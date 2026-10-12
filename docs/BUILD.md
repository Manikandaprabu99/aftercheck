# Build guide

## Prerequisites

- Android Studio with Android SDK 35
- JDK 17
- Gradle 8.9 or a generated Gradle wrapper

## Build locally

Open the repository's `android/` folder in Android Studio and allow Gradle sync. Alternatively, with Gradle 8.9 installed:

```bash
cd android
gradle testDebugUnitTest
gradle assembleDebug
```

The debug APK is produced at `android/app/build/outputs/apk/debug/app-debug.apk`.

A Gradle wrapper is not checked into the initial scaffold. Generate and commit it from this folder with:

```bash
gradle wrapper --gradle-version 8.9
```

## GitHub Actions

Pushes to `main`, pull requests, and manual runs execute unit tests and build a debug APK. Successful builds upload `aftercheck-debug-apk` as a workflow artifact. A tag beginning with `v` publishes that debug APK and SHA-256 checksum as a GitHub Release.

The first release artifact is a debug build, **not a production-signed APK**. Configure secure release signing before publishing a production release. Never commit a keystore/password.
