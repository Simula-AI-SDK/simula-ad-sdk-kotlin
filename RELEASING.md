# Releasing the Android SDK

Stable releases are published as `ad.simula:ad-sdk:<version>` to Maven Central by
`.github/workflows/release.yml`. Maven Central versions are immutable.

## One-time GitHub setup

Create a `maven-central` environment, require a reviewer, and add these environment secrets:

- `MAVEN_CENTRAL_USERNAME`: Central Portal publishing token username
- `MAVEN_CENTRAL_PASSWORD`: Central Portal publishing token password
- `MAVEN_SIGNING_KEY`: ASCII-armored GPG private key
- `MAVEN_SIGNING_KEY_ID`: signing key ID
- `MAVEN_SIGNING_PASSWORD`: private-key passphrase

Protect `main`, `dev`, and `v*` tags. Require the CI workflow before merging to either branch and
restrict release-tag creation to release maintainers.

## Release steps

1. Update the same version in `simula-ad-sdk/build.gradle.kts`,
   `telemetry/Telemetry.kt`, and `SimulaAdSdk.kt`.
2. Run `./gradlew :simula-ad-sdk:verifyVersionConsistency`,
   `./gradlew compileDebugKotlin`, and `./gradlew testDebugUnitTest`.
3. Merge the version change to `main` after CI passes.
4. Create and push the stable tag from that exact `main` commit:

   ```bash
   git tag -a v1.2.3 -m "Release 1.2.3"
   git push origin v1.2.3
   ```

The workflow verifies that the tag is a stable semantic version, belongs to `main`, matches all
runtime version constants, and does not already exist on Maven Central. It then runs the full release
gate, signs and publishes the artifacts, and creates the GitHub release.

Development builds must not be published to Maven Central. Use Maven Local or a separate internal
repository for snapshots.
