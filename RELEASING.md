# Releasing dwc-dp-analyser

## Build the CLI release from dev

Install Go Task, JDK 17 or later, Maven, Bash, Git, `nfpm`, `zip`, `sha256sum`, and GPG with a signing key. From the repository root, update your local `dev` branch and run the release task.

```bash
git switch dev
git pull --ff-only origin dev
task release
```

[Taskfile.dist.yaml](Taskfile.dist.yaml) calls [release.sh](release.sh). The script fetches tags from `origin` and picks the highest `dwc-dp-analyser-<major>.<minor>.<patch>` tag reachable from your local `dev` branch. A tag ending in `-SNAPSHOT` or a prerelease suffix is skipped. For example, if `dev` has moved to `0.0.17-SNAPSHOT` and its latest stable tag is `dwc-dp-analyser-0.0.16`, the task packages `0.0.16`.

If the version you intend to ship exists only as a snapshot, first prepare a commit on `dev` with the release version in the reactor POMs and the appropriate [changelog](CHANGELOG.md) entry. Tag that commit with `dwc-dp-analyser-<version>`. Push the tag to GitHub before creating the GitHub release. The CLI workflow uses Maven to build locally and can run independently of deployment to the Maven repository.

The script runs `mvn clean package` in a temporary Git worktree at the selected tag, checks for the exact versioned runner JAR, and runs its `--help` command. It replaces `dist/` in your original checkout with Debian, RPM, and Arch Linux packages, a Windows portable ZIP, a standalone JAR, and a SHA-256 checksum manifest with a detached GPG signature. It verifies the manifest and signature before reporting the tag to publish.

Set `GPG_KEY_ID` to select a signing key. For an unsigned rehearsal, run `SKIP_SIGN=1 task release`. To package an already-built runner JAR for a specific stable version, run `./release.sh <version>` from its checkout.

## Publish the CLI packages

Create a GitHub release manually using the tag printed by the task. Add the changelog entry as release notes, attach every file from `dist/`, and review the draft before publishing. If a release already exists for the tag, use that release to upload the assets.

With an authenticated GitHub CLI, you can create the draft and attach the files in one command. Replace `<version>` with the version reported by the task.

```bash
gh release create 'dwc-dp-analyser-<version>' dist/* \
  --repo gbif/dwc-dp-analyser --verify-tag --draft
```

## Update the Homebrew tap

The script generates `Formula/dwc-dp-analyser.rb` in your original checkout using the expected public JAR URL and its SHA-256 checksum. It derives the repository from `origin`. Set `GITHUB_REPO=gbif/dwc-dp-analyser` if packaging from a fork for the GBIF release, or use `SKIP_FORMULA=1` to skip formula generation.

Once the GitHub release and JAR are public, copy the formula into `Formula/` in the Homebrew tap, then commit and push it there. The script defaults to the `wisienkas/dwc-dp-analyser` tap. The automatic release task skips the interactive tap update.

## Deploy the Maven modules when needed

The [Jenkins pipeline](Jenkinsfile) handles Maven deployment from `master` with `RELEASE` enabled. It runs `release:prepare` and `release:perform`, which manage their own release tag. For a version that already has a manual tag, coordinate Maven deployment before running that pipeline for the same version.
