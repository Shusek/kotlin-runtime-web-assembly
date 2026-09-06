# Contributing guide

**Want to contribute? Great!**

We try to make it easy, and all contributions, even the smaller ones, are more than welcome.
This includes bug reports, fixes, documentation, examples...
But first, read this page (including the small print at the end).

* [Coding Philosophy](#coding-philosophy)
* [Before you contribute](#before-you-contribute)
  + [Code reviews](#code-reviews)
  + [Coding Guidelines](#coding-guidelines)
  + [Continuous Integration](#continuous-integration)
  + [Tests and documentation are not optional](#tests-and-documentation-are-not-optional)
  + [Current status](#current-status)
* [Reporting an issue](#reporting-an-issue)
* [Legal](#legal)
* [The small print](#the-small-print)

## Coding Philosophy

Writing a runtime is a big challenge. We want Kotlin Runtime Web Assembly to always be a solid foundation
for running Wasm across supported Kotlin targets. In order to accomplish this, it's going to take a large team
of diverse contributors. That's why our goal up front is to aim for writing
simple code that's easy to understand and is as backwards compatible as possible.

The reason is we want to optimize for:

 * attracting more contributors
 * supporting more users
 * supporting more platforms

It's important we focus on this in the beginning phase so that we can grow a large team
of contributors. We also want to make it possible for people with deep Wasm and runtime experience,
but maybe not the deepest Java experience, to contribute.

This philosophy tends to lead us down what might seem like some non-optimal paths. We may ask you
to simplify things, use conservative language/runtime features, or reject improvements that we feel
makes things more confusing without enough measurable benefits.

We don't expect to be able to maintain this forever, and some parts of the codebase will
inevitably suffer from necessary complexity in the name of correctness, safety, or speed.
But we are holding the line as long as we can.

## Before you contribute

To contribute, use GitHub Pull Requests, from your **own** fork.

Also, make sure you have set up your Git authorship correctly:

```
git config --global user.name "Your Full Name"
git config --global user.email your.email@example.com
```

If you use different computers to contribute, please make sure the name is the same on all your computers.

We may use this information to acknowledge your contributions!

### Code reviews

All submissions, including submissions by project members, need to be reviewed and approved by at least one project owner before being merged.

[GitHub Pull Request Review Process](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/reviewing-changes-in-pull-requests/about-pull-request-reviews) is followed for every pull request.

### Coding Guidelines

 * We primarily use the Git history to track authorship. GitHub also has [this nice page with your contributions](https://github.com/Shusek/kotlin-runtime-web-assembly/graphs/contributors).
 * Please take care to write code that fits with existing code styles.
 * Commits should be atomic and semantic. Please properly squash your pull requests before submitting them. Fixup commits can be used temporarily during the review process but things should be squashed at the end to have meaningful commits.
 * We typically squash and merge pull requests when they are approved. This tends to keep the commit history a little bit more tidy without placing undue burden on the developers.

### Building the Runtime

Contributors and other advanced users may want to build the runtime from source. To do so, you'll need Java 25 and the Gradle wrapper.

Basic steps:

* `./gradlew --no-daemon test --continue` to run all tests.
* `./gradlew --no-daemon publishToMavenLocal -x test` to publish local artifacts while skipping tests.
* `./scripts/compile-resources.sh` will recompile and regenerate the `resources/compiled` folders

### Local release gate

Before preparing a release candidate, run the aggregate gate with an immutable candidate version:

```shell
./gradlew --no-daemon prepareReleaseDependencies
./gradlew --no-daemon --offline -Pversion=0.3.0-rc.12 releaseGate
```

`prepareReleaseDependencies` is the repository-owned online preparation step. It downloads pinned
native sources, tools, adapters, and conformance inputs into task-owned locations, verifies every
download against its checked-in revision or SHA-256, installs the exact Rust toolchain declared by
`rustRelease` in `gradle/libs.versions.toml`, verifies its `rustReleaseCommit`, installs targets for
that toolchain, and populates Cargo's locked dependency cache. Run it before disconnecting the
release runner. If a verified input, pinned compiler, Rust target, or locked crate is unavailable,
the offline gate fails without trying to fetch it and points back to the preparation command.

The gate runs project checks and tests, Kotlin ABI validation, Gradle plugin validation, and the
disabled-test policy. It then publishes Maven artifacts only to
`build/release-staging-repository`. The staging repository is recreated by the gate; it does not
write to Maven Local and it does not invoke any externally configured publishing repository.

Run the gate with `--offline` after Gradle dependencies, toolchains, and the verified release inputs
have been provisioned. This keeps release verification independent of network availability and
prevents accidental resolution from changing during the gate.

Disabled tests must include both a useful reason and a durable tracking reference in the annotation,
for example:

```kotlin
@Disabled("Flaky on macOS; tracked by #123")
```

An issue URL or tracker ID such as `KRWA-123` is also accepted. `@Ignore` and TestNG
`@Test(enabled = false)` follow the same rule. A disabled test without such a reference fails
`verifyNoUnjustifiedDisabledTests`, which is part of both `check` and `releaseGate`.

### Proposals implementation

Our priority is to focus on implementing [proposals](https://github.com/WebAssembly/proposals) that are in the most advanced stages of development. While we wholeheartedly encourage and support explorations, we’ll be dedicating less time to early-stage proposals until we have more comprehensive support for those that are stabilized.

### Continuous Integration

Because we are all humans, and to ensure Kotlin Runtime Web Assembly evolves in the right direction, all changes must pass continuous integration before being merged. The CI is based on GitHub Actions, which means that pull requests will receive automatic feedback.  Please watch out for the results of these workflows to see if your PR passes all tests.

GitHub Actions runs the release coverage as parallel platform gates instead of invoking the
single-host `releaseGate`: JVM, host-native, Wasm/publication metadata, Android, and iOS execute on
appropriate runners. The platform jobs stage disjoint Maven repository shards; a final Linux job
merges them, verifies the complete publication matrix, and compiles the standalone and Android
consumers against only the merged repository. Maven Central releases call the same platform gates
for an immutable tag and add only the final signing and upload step.

### IntelliJ default limits

Some of the SIMD tests are exceeding the default limits of IntelliJ.
To overcome this issue go to "Help menu" -> "Edit Custom Properties" and add the following line:

```
idea.max.intellisense.filesize=5000
```

### Wildcard imports

In this project, we disallow wildcard imports, when using IntelliJ we suggest to apply [this configuration](https://www.jetbrains.com/help/idea/creating-and-optimizing-imports.html#disable-wildcard-imports).

### Tests and documentation are not optional

Don't forget to include tests in your pull requests.
Also don't forget the documentation, including reference docs and API docs where relevant.

To automatically apply and approve a new version of the "Golden samples" used by the Approval tests you can use the environment variable:
```
APPROVAL_TESTS_USE_REPORTER=AutoApproveReporter
```

## Reporting an issue

This project uses GitHub issues to manage the issues. Open an issue directly in GitHub.

If you believe you found a bug, and it's likely possible, please indicate a way to reproduce it, what you are seeing and what you would expect to see.

## Legal

All original contributions to Kotlin Runtime Web Assembly projects are licensed under the
[MIT License](https://opensource.org/licenses/MIT), or, if another license is specified as governing
the file or directory being modified, such other license.

## The small print

This project is an open source project. Please act responsibly, be nice, polite and enjoy!
