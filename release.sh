#!/usr/bin/env bash
#
# release.sh — manual release helper for dwc-dp-analyser.
#
#   ./release.sh 1.0.0
#
# What it does:
#   1. Finds the versioned CLI runner jar under dwc-dp-analyser-cli/target/
#      (Maven/Quarkus bakes the pom version into the filename, so this is
#      a glob match, not a fixed path).
#   2. Copies it to a staging dir under a fixed, version-agnostic name —
#      this fixed name is what nfpm.yaml actually packages, so nfpm never
#      has to know or care about the Maven version in the filename.
#   3. Runs nfpm three times (deb, rpm, archlinux/pacman) and assembles a
#      Windows portable zip (bin/ + lib/, matching what the .bat wrapper
#      expects) — four artifacts land in dist/.
#   4. Generates a checksums.txt (SHA256) covering every artifact in
#      dist/, then GPG-signs checksums.txt with a detached armored
#      signature so downloaders can verify both integrity and origin.
#
# Requires: nfpm installed and on PATH (https://nfpm.goreleaser.com), and
# `zip` available for the Windows archive. GPG signing is done with
# whatever key `gpg` picks as default (or GPG_KEY_ID if set) — skip with
# SKIP_SIGN=1 if you don't want a signature for a given run.

set -euo pipefail

if [ $# -ne 1 ]; then
  echo "usage: $0 <version>" >&2
  echo "  e.g.: $0 1.0.0" >&2
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

  if [ "${SKIP_SIGN:-0}" != "1" ] && ! command -v gpg >/dev/null 2>&1; then
    echo "error: gpg not found on PATH (set SKIP_SIGN=1 to release without signing)" >&2
    missing=1
  fi

  if [ "$missing" -eq 1 ]; then
    exit 1
  fi
}

check_dependencies

VERSION="$1"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI_TARGET_DIR="${ROOT_DIR}/dwc-dp-analyser-cli/target"
STAGING_DIR="${ROOT_DIR}/packaging/staging"
DIST_DIR="${ROOT_DIR}/dist"

echo "==> looking for the runner jar in ${CLI_TARGET_DIR}"

# The Maven/Quarkus build embeds the pom version in the filename
# (dwc-dp-analyser-cli-${VERSION}-runner.jar), so glob rather than assume
# a fixed name. If more than one match turns up (stale jar from a previous
# build left behind), fail loudly instead of silently picking one.
shopt -s nullglob
matches=("${CLI_TARGET_DIR}"/dwc-dp-analyser-cli-*-runner.jar)
shopt -u nullglob

if [ ${#matches[@]} -eq 0 ]; then
  echo "error: no runner jar found matching dwc-dp-analyser-cli-*-runner.jar" >&2
  echo "  did you run 'mvn package' in dwc-dp-analyser-cli first?" >&2
  exit 1
elif [ ${#matches[@]} -gt 1 ]; then
  echo "error: found more than one candidate runner jar, refusing to guess:" >&2
  printf '  %s\n' "${matches[@]}" >&2
  echo "  run 'mvn clean package' to remove stale build output, then retry" >&2
  exit 1
fi

SOURCE_JAR="${matches[0]}"
echo "==> found: ${SOURCE_JAR}"

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
  --config "${ROOT_DIR}/packaging/nfpm.yaml" \
  --target "${DIST_DIR}/" \
  --packager deb

echo "==> building .rpm"
VERSION="${VERSION}" nfpm package \
  --config "${ROOT_DIR}/packaging/nfpm.yaml" \
  --target "${DIST_DIR}/" \
  --packager rpm

echo "==> building pacman package"
VERSION="${VERSION}" nfpm package \
  --config "${ROOT_DIR}/packaging/nfpm.yaml" \
  --target "${DIST_DIR}/" \
  --packager archlinux

echo "==> building windows portable zip"
# The .bat wrapper looks for the jar at ..\lib\ relative to itself, so the
# zip has to mirror that bin/ + lib/ split — same shape a real installer
# would use, just without an installer.
WIN_STAGE_DIR="${STAGING_DIR}/windows/dwc-dp-analyser-${VERSION}"
rm -rf "${WIN_STAGE_DIR}"
mkdir -p "${WIN_STAGE_DIR}/bin" "${WIN_STAGE_DIR}/lib"
cp "${ROOT_DIR}/packaging/dwc-dp-analyser.bat" "${WIN_STAGE_DIR}/bin/"
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

echo "==> generating Homebrew formula"
# The formula just needs a download URL + sha256 for the jar we already
# copied into dist/ above. We assume it'll be uploaded as a GitHub Release
# asset on this same repo (e.g. via `gh release create v${VERSION} dist/*`)
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
    echo "    ${REPO} (tag dwc-dp-analyser-${VERSION}), copy this file into your"
    echo "    homebrew-tap repo's Formula/ directory, commit, and push. Then:"
    echo "      brew tap ${REPO%%/*}/dwc-dp-analyser"
    echo "      brew install dwc-dp-analyser"
  fi
fi

echo "==> done. artifacts in ${DIST_DIR}/:"
ls -1 "${DIST_DIR}"