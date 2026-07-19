# Release process

Kendive releases are immutable. The repository must be releasable without reading credentials and
the local gate never publishes outside its task-owned staging directory.

## Candidate checklist

1. Set an immutable semantic version in `gradle.properties` and update `CHANGELOG.md`.
2. Provision Java 25, Node, rustup/Cargo, CMake, Xcode, and the Gradle dependency caches. The exact
   Rust release and compiler commit are the `rustRelease` and `rustReleaseCommit` entries in
   `gradle/libs.versions.toml`; do not substitute the moving `stable` alias.
3. While still online, download and verify the repository's pinned external release inputs:

   ```shell
   ./gradlew --no-daemon prepareReleaseDependencies
   ```

   Downloads are written to temporary files, verified by their checked-in revision or SHA-256,
   and only then moved into their task-owned locations. The task also verifies the
   release-pinned Rust compiler (both release and commit), installs targets for that exact
   toolchain, and performs locked Cargo fetches with it. A later offline gate uses Cargo's offline
   mode and fails before fetching if any pinned input, compiler version, Rust target, or locked
   crate is missing.

4. Verify the checkout contains only the intended release changes:

   ```shell
   git diff --check
   git status --short
   ```

5. Run the complete gate on macOS so JVM, wasmJs, Android publication checks, and iOS simulator
   tests are covered. The gate also runs the recursively inventoried, pinned WebAssembly core
   testsuite, tests the Rust Wasmtime Preview3 bridge, builds JVM/iOS/wasm standalone consumers
   and the Android instrumentation consumer from the staged Maven repository, and rejects
   untracked disabled tests or Gradle tasks:

   ```shell
   ./gradlew --no-daemon --offline releaseGate
   ```

6. Confirm `build/release-staging-repository` contains only the selected immutable version and
   the curated public module set, and retains the gate-generated `SHA256SUMS`. Use that repository
   for downstream Suvio, SDK, and representative plugin acceptance tests.
7. Review the generated ABI dumps and release notes. Any intentional public API difference must
   be represented in both.
8. Tag or publish only after the Kendive gate and all downstream acceptance tests pass from a
   clean checkout. Publishing is performed by the separately reviewed release workflow, never by
   `releaseGate`.

## Public publication

`releaseGate` deliberately produces an unsigned, local staging repository. Before a public Maven
Central upload, the authorized release workflow must additionally:

1. verify every non-POM artifact has matching sources and Javadoc artifacts and that every POM
   contains the project name, description, URL, license, developers, and SCM coordinates;
2. create an ASCII-armored PGP `.asc` signature for every uploaded file, including checksums and
   metadata;
3. build the Maven Central Publisher Portal bundle from the exact, already verified staging
   contents; and
4. upload and publish that immutable bundle under a separately reviewed release identity.

The signing key and Publisher Portal token are never read by Gradle configuration or by
`releaseGate`. The release runner receives them through file-mounted, least-privilege secret
provisioning and must not print, persist, or expose their values. Verification uses only upload
status and published artifact coordinates.

Maven Central requirements:

- <https://central.sonatype.org/publish/requirements/>
- <https://central.sonatype.org/publish/publish-portal-gradle/>

## Failure policy

- Do not skip, disable, or exclude a failing test to make a candidate pass.
- Do not reuse a published version. Fix the issue and create the next release candidate.
- Do not broaden filesystem, network, import, memory, or credential capabilities as a
  compatibility workaround. Migrate the consumer to an explicit capability instead.
- Do not publish artifacts produced while Gradle resolved dependencies online during the gate.

## Evidence to retain

Retain the gate log, exact commit, staging repository checksums, ABI diff, downstream SDK/Suvio
test results, and the list of plugin packages validated against the candidate.
