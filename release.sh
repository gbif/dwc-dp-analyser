#!/usr/bin/env bash
#
# release.sh — manual release helper for dwc-dp-analyser.
#
#   ./release.sh          # build and package the latest stable tag from dev
#   ./release.sh 1.0.0    # package an already-built runner jar
#
# What it does:
#   1. Finds the versioned CLI runner jar under dwc-dp-analyser-cli/target/
#      for the selected version.
#   2. Copies it to a staging dir under a fixed, version-agnostic name —
#      this fixed name is what nfpm.yaml actually packages, so nfpm never
#      has to know or care about the Maven version in the filename.
#   3. Runs nfpm three times (deb, rpm, archlinux/pacman) and assembles a
#      Windows portable zip (bin/ + lib/, matching what the .bat wrapper
#      expects). The standalone JAR also lands in dist/.
#   4. Generates a checksums.txt (SHA256) covering every artifact in
#      dist/, then GPG-signs checksums.txt with a detached armored
#      signature so downloaders can verify both integrity and origin.
#
# Requires: nfpm installed and on PATH (https://nfpm.goreleaser.com), and
# `zip` available for the Windows archive. GPG signing is done with
# whatever key `gpg` picks as default (or GPG_KEY_ID if set) — skip with
# SKIP_SIGN=1 if you don't want a signature for a given run.

set -euo pipefail

if [ $# -gt 1 ]; then
  echo "usage: $0 [version]" >&2
  exit 1
fi

# --- fail fast if the tools this script shells out to aren't installed ---
check_dependencies() {
  local missing=0

  if ! command -v nfpm >/dev/null 2>&1; then
    echo "error: nfpm not found on PATH" >&2
    echo "  install: https://nfpm.goreleaser.com/install/" >&2
    missing=1
  fi

  if ! command -v zip >/dev/null 2>&1; then
    echo "error: zip not found on PATH" >&2
    echo "  install: e.g. 'sudo pacman -S zip' / 'sudo apt install zip'" >&2
    missing=1
  fi

  if ! command -v sha256sum >/dev/null 2>&1; then
    echo "error: sha256sum not found on PATH" >&2
    missing=1
  fi

  if ! command -v java >/dev/null 2>&1; then
    echo "error: java not found on PATH" >&2
    missing=1
  fi

  if [ "${SKIP_SIGN:-0}" != "1" ] && ! command -v gpg >/dev/null 2>&1; then
    echo "error: gpg not found on PATH (set SKIP_SIGN=1 to release without signing)" >&2
    missing=1
  fi

  if [ "$missing" -eq 1 ]; then
    exit 1
  fi
}

check_dependencies

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="${ROOT_DIR}/dist"
SOURCE_DIR="${ROOT_DIR}"
VERSION="${1:-}"

if [ $# -eq 0 ]; then
  if [ "$(git -C "${ROOT_DIR}" branch --show-current)" != dev ]; then
    echo "error: run the automatic release from the dev branch" >&2
    exit 1
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    echo "error: mvn not found on PATH" >&2
    exit 1
  fi

  git -C "${ROOT_DIR}" fetch --tags origin
  tags="$(git -C "${ROOT_DIR}" tag --merged HEAD --sort=-version:refname --list 'dwc-dp-analyser-*')"
  TAG="$(awk '/^dwc-dp-analyser-[0-9]+\.[0-9]+\.[0-9]+$/ && !found { print; found = 1 }' <<< "${tags}")"
  if [ -z "${TAG}" ]; then
    echo "error: no stable dwc-dp-analyser-<version> tag is reachable from dev" >&2
    exit 1
  fi
  VERSION="${TAG#dwc-dp-analyser-}"
  echo "==> building ${TAG}"
  WORK_DIR="$(mktemp -d)"
  SOURCE_DIR="${WORK_DIR}/checkout"
  cleanup() {
    git -C "${ROOT_DIR}" worktree remove --force "${SOURCE_DIR}" 2>/dev/null || true
    rmdir "${WORK_DIR}"
  }
  trap cleanup EXIT
  git -C "${ROOT_DIR}" worktree add --detach "${SOURCE_DIR}" "refs/tags/${TAG}"
  (cd "${SOURCE_DIR}" && mvn clean package)
  # The caller publishes the release before updating the tap.
elif [[ ! "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: expected a stable version such as 1.0.0" >&2
  exit 1
fi

STAGING_DIR="${SOURCE_DIR}/packaging/staging"
SOURCE_JAR="${SOURCE_DIR}/dwc-dp-analyser-cli/target/dwc-dp-analyser-cli-${VERSION}-runner.jar"
if [ ! -f "${SOURCE_JAR}" ]; then
  echo "error: runner jar missing for ${VERSION}: ${SOURCE_JAR}" >&2
  echo "  run 'mvn clean package' from a checkout at the matching project version" >&2
  exit 1
fi
java -jar "${SOURCE_JAR}" --help

# nFPM resolves the file paths in its config relative to the working directory.
cd "${SOURCE_DIR}"

echo "==> cleaning ${DIST_DIR}"
# dist/ is release output only — wipe it at the start of every run so it
# always reflects just this version's artifacts, not a pileup from every
# version ever released.
rm -rf "${DIST_DIR}"
mkdir -p "${STAGING_DIR}" "${DIST_DIR}"
cp "${SOURCE_JAR}" "${STAGING_DIR}/dwc-dp-analyser-cli-runner.jar"
echo "==> staged as ${STAGING_DIR}/dwc-dp-analyser-cli-runner.jar"

# Also drop a version-named copy of the jar straight into dist/. This is
# the artifact the Homebrew formula points at — no tar/zip needed, since
# the formula just tells Homebrew to write its own "java -jar" wrapper at
# install time. Riding along in dist/ means it's covered by checksums.txt
# and the GPG signature like everything else.
cp "${SOURCE_JAR}" "${DIST_DIR}/dwc-dp-analyser-${VERSION}.jar"
echo "==> copied jar to ${DIST_DIR}/dwc-dp-analyser-${VERSION}.jar (Homebrew release asset)"

echo "==> building .deb"
VERSION="${VERSION}" nfpm package \
  --config "${SOURCE_DIR}/packaging/nfpm.yaml" \
  --target "${DIST_DIR}/" \
  --packager deb

echo "==> building .rpm"
VERSION="${VERSION}" nfpm package \
  --config "${SOURCE_DIR}/packaging/nfpm.yaml" \
  --target "${DIST_DIR}/" \
  --packager rpm

echo "==> building pacman package"
VERSION="${VERSION}" nfpm package \
  --config "${SOURCE_DIR}/packaging/nfpm.yaml" \
  --target "${DIST_DIR}/" \
  --packager archlinux

echo "==> building windows portable zip"
# The .bat wrapper looks for the jar at ..\lib\ relative to itself, so the
# zip has to mirror that bin/ + lib/ split — same shape a real installer
# would use, just without an installer.
WIN_STAGE_DIR="${STAGING_DIR}/windows/dwc-dp-analyser-${VERSION}"
rm -rf "${WIN_STAGE_DIR}"
mkdir -p "${WIN_STAGE_DIR}/bin" "${WIN_STAGE_DIR}/lib"
cp "${SOURCE_DIR}/packaging/dwc-dp-analyser.bat" "${WIN_STAGE_DIR}/bin/"
cp "${STAGING_DIR}/dwc-dp-analyser-cli-runner.jar" "${WIN_STAGE_DIR}/lib/dwc-dp-analyser-cli.jar"

(
  cd "${STAGING_DIR}/windows"
  zip -qr "${DIST_DIR}/dwc-dp-analyser-${VERSION}-windows.zip" "dwc-dp-analyser-${VERSION}"
)

echo "==> generating checksums.txt"
(
  cd "${DIST_DIR}"
  sha256sum -- * > checksums.txt
)
echo "==> wrote ${DIST_DIR}/checksums.txt"

if [ "${SKIP_SIGN:-0}" = "1" ]; then
  echo "==> SKIP_SIGN=1, not signing checksums.txt"
else
  echo "==> signing checksums.txt"
  GPG_SIGN_ARGS=(--detach-sign --armor)
  if [ -n "${GPG_KEY_ID:-}" ]; then
    GPG_SIGN_ARGS+=(--local-user "${GPG_KEY_ID}")
  fi
  gpg "${GPG_SIGN_ARGS[@]}" --output "${DIST_DIR}/checksums.txt.asc" "${DIST_DIR}/checksums.txt"
  echo "==> wrote ${DIST_DIR}/checksums.txt.asc"
  echo "    verify with: gpg --verify checksums.txt.asc checksums.txt"
fi

echo "==> verifying release artifacts"
(
  cd "${DIST_DIR}"
  sha256sum -c checksums.txt
  if [ "${SKIP_SIGN:-0}" != "1" ]; then
    gpg --verify checksums.txt.asc checksums.txt
  fi
)

echo "==> generating Homebrew formula"
# The formula just needs a download URL + sha256 for the jar we already
# copied into dist/ above. We assume it'll be uploaded as a GitHub Release
# asset on this same repo under the dwc-dp-analyser-${VERSION} tag
# — override GITHUB_REPO if that's ever not the case, or set
# SKIP_FORMULA=1 to opt out of generating it for a given run.
if [ "${SKIP_FORMULA:-0}" = "1" ]; then
  echo "==> SKIP_FORMULA=1, not generating Homebrew formula"
else
  if [ -n "${GITHUB_REPO:-}" ]; then
    REPO="${GITHUB_REPO}"
  else
    ORIGIN_URL="$(git -C "${ROOT_DIR}" remote get-url origin 2>/dev/null || true)"
    # handles both git@github.com:owner/repo.git and
    # https://github.com/owner/repo.git (with or without trailing .git).
    # Strip any trailing .git first — sed's ERE has no non-greedy
    # quantifiers, so trying to do both in one lazy pattern silently
    # swallows ".git" into the repo name instead of stripping it.
    ORIGIN_URL="${ORIGIN_URL%.git}"
    REPO="$(printf '%s' "${ORIGIN_URL}" | sed -nE 's#^(git@github\.com:|https://github\.com/)([^/]+/[^/]+)$#\2#p')"
  fi

  if [ -z "${REPO:-}" ]; then
    echo "warn: could not determine owner/repo from git remote, skipping formula generation" >&2
    echo "      set GITHUB_REPO=owner/repo to generate it manually, e.g.:" >&2
    echo "      GITHUB_REPO=your-org/dwc-dp-analyser $0 ${VERSION}" >&2
  else
    JAR_SHA256="$(sha256sum "${DIST_DIR}/dwc-dp-analyser-${VERSION}.jar" | awk '{print $1}')"
    FORMULA_DIR="${ROOT_DIR}/Formula"
    FORMULA_PATH="${FORMULA_DIR}/dwc-dp-analyser.rb"
    mkdir -p "${FORMULA_DIR}"

    cat > "${FORMULA_PATH}" <<EOF
class DwcDpAnalyser < Formula
  desc "Validator/analyser CLI for DwC-DP data packages"
  homepage "https://github.com/${REPO}"
  url "https://github.com/${REPO}/releases/download/dwc-dp-analyser-${VERSION}/dwc-dp-analyser-${VERSION}.jar"
  sha256 "${JAR_SHA256}"
  license "Apache-2.0"

  # Adjust the openjdk version constraint here if the CLI needs a specific
  # Java release (e.g. "openjdk@21") rather than whatever's latest.
  depends_on "openjdk"

  def install
    libexec.install "dwc-dp-analyser-${VERSION}.jar" => "dwc-dp-analyser-cli.jar"
    (bin/"dwc-dp-analyser").write <<~SCRIPT
      #!/bin/bash
      exec "#{formula_opt_bin("openjdk")}/java" --enable-native-access=ALL-UNNAMED -jar "#{libexec}/dwc-dp-analyser-cli.jar" "\$@"
    SCRIPT

    # picocli ships a completion generator (AutoComplete) baked into the
    # runner jar already — no extra build step needed. Run it against this
    # same jar so the completion script always matches the version being
    # installed. picocli only emits a bash-style script, so zsh gets a
    # small wrapper that loads bashcompinit and sources it, rather than a
    # native #compdef function.
    system formula_opt_bin("openjdk")/"java", "-cp", libexec/"dwc-dp-analyser-cli.jar",
           "picocli.AutoComplete", "--force", "-n", "dwc-dp-analyser",
           "-o", "dwc-dp-analyser_completion", "org.gbif.dp.cli.Config"
    bash_completion.install "dwc-dp-analyser_completion" => "dwc-dp-analyser"

    (zsh_completion/"_dwc-dp-analyser").write <<~ZSH
      #compdef dwc-dp-analyser
      autoload -U +X bashcompinit && bashcompinit
      # picocli's generated completion script calls compopt, a bash 4+
      # builtin with no equivalent in bashcompinit's zsh emulation — stub
      # it as a no-op so it doesn't spam "command not found" on every tab.
      compopt() { :; }
      source "#{bash_completion}/dwc-dp-analyser"
    ZSH
  end

  test do
    system "#{bin}/dwc-dp-analyser", "--help"
  end
end
EOF
    echo "==> wrote ${FORMULA_PATH}"
    echo "    once dist/dwc-dp-analyser-${VERSION}.jar is uploaded as a release asset on"
    echo "    ${REPO} (tag dwc-dp-analyser-${VERSION}), copy this file into the"
    echo "    Formula/ directory of your workspace checkout of the Homebrew tap."
    echo "    Commit and push the formula there, then run brew update and brew upgrade."
  fi
fi

echo "==> done. artifacts in ${DIST_DIR}/:"
ls -1 "${DIST_DIR}"
echo "==> GitHub release tag: dwc-dp-analyser-${VERSION}"
