# Running Kotlin Runtime Web Assembly tests on Android

This is an Android project designed to run Kotlin Runtime Web Assembly tests on device.

This sample consumes artifacts from the local Maven repository produced by the
main Gradle build.

Inside the `device-tests` folder, there is an Android library project setup.

To run the tests on Android, first publish the KRWA artifacts locally:

```bash
./gradlew --no-daemon publishToMavenLocal -x test
```

Then run the Android project:

```bash
./samples/android-tests/gradlew -p samples/android-tests connectedCheck
# you can also abbreviate connectedCheck to `cC`
./samples/android-tests/gradlew -p samples/android-tests cC
```

## Environment Setup

You'll need Android build tools and a running emulator (or connected device with developer
mode) to build and run these tests.

The easiest way to obtain a working local setup is to use
[Android Studio](https://developer.android.com/studio).

* Download Android Studio from [this link](https://developer.android.com/studio).
* Start Android Studio. It will guide you through the SDK installation.
* Make sure to run `./gradlew --no-daemon publishToMavenLocal -x test` once to allow Android Studio to find dependencies.
* Next, open the project in Android Studio (`<checkout-root>/samples/android-tests`). This will also
  automatically add a `local.properties` file, specifying your Android SDK location.
* Finally, go to `View > Tool Windows > Device Manager` create and run an emulator. You can select
  any device-version configuration as long as it is at least API 33. See
  [documentation](https://developer.android.com/studio/run/managing-avds) for more details.

## Adding a New Test Project

When adding a new project to be tested, follow these steps:
* Update `device-tests/build.gradle.kts`
  * Create a new product flavor in the `productFlavors` section.
  * Add its dependencies in the `dependencies` section.
