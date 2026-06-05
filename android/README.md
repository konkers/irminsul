# Irminsul Android

## Build Requirements

| Tool | Version |
|------|---------|
| JDK | 17 |
| Android SDK | compileSdk 35 |
| Android NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| Rust | stable |
| cargo-ndk | latest |

## Build

```bash
cd android
./gradlew assembleDebug
```

The build will automatically compile the Rust library via `cargo-ndk` before building the APK.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Release APK: `./gradlew assembleRelease` (requires keystore)
