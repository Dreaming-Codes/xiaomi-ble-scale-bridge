# S200 → Health Connect bridge: plan

## Verdict
The proposed design holds up: a PendingIntent BLE scan, then a receiver, then a Health Connect write, with no foreground service. It needs the corrections and hardening below.

## Architecture
1. **One-time setup (Activity):** paste the bind key and scale MAC, grant BLUETOOTH_SCAN and HC WRITE_WEIGHT, then prompt the user to set battery usage to **Unrestricted** (see risks).
2. **Scan:** `BluetoothLeScanner.startScan(filters, settings, pendingIntent)`.
   - Filter: `setServiceData(ParcelUuid(0000fe95-0000-1000-8000-00805f9b34fb), byteArrayOf(0x48,0x59), byteArrayOf(0xFF,0xFF))` plus `setDeviceAddress(MAC)`.
   - Settings: SCAN_MODE_LOW_POWER, CALLBACK_TYPE_ALL_MATCHES, reportDelay 0.
   - PendingIntent: explicit, FLAG_MUTABLE (required, because the stack fills in the results extras), FLAG_UPDATE_CURRENT (never FLAG_CANCEL_CURRENT, or stopScan breaks).
3. **ScanReceiver** (manifest, exported=false): reads `EXTRA_LIST_SCAN_RESULT`, does the cheap dedupe on frame counter and timestamp in SharedPreferences, then enqueues **expedited** OneTimeWork (`setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`) as unique work that keeps the existing request.
4. **Worker:** `S200Parser.parse` (module `:protocol`, already done and tested) → filter by profileId → `WeightRecord(time = scale ts, Mass.kilograms)` with `Metadata` clientRecordId = "s200-<ts>-<profile>". clientRecordId makes the write an idempotent upsert, so duplicates are harmless.
5. **Re-arm:** receiver for BOOT_COMPLETED, LOCKED_BOOT_COMPLETED is not needed, and MY_PACKAGE_REPLACED. Also a **daily PeriodicWork watchdog** that calls startScan again. A duplicate startScan with the same PendingIntent is effectively replaced, but stop first to be safe.

## Corrections and additions to the proposal
- **Bluetooth toggle kills the scan.** The PendingIntent scan does not come back after BT off→on, and `BluetoothAdapter.ACTION_STATE_CHANGED` cannot be received by a manifest receiver on API 26+ (it is not on the implicit-broadcast exemption list). Fix: the daily watchdog re-arms, and opening the app re-arms too. Worst case after a BT toggle is up to about 24 h of missed weigh-ins. Sources: SO 57283343; Nordic Scanner-Compat issue #70.
- **HC background write:** there is no extra permission. Only `READ_HEALTH_DATA_IN_BACKGROUND` exists, and it is for reads. WRITE_WEIGHT is enough from a Worker. Source: d.android.com/health-and-fitness/health-connect/write-data and read-data.
  Caveat to test on the Pixel: HC's rate limits are stricter for background callers, but irrelevant at about 1 write a day.
- **Scale MAC:** the parser uses `ScanResult.device.address`. This only works if the scale uses a public or static address, which is typical for MiBeacon. With BLUETOOTH_SCAN neverForLocation, Android does **not** strip the address, but it does strip *beacon* data for some known beacon formats (iBeacon/Eddystone). MiBeacon 0xFE95 is not on that list. Verify once the scale arrives.
- **Sleepy device:** the scale advertises only around a weigh-in, for a few seconds. LOW_POWER is a 512 ms window every 5 s, so it may miss a short burst. Test it, and fall back to SCAN_MODE_BALANCED. With hardware offload filtering the cost is small.
- **Hardware offload:** service-data filtering with a mask is part of the APCF vendor filter set used by AOSP (`ScanFilterQueue`/`TransitionalScanHelper`). On Tensor this is **unverified**. Check with `adb shell dumpsys bluetooth_manager` (look for "isHardwareTrackingFiltersAvailable" / onfound-onlost and filter count) after registering.
- **Standby buckets:** a never-opened app drifts to rare/restricted, which limits expedited jobs. A PendingIntent broadcast itself is still delivered, and a receiver may do about 10 s of work via `goAsync()`. **Fallback:** do decrypt and HC insert directly in `goAsync()`, which is typically under 1 s, and use WorkManager only as a retry. Setting battery to "Unrestricted" removes the restricted-bucket risk and is the recommended one-time step.
- **5 starts/30 s:** only relevant to the watchdog and boot path. Debounce them.
- **Force stop** cancels the PendingIntent scan and all work. The app must be opened manually afterward. (Confirmed as in the proposal.)
- **Permissions:** BLUETOOTH_SCAN `usesPermissionFlags="neverForLocation"`; ACCESS_FINE_LOCATION maxSdkVersion=30, which is harmless and unused on the Pixel; `android.permission.health.WRITE_WEIGHT`; RECEIVE_BOOT_COMPLETED. HC needs a privacy-policy activity alias (`ACTION_SHOW_PERMISSIONS_RATIONALE` / `VIEW_PERMISSION_USAGE`), or the permission dialog won't show.
- **Mi Home interference:** if the Mi Home app is connected to the scale, it may suppress broadcasts (seen on the S400). After extracting the key, uninstall Mi Home or disable its Bluetooth.

## Android 14–16 / SDK 37 notes
- Pin targetSdk to the latest stable SDK in nixpkgs. Not verified: specific SDK 37 changes affecting PendingIntent BLE scans; nothing found so far. Re-check the behavior-changes pages when building `:app`.
- Since API 34, a mutable PendingIntent must be explicit, which ours is.

## Risks / open items
- **Bind key:** needs Lorenzo's Xiaomi login with Xiaomi-cloud-tokens-extractor after pairing. It changes if the scale is re-paired.
- **Multi-user scale:** filter by profileId (configurable).
- Validate on the device: offload, burst capture with LOW_POWER, and delivery in Doze. The user typically picks up the phone in the morning anyway, so maintenance windows are likely.

## CompanionDeviceManager presence (optional second trigger, implemented)
Verdict: **viable as a supplement, not reliable as the only trigger.** The PendingIntent scan stays primary.

- **Works for a broadcast-only beacon.** BLE presence is a system_server scan on the associated device's **address only**; there is no connection or GATT.
  - AOSP `services/companion/.../devicepresence/BleDeviceProcessor.java`: MAC ScanFilters, `CALLBACK_TYPE_FIRST_MATCH | MATCH_LOST`, `SCAN_MODE_LOW_POWER`.
  - CDM docs: "For Bluetooth LE devices, this is based on scanning for device with the given address."
  - Needs a stable scale MAC.
  - The scale must be advertising during association, so step on it first.
- **Big caveat, latency.** The AOSP comment says LOW_POWER "usually takes from 20 seconds to 2 minutes" to find a device. The S200 advertises for only a few seconds per weigh-in (a sleepy device in xiaomi-ble).
  - Presence may miss short weigh-ins.
  - When it does fire, `ScaleCompanionService` runs a LOW_LATENCY burst scan, capped at 60 s, to catch the encrypted adv.
- **FIRST_MATCH/MATCH_LOST depend on controller onfound/onlost tracking.** If the Pixel's controller lacks it, the system scan fails silently. Check with `dumpsys companiondevice`.
- **BT toggle:** survives. `BleDeviceProcessor.checkBleState()` restarts the scan when LE is re-enabled.
- **Standby:** the App Standby docs list "Companion device apps" as exempt from the restricted bucket and the inactivity trigger.
  - `REQUEST_COMPANION_RUN_IN_BACKGROUND` puts an associated app on the power-save allowlist. This also protects the PendingIntent/WorkManager path.
  - The bound CompanionDeviceService raises process priority.
  - Delivery under deep Doze is unverified.
- **Permissions:**
  - `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` and `REQUEST_COMPANION_RUN_IN_BACKGROUND` (both normal).
  - The service is guarded by `BIND_COMPANION_DEVICE_SERVICE`.
  - `companion_device_setup` feature, required=false.
  - No location permission is needed for association.
- **API changes:**
  - API 33: `onDeviceAppeared(AssociationInfo)` replaces the String variant.
  - API 36: `startObservingDevicePresence(String)` and `onDeviceAppeared/Disappeared` are deprecated. Use `ObservingDevicePresenceRequest.Builder().setAssociationId(id)` with `onDevicePresenceEvent(DevicePresenceEvent)` (EVENT_BLE_APPEARED / DISAPPEARED).
  - The code uses the API 36 path at runtime, with the legacy path for minSdk 34-35.
- **Re-observe** runs on app open, BOOT_COMPLETED, MY_PACKAGE_REPLACED, and in the daily watchdog.

## Status / build
- `:protocol`: pure JVM, AES-CCM on javax AES/ECB. Tests 3/3 pass: the xiaomi-ble vector gives 62.25 kg, profile 1, ts 1762728842; a wrong key and tampered data are rejected.
- `:app`: `assembleDebug` OK. Untested on device. compileSdk/targetSdk 36, minSdk 34.
- **Build:** set `sdk.dir` in `local.properties` (or `ANDROID_HOME`), then `gradle :protocol:test :app:assembleDebug`. JDK 17+ and Gradle 8.13+ are needed.
  - NixOS: `nix-build sdk.nix -o .android-sdk` gives platform 36 and build-tools 36.0.0. Then set `android.aapt2FromMavenOverride` (see gradle.properties).
