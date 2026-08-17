# Releasing the Android SDK

Stable releases are published as `ad.simula:ad-sdk:<version>` to Maven Central by
`.github/workflows/release.yml`. Maven Central versions are immutable. The workflow performs the
complete release from a source commit on `main`; do not create the release tag or publish to Maven
Central manually first.

## One-time GitHub setup

Create a `Maven` environment, require a reviewer, and add these environment secrets:

- `MAVEN_CENTRAL_USERNAME`: Central Portal publishing token username
- `MAVEN_CENTRAL_PASSWORD`: Central Portal publishing token password
- `MAVEN_SIGNING_KEY`: ASCII-armored GPG private key
- `MAVEN_SIGNING_KEY_ID`: signing key ID
- `MAVEN_SIGNING_PASSWORD`: private-key passphrase

Allow GitHub Actions to write repository contents so the workflow can push the release tag and
create the GitHub release. Protect `main` and `v*` tags, while allowing the release workflow to
create tags after any configured environment approval. Require the CI workflow before merging to
`main` or `dev`.

## Release steps

1. Update the same version in `simula-ad-sdk/build.gradle.kts`,
   `telemetry/Telemetry.kt`, and `SimulaAdSdk.kt`.
2. Run `./gradlew :simula-ad-sdk:verifyVersionConsistency`,
   `./gradlew compileDebugKotlin`, and `./gradlew testDebugUnitTest`.
3. Merge the version change to `main` after CI passes.
4. In GitHub Actions, run **Release to Maven Central** from `main` and enter the version
   without a leading `v`, for example `1.2.3`. Test releases use `MAJOR.MINOR.PATCH-dev.NUMBER`,
   for example `1.2.4-dev.1`.

The workflow rejects an existing Git tag, GitHub release, or Maven Central version. It verifies that
the dispatch is the current `main` tip with a successful CI run, matches all runtime version
constants, runs the full release gate, signs and publishes the artifacts, creates the `v<version>`
tag, and creates the GitHub release. Test releases are marked as GitHub prereleases.

If the workflow fails after Maven Central accepts the version but before the GitHub release is
published, create the `v<version>` tag and GitHub release from that same `main` commit and attach
the AAR. Never reuse a published Maven Central version.

Maven Central versions are immutable, including test releases. Increment the `-dev.NUMBER` suffix for
every test attempt. Use Maven Local or a separate internal repository for snapshots.
