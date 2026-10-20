# Get the bind key, BLE token, and MAC by hand

The app decrypts the scale's adverts with a bind key that only the Xiaomi cloud knows. **Sign in with Xiaomi** in the app gets the bind key, the BLE token, and the MAC for you, as the [README](../README.md#set-up-the-app) describes. Use this guide when the in-app sign-in fails, or when you don't want the phone to contact Xiaomi. It gets the values on a computer with [Xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor), and you type them into S200 Bridge. You do this once.

You need a computer with Python 3.9 or later.

Before you start, add the scale to Xiaomi Home on your account. Don't remove the scale from your account or pair it again afterward. Either one makes Xiaomi issue new keys, and you have to repeat this guide.

## Install the extractor

1. Clone the extractor:

	```sh
	git clone https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor
	cd Xiaomi-cloud-tokens-extractor
	```

2. On NixOS, open a shell with Python from nixpkgs, and run the next steps inside it:

	```sh
	nix shell nixpkgs#python3
	```

3. Create a virtual environment and install the dependencies into it. Many distributions block a system-wide `pip install` (PEP 668), so use the virtual environment:

	```sh
	python3 -m venv .venv
	.venv/bin/pip install -r requirements.txt
	```

If the packages from pip fail to load on NixOS, skip the virtual environment. Use the nixpkgs packages instead, and run `python3` in place of `.venv/bin/python` in the next section:

```sh
nix-shell -p python3Packages.requests python3Packages.pycryptodome python3Packages.charset-normalizer python3Packages.pillow python3Packages.colorama
```

## Log in and read the scale's values

1. Find the server for your account region. The extractor supports `cn`, `de`, `us`, `ru`, `tw`, `sg`, `in`, and `i2`. A Netherlands account uses `de`. If you don't know your server, leave out `--server`, and press Enter at the server prompt to check all of them.
2. Run the extractor with your server:

	```sh
	.venv/bin/python token_extractor.py --server de
	```

3. At the `p/q:` prompt, type `q` to log in with a QR code.
4. Open `http://127.0.0.1:31415` in a browser on the same computer. The page shows the QR code.
5. Scan the QR code with the Xiaomi Home app on your phone, and confirm the login. The QR code expires after a few minutes. If it expires, stop the extractor with Ctrl+C and run it again for a new code.

	If the QR code doesn't work, open the URL that the extractor prints under `Alternatively you can visit the following URL`, and scan the code on that page.

6. Find the entry with `MODEL:    xiaomi.scales.ms113`. It looks like this, with your own values:

	```text
	   NAME:     Xiaomi Smart Scale S200
	   ID:       blt.4.xxxxxxxxxxxxx
	   BLE KEY:  xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
	   MAC:      AA:BB:CC:DD:EE:FF
	   TOKEN:    xxxxxxxxxxxxxxxxxxxxxxxx
	   MODEL:    xiaomi.scales.ms113
	```

7. Copy `BLE KEY`, `MAC`, and `TOKEN`. Then press Enter to close the extractor.

To keep the full result as JSON, add `-o devices.json` to the command in step 2. The file contains your keys, so don't commit it or share it.

## Enter the values in the app

1. Open S200 Bridge.
2. In the **Scale setup** card, under **Or enter the values by hand**, fill in the fields:
	- **Bind key**: the `BLE KEY` value.
	- **Scale MAC**: the `MAC` value.
	- **BLE token (optional)**: the `TOKEN` value. Without it, the app still records weigh-ins, but the scale's Bluetooth icon keeps blinking.
	- **Profile slot**: leave `-1` to accept every user on the scale. To record only one user, enter that user's profile number. The **Recent readings** card shows the profile number of each weigh-in.
3. Tap **Save & arm**. The app shows **Saved**. If a value has the wrong format, the app marks the field as an error and keeps **Save & arm** disabled.
4. In the **Permissions** card, tap **Grant** or **Open** on each row, and allow the request:
	- **Bluetooth scan**
	- **Bluetooth connect**
	- **Notifications**
	- **Health Connect**
	- **Battery unrestricted**
	- **Unused-app exemption**
5. Step on the scale. The status card shows the new weight, and a notification shows the weigh-in. If you entered the BLE token, the chip reads **Scale synced** with the time, and the scale's Bluetooth icon stops blinking.
