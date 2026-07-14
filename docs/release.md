<!-- SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC -->
<!-- SPDX-License-Identifier: MPL-2.0 -->

# Release Process

Fluxy Java has two release tracks:

- **Public free release:** independently hosted, MPL-2.0, `freeModule=true`, and no IA licensing service.
- **IA-integrated release:** future Module Showcase or commercial integration using IA's assigned module prefix and any applicable review/licensing setup.

Neither track may imply Inductive Automation certification, approval, support, or endorsement without express authorization.

## Required Public-Release Inputs

- Clean Git worktree at an immutable, pushed `v<module-version>` tag.
- Exact source commit and tag embedded in the module.
- Completed provenance record in `PROVENANCE.md`.
- MPL-2.0, historical MIT notice, Apache-2.0 wrapper license, and third-party notices.
- Controlled signing key and certificate chain stored outside Git.
- Completed Java compatibility matrix and artifact audit.
- Working private security-reporting channel.

IA assigned Green Pipe Partners the module prefix `partners.greenpipe`; every release must use module ID `partners.greenpipe.fluxy`. IA's module descriptor and Gradle plugin do not use a separate numeric vendor ID. Licensed IA-integrated builds remain blocked while `iaLicensingReady` is false.

## Source Identity

The release tag for version `0.1.5.20260714` is:

```text
v0.1.5.20260714
```

The artifact embeds immutable links to:

```text
https://github.com/GreenPipePartners/Fluxy-modl/tree/<commit>
https://github.com/GreenPipePartners/Fluxy-modl/archive/<commit>.tar.gz
```

Keep tagged corresponding source available for every distributed binary.

## Development Matrix

Run before tagging:

```bash
./gradlew -PignitionTarget=8.1.50 clean test packageDevelopment
./gradlew -PignitionTarget=8.1.51 clean test packageDevelopment
./gradlew -PignitionTarget=8.3.4 clean test packageDevelopment
./gradlew -PignitionTarget=8.3.6 clean test packageDevelopment
```

The default license mode is `free`. Development artifacts are unsigned and must not be published.

## Tag And Build Candidates

After review, commit and push `main`, then create and push the exact version tag. Build each public candidate from that clean tagged checkout:

```bash
COMMIT=$(git rev-parse HEAD)
TAG=v0.1.5.20260714

./gradlew \
  -PignitionTarget=8.1 \
  -PlicenseMode=free \
  -PpublicRelease=true \
  -PsourceCommit="$COMMIT" \
  -PsourceTag="$TAG" \
  clean test packageReleaseCandidate

./gradlew \
  -PignitionTarget=8.3 \
  -PlicenseMode=free \
  -PpublicRelease=true \
  -PsourceCommit="$COMMIT" \
  -PsourceTag="$TAG" \
  clean test packageReleaseCandidate
```

The task refuses dirty, untagged, unpushed, incorrectly licensed, or source-mismatched builds. Candidates are named `*.unsigned.modl` and are not release assets.

## Inspect Candidates

```bash
python3 scripts/verify_release_artifact.py \
  release/Fluxy-Ignition81-Free-0.1.5.20260714.unsigned.modl \
  --ignition-version 8.1.50 \
  --source-commit "$COMMIT" \
  --source-tag "$TAG"

python3 scripts/verify_release_artifact.py \
  release/Fluxy-Ignition83-Free-0.1.5.20260714.unsigned.modl \
  --ignition-version 8.3.4 \
  --source-commit "$COMMIT" \
  --source-tag "$TAG"
```

## Sign

Use Inductive Automation's module-signing tool with a protected keystore and certificate chain:

```bash
java -jar /secure/module-signer.jar \
  -keystore=/secure/fluxy-signing.p12 \
  -keystore-pwd=<provided-securely> \
  -alias=<signing-alias> \
  -alias-pwd=<provided-securely> \
  -chain=/secure/fluxy-signing-chain.p7b \
  -module-in=release/Fluxy-Ignition83-Free-0.1.5.20260714.unsigned.modl \
  -module-out=release/Fluxy-Ignition83-Free-0.1.5.20260714.modl
```

Repeat for 8.1. Do not put literal passwords in shell history, CI logs, Gradle properties, or the repository. A self-signed certificate is supported for an independently hosted free beta; a CA-issued code-signing certificate provides stronger assurance for public users.

## Release Verification

- Install both signed artifacts on Gateways where unsigned modules are disabled.
- Confirm certificate identity/fingerprint and the installation trust prompt.
- Confirm `Fluxy Free`, `freeModule=true`, module ID `partners.greenpipe.fluxy`, minimum Ignition version, and vendor display.
- Run authenticated project scan, tag read/write/configure/delete, audit, and historian closed-loop tests.
- Exercise upgrade and uninstall behavior.
- Verify the archives contain only Fluxy code and compliance files, with no IA SDK/runtime JARs.
- Generate SHA-256 sidecars from the final signed artifacts.
- Scan source and artifacts for credentials, customer data, private keys, and certificate passwords.

## Publish

Publish one immutable website or GitHub release containing:

- Signed 8.1 and 8.3 `.modl` files.
- SHA-256 sidecars for the signed files.
- Certificate fingerprint and whether it is self-signed or CA-issued.
- CycloneDX SBOMs.
- Compatibility, installation, support, and security links.
- Link to the exact source tag and archive.
- Clear statement that Fluxy is third-party software not endorsed or supported by IA.

Never publish the unsigned release candidates.
