# Xiaomi BLE Scale Bridge

[![CI](https://github.com/Dreaming-Codes/xiaomi-ble-scale-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/Dreaming-Codes/xiaomi-ble-scale-bridge/actions/workflows/ci.yml)

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22dev.lorenzo.s200.bridge%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FDreaming-Codes%2Fxiaomi-ble-scale-bridge%22%2C%22author%22%3A%22Dreaming-Codes%22%2C%22name%22%3A%22Xiaomi%20BLE%20Scale%20Bridge%22%7D"><img src="docs/images/badge_obtainium.png" alt="Get it on Obtainium" width="161"></a>

Xiaomi BLE Scale Bridge copies weigh-ins from a Xiaomi Smart Scale S200 into Health Connect, where apps such as MacroFactor read them. The scale broadcasts each reading as an encrypted Bluetooth advert. Android hands the advert to the app in the background, and the app decrypts it with the scale's bind key and writes a weight record.

The app runs no foreground service, and you don't have to open it after setup. It uses the internet only while you sign in with Xiaomi during setup. Weigh-ins never leave the phone except through Health Connect.

## Requirements

You need the following:

- An Android 14 or later phone (API 34) with Health Connect.
- A [supported scale](#supported-scales) that is already added to Xiaomi Home on your Xiaomi account.
- JDK 17 and the Android SDK 36, to build the APK yourself. The Gradle wrapper downloads Gradle 8.14.3.

## Install the app

Each [GitHub release](https://github.com/Dreaming-Codes/xiaomi-ble-scale-bridge/releases) has a signed `xiaomi-ble-scale-bridge-<version>.apk` and its SHA-256 checksum. To get updates automatically, tap the **Get it on Obtainium** badge on your phone, or add `https://github.com/Dreaming-Codes/xiaomi-ble-scale-bridge` in Obtainium by hand.

## Build the app yourself

1. Point Gradle at the Android SDK. Set `sdk.dir` in `local.properties`, or set `ANDROID_HOME`.
2. Build the APK:

	```sh
	./gradlew :app:assembleRelease
	```

3. Install the APK on the phone:

	```sh
	adb install app/build/outputs/apk/release/app-release.apk
	```

Gradle signs the release build only when it finds a signing key. It reads `keystore.properties` in the repo root, or the file that `S200_KEYSTORE_PROPERTIES` names. The file sets `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Instead of the file, you can set the environment variables `S200_STORE_FILE`, `S200_STORE_PASSWORD`, `S200_KEY_ALIAS`, and `S200_KEY_PASSWORD`. Without a key, Gradle writes `app-release-unsigned.apk`, which Android refuses to install. For a quick test, build `:app:assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.

Android refuses an update that is signed with a different key. To switch between the debug build and the release build, uninstall the old one first, then enter the setup values again.

To run the tests and lint, run `./gradlew check`. To run the tests for the Python tools in `tools/`, install `tools/requirements.txt` and run `python -m unittest discover -s tools`.

### Build on NixOS

`sdk.nix` builds an Android SDK with platform 36 and build-tools 36.0.0:

```sh
nix-build sdk.nix -o .android-sdk
```

Set `sdk.dir` to the `libexec/android-sdk` directory inside `.android-sdk`. The aapt2 binary that Gradle downloads from Maven doesn't run on NixOS. Add this line to `gradle.properties` to use the SDK's own aapt2:

```properties
android.aapt2FromMavenOverride=<sdk>/build-tools/36.0.0/aapt2
```

## Release a new version

1. In `app/build.gradle.kts`, increase `versionCode` by one and set `versionName`, for example to `0.10`.
2. Commit the change and push it to `main`.
3. Tag the commit with `v` and the `versionName`, then push the tag:

	```sh
	git tag v0.10
	git push origin v0.10
	```

The `Release` workflow in `.github/workflows/release.yml` fails if the tag doesn't match `versionName`. Otherwise it builds the APK, signs it with the key in the repository secrets `S200_KEYSTORE_BASE64`, `S200_STORE_PASSWORD`, `S200_KEY_ALIAS`, and `S200_KEY_PASSWORD`, checks the signature with `apksigner`, and publishes the GitHub release. To publish a release for a tag that already exists, run the workflow from the **Actions** tab and enter the tag.

## Set up the app

The app needs three values from your Xiaomi account: the bind key, the BLE token, and the scale's MAC address. The app gets them for you when you sign in with Xiaomi.

1. Open Xiaomi BLE Scale Bridge.
2. In the **Scale setup** card, tap **Sign in with Xiaomi**. A Xiaomi sign-in page opens in a browser tab.
3. Sign in with the account that has the scale in Xiaomi Home. You can use a password, Google, or Facebook. If Xiaomi asks for a verification code, enter it on the same page.
4. Return to the app. If the browser tab stays open after the sign-in, close it. The app reads the scale's values, saves them, and shows **Saved**. If the account has more than one supported scale, the app lists them with their name, model, MAC, and region. Tap the one you want.
5. In the **Permissions** card, tap **Grant** or **Open** on each row, and allow the request.
6. Step on the scale. The status card shows the new weight.

If the sign-in fails, or you don't want the app to contact Xiaomi, follow [Get the bind key, BLE token, and MAC by hand](docs/extract-keys.md). [Scale setup fields](docs/setup-fields.md) lists the format of each value.

### What the sign-in sends to Xiaomi

Xiaomi keeps each account on one regional server, and an account can have devices on more than one. After you sign in, the app asks all eight regions that Xiaomi-cloud-tokens-extractor supports (`cn`, `de`, `us`, `ru`, `tw`, `sg`, `in`, and `i2`) for the account's homes and devices. It merges the supported scales from every region and reads each scale's bind key from the region that lists the scale. You never pick a region. If a region doesn't answer and no other region lists a scale, the app names the regions that failed.

The app makes these requests only while the sign-in runs. It saves the bind key, the BLE token, and the MAC, and it drops the Xiaomi session as soon as it has them. It never stores your password, because you type the password on Xiaomi's own page. It makes no network requests in the background.

## Learn how it works

[docs/gatt-ack.md](docs/gatt-ack.md) covers the Bluetooth login that stops the scale's Bluetooth icon from blinking.
