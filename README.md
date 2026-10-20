# S200 Bridge

S200 Bridge copies weigh-ins from a Xiaomi Smart Scale S200 (`xiaomi.scales.ms113`) into Health Connect, where apps such as MacroFactor read them. The scale broadcasts each reading as an encrypted Bluetooth advert. Android hands the advert to the app in the background, and the app decrypts it with the scale's bind key and writes a weight record.

The app runs no foreground service, and you don't have to open it after setup. It has no internet permission, so it can't send data to Xiaomi or anywhere else.

## Requirements

You need the following:

- An Android 14 or later phone (API 34) with Health Connect.
- A Xiaomi Smart Scale S200 that is already added to Xiaomi Home on your Xiaomi account.
- A computer with Python 3.9 or later, to get the scale's keys from the Xiaomi cloud once.
- JDK 17, Gradle 8.13 or later, and the Android SDK 36, to build the APK.

## Build and install the app

1. Point Gradle at the Android SDK. Set `sdk.dir` in `local.properties`, or set `ANDROID_HOME`.
2. Build the APK:

	```sh
	gradle :app:assembleRelease
	```

3. Install the APK on the phone:

	```sh
	adb install app/build/outputs/apk/release/app-release.apk
	```

Gradle signs the release build only when it finds a signing key. It reads `keystore.properties` in the repo root, or the file that `S200_KEYSTORE_PROPERTIES` names. The file sets `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Instead of the file, you can set the environment variables `S200_STORE_FILE`, `S200_STORE_PASSWORD`, `S200_KEY_ALIAS`, and `S200_KEY_PASSWORD`. Without a key, Gradle writes `app-release-unsigned.apk`, which Android refuses to install. For a quick test, build `:app:assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.

Android refuses an update that is signed with a different key. To switch between the debug build and the release build, uninstall the old one first, then enter the setup values again.

To run the tests and lint, run `gradle check`.

### Build on NixOS

`sdk.nix` builds an Android SDK with platform 36 and build-tools 36.0.0:

```sh
nix-build sdk.nix -o .android-sdk
```

Set `sdk.dir` to the `libexec/android-sdk` directory inside `.android-sdk`. The aapt2 binary that Gradle downloads from Maven doesn't run on NixOS. Add this line to `gradle.properties` to use the SDK's own aapt2:

```properties
android.aapt2FromMavenOverride=<sdk>/build-tools/36.0.0/aapt2
```

## Set up the app

The app needs three values from your Xiaomi account: the bind key, the BLE token, and the scale's MAC address. To get them and enter them in the app, follow [Get the bind key, BLE token, and MAC](docs/extract-keys.md). [Scale setup fields](docs/setup-fields.md) lists the format of each value.

## Learn how it works

- [PLAN.md](PLAN.md) covers the background scan, the Health Connect write, and the Android limits that shaped them.
- [docs/gatt-ack.md](docs/gatt-ack.md) covers the Bluetooth login that stops the scale's Bluetooth icon from blinking.
