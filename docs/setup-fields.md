# Scale setup fields

The **Scale setup** card in S200 Bridge has four fields. **Sign in with Xiaomi** fills the bind key, the scale MAC, and the BLE token, and leaves the profile slot as it is. The extractor column names the line in the [Xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor) output, and the JSON column names the key in its `-o` output file.

| Field | Format | Extractor line | JSON key | Required | Used for |
| --- | --- | --- | --- | --- | --- |
| Bind key | 32 hex characters | `BLE KEY` | `BLE_DATA.beaconkey` | Yes | Decrypts the MiBeacon V5 adverts with AES-128-CCM |
| Scale MAC | `AA:BB:CC:DD:EE:FF`, case-insensitive | `MAC` | `mac` | Yes | Filters the background scan to one scale |
| BLE token | 24 hex characters | `TOKEN` | `token` | No | Logs in over GATT, which stops the Bluetooth icon from blinking |
| Profile slot | Integer, default `-1` | None | None | No | Selects one scale user. `-1` accepts every user |

**Save & arm** stays disabled until the bind key and the MAC are valid and the BLE token is empty or valid.

Xiaomi issues a new bind key and a new BLE token when the scale is paired again or removed from the account.
