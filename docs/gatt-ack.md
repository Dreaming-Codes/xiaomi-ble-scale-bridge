# S200 GATT login and offline-data acknowledgement

Goal: connect to the S200 (`xiaomi.scales.ms113`), log in the way Xiaomi Home does, and acknowledge
stored readings so the scale's Bluetooth icon stops blinking. The passive MiBeacon path stays as it is.

Status: the transport, login and spec framing below are taken from source code or real captures of
other Xiaomi devices. The ack sequence for this scale is **inferred** from its MIoT spec. The capture
(see the end of this doc) is meant to confirm or correct it.

Labels used throughout:
- **Confirmed**: seen in Xiaomi SDK source or in decrypted real captures, and reproduced by the tests in `tools/`.
- **Inferred**: the same shape as a confirmed sibling, but never seen on the wire for this op or device.
- **Unknown**: needs the capture or the Xiaomi Home plugin.

## 1. GATT layout

Service `0xFE95`. 16-bit characteristics, base UUID `0000xxxx-0000-1000-8000-00805f9b34fb`:

| UUID | Name in other projects | Role |
| --- | --- | --- |
| `0x0004` | version | read, firmware string |
| `0x0010` | UPNP | write-no-resp + notify, 4-byte login opcodes |
| `0x0019` | AVDTP | write-no-resp + notify, login data (rxfer framed) |
| `0x001a` | VEND1A / cmd | encrypted app → scale (rxfer framed) |
| `0x001b` | CMTP / data | encrypted scale → app (rxfer framed) |
| `0x001c` | info | notify |

Confirmed on the S400 (`nokistin/xiaomi-s400-live`) and the Cuktech AD1204U (`kairui1108/cuktech-ble-ha`).
Some devices put the encrypted channel on a separate service, `mi.miot.ble`, instead:
`00000101-0065-6c62-2e74-6f696d2e696d` for write and `...0102...` for notify, with **no rxfer framing**,
just `[ctr16][ct][tag]` per ATT value (confirmed on the HOTO scale).
**Unknown for the S200**: which of the two it uses. The decoder handles both.

## 2. rxfer framing on 0x19 / 0x1a / 0x1b (confirmed)

Source: `mible_rxfer.c` from Xiaomi's mijia BLE SDK (copy in Telink mesh SDK trees). Every ATT value is
`sn:u16le || payload`.

- `sn != 0`: data segment number `sn` (1-based) of the open transfer, `dmtu` bytes each. The default
  `dmtu` is 18; MNG FEATURE can negotiate more.
- `sn == 0`: control frame `00 00 mode type arg...`

| mode | name | arg |
| --- | --- | --- |
| 0 | SEG_CMD | `count:u16le`. The sender then waits for READY and sends segments 1..count. |
| 1 | SEG_ACK | type 0 SUCCESS, 1 READY, 2 BUSY, 3 TIMEOUT, 4 CANCEL, 5 LOST (+ list of lost `sn:u16le`) |
| 2 | SGL_CMD | the whole payload inline (used when it fits in one frame) |
| 3 | SGL_ACK | type as above |
| 4 / 5 | MNG_CMD / MNG_ACK | type 0 FEATURE `simul_retran, dmtu`; type 1 LENGTH probe |

Data types (`type` of SEG_CMD/SGL_CMD): `00 PASS_THROUGH` (all encrypted traffic), `0A APP_CONFIRMATION`,
`0B APP_RANDOM`, `0C DEV_CONFIRMATION`, `0D DEV_RANDOM` (full list in `decode_snoop.py`).

Typical exchanges:
```
-> 00 00 00 0b 01 00        SEG_CMD APP_RANDOM, 1 segment
<- 00 00 01 01              SEG_ACK READY
-> 01 00 <16 B>             segment 1
<- 00 00 01 00              SEG_ACK SUCCESS

<- 00 00 02 0d <16 B>       SGL_CMD DEV_RANDOM inline
-> 00 00 03 00              SGL_ACK SUCCESS
```
The receiver of a segmented transfer sends READY and SUCCESS on the **same characteristic** it received on.
Whether a device uses SEG or SGL for a short message varies (the S400 uses SEG, HOTO and Cuktech use SGL),
so an implementation must accept both.

## 3. Login (Mi BLE "v2" token login; confirmed)

Inputs: the 12-byte cloud `token` from the device list (`out.json`), never the 16-byte beaconkey.

1. Connect, request MTU (Xiaomi Home asks for 247), enable notifications on 0x10, 0x19, 0x1a, 0x1b (and 0x1c).
2. Optional transport negotiation (HOTO capture; S400 skips it): write `a4` to UPNP. The scale sends
   `00 00 04 00 <simul> <dmtu>` on AVDTP; echo it back with mode 5 (`00 00 05 00 ...`). A LENGTH probe
   `00 00 04 01 f2...` may follow; echo it the same way.
3. UPNP ← `24 00 00 00` (LOGIN).
4. AVDTP: send APP_RANDOM (16 random bytes) as in section 2.
5. AVDTP: receive DEV_RANDOM (16 B) and DEV_CONFIRMATION (32 B) and ack each.
6. Derive:
   ```
   okm     = HKDF-SHA256(ikm=token, salt=app_rand||dev_rand, info="mible-login-info", L=64)
   dev_key = okm[0:16]   app_key = okm[16:32]   dev_iv = okm[32:36]   app_iv = okm[36:40]
   ```
7. Check `DEV_CONFIRMATION == HMAC-SHA256(dev_key, dev_rand||app_rand)`. A mismatch means a wrong or rotated token.
8. AVDTP: send APP_CONFIRMATION = `HMAC-SHA256(app_key, app_rand||dev_rand)` (32 B, 2 segments at dmtu 18).
9. UPNP → `21 00 00 00` LOGIN_OK (`23 00 00 00` = failure).

Test vector: the real HOTO capture (token, randoms, both HMACs and keys) is checked in
`tools/test_decode_snoop.py::CryptoVectors`.

## 4. Encrypted channel (confirmed)

Every PASS_THROUGH payload (after rxfer reassembly) or `mi.miot.ble` ATT value is:
```
ctr:u16le || AES-128-CCM(ciphertext) || tag[4]          (no AAD)
nonce = iv[4] || 00 00 00 00 || ctr:u32le               (12 bytes)
app -> scale: app_key / app_iv      scale -> app: dev_key / dev_iv
```
Each direction has its own counter, starting at 0 after login and incremented once per message. Only the
low 16 bits travel on the wire, so reconnect before 0xFFFF. Test vectors: HOTO notify and write frames.

## 5. MIoT spec over BLE (header and property ops confirmed; actions inferred)

The plaintext of every encrypted message:
```
hdr:u16le    = (version << 12) | total_length      version = 2, so byte 1 is 0x20; length includes hdr
tid:u16le    request id, echoed in the response
op:u8        00 SET  01 SET_RSP  02 GET  03 GET_RSP  04 PROP_CHANGED
             05 ACTION  06 ACTION_RSP  07 EVENT  F0 PROTOCOL_EXCHANGE   (gatt_spec.h)
body
```
Typed value: `vt:u16le = (type << 12) | byte_len`, then `byte_len` bytes, little-endian. Types:
0 bool, 1 u8, 2 i8, 3 u16, 4 i16, 5 u32, 6 i32, 7 u64, 8 i64, 9 float, 10 string. For example, a 20-byte
string has `vt = 14 a0`.

| op | body | status |
| --- | --- | --- |
| SET 00 | `n:u8` then n × `siid:u8 piid:u16 vt value` | confirmed (Cuktech capture) |
| SET_RSP 01 | `n` × `siid piid code:i16` | confirmed |
| GET 02 | `n` × `siid piid` | confirmed |
| GET_RSP 03 | `n` × `siid piid code:i16 vt value` | confirmed |
| PROP_CHANGED 04 | as SET | confirmed |
| EVENT 07 | `siid:u8 eiid:u16 nargs:u8` then nargs × `piid:u16 vt value` | confirmed (S400 live stream: `.. 20 tid 07 08 01 00 01 01 00 len a0 "<csv>"` = siid 8 online-data, eiid 1, piid 1) |
| ACTION 05 | `siid:u8 aiid:u16 nargs:u8` then args as EVENT | **inferred** |
| ACTION_RSP 06 | `siid:u8 aiid:u16 code:i16 nargs:u8` then out args | **inferred**; the decoder also accepts it without `code` |

Error codes come from `mi_spec_type.h`: 0 OK, −4001 cannot read, −4002 cannot write, −4003 invalid,
−4004 internal, −4005 value, −4006 method, −4007 did.

Example (inferred): request-offline-data, tid 2, as plaintext:
```
09 20  02 00  05  06  01 00  00
len 9  tid 2  ACTION siid 6 aiid 1 nargs 0
```

## 6. ms113 spec: what matters for the ack

From `miot-spec.org` (`urn:miot-spec-v2:device:scale:0000A07D:xiaomi-ms113:1`):

| siid | service | relevant items |
| --- | --- | --- |
| 2 | scale | p1 unit (0 kg, 1 half-kg/jin, 2 lb), p2 current-time u32 |
| 3 | customized-service-for-ble | e1022 = the 0x4E16 MiBeacon object we already decode |
| 4 | user-management | a1 update-user [p1 miid, p2 user-id, p3 is-owner, p4 user-type, p5 age, p6 sex, p7 height, p8 weight] |
| 6 | offline-data | **a1 request-offline-data → out [p3 data-num u8]**; **e1 report-offline-data [p1 offline-data string]**; **a2 comfirm-offline-data in [p2 idx-string string]** |
| 7 | sync-info | **a1 sync in [p1 json-string]** ("string containing unit, time and user-list sync structure"); p2 offline-msg-cnt u8, p3 sn, p4 mac, p5 firmware-version, p6 battery-status; e1 batt-status-report [p6] |
| 9 | battery | p1 battery-level, e1001 low-battery, e1035 battery-status-change |

The sibling S200 `yunmai.scales.ms106` declares `sync` with out args `[p2 offline-msg-cnt, p3 sn, p4 mac,
p5 firmware-version, p6 battery-status]`. ms113 lists none, but the scale may still return them.

## 7. Proposed ack sequence (inferred, to be confirmed by the capture)

```
connect, MTU 247, notify on 0x10/0x19/0x1a/0x1b(/0x1c)
[a4 + MNG echo]                                    if the scale starts it
login (section 3)                                  -> LOGIN_OK
ACTION 7.1 sync  [7.1 = string JSON]               -> ACTION_RSP code 0 (maybe offline-msg-cnt etc.)
ACTION 6.1 request-offline-data []                 -> ACTION_RSP [6.3 data-num = N]
  N x EVENT 6.e1 report-offline-data [6.1 = string record]
ACTION 6.2 comfirm-offline-data [6.2 = string idx list of the records received]
                                                   -> ACTION_RSP code 0
disconnect
```
Why this order: it's the order of the spec services, and both offline-data designs (ms113's
request/confirm and ms104's request/end-of-reception) are pull-then-confirm. Xiaomi Home reportedly
uploads stored readings when the scale's page opens, which fits `request-offline-data` being sent right
after `sync`.

## 8. Open unknowns

1. **Which channel**: 0x1a/0x1b with rxfer, or `mi.miot.ble`.
2. **Login variant**: whether Xiaomi Home uses this token login (UPNP `24`) for the S200, rather than
   standard-auth v2 on 0x17/0x18. If it's the latter, the decoder will show the rxfer frames but nothing will decrypt.
3. **Action framing**: is there a `code` field in ACTION_RSP, and how is `nargs` encoded.
4. **sync JSON schema**: key names, time unit (s vs ms), timezone, user objects. Is `sync` required
   before the offline actions?
5. **offline-data record format** (probably CSV like the S400's: profile, weight, timestamp, ...) and the
   **idx-string format** (one idx, a comma list, or a JSON array).
6. **What clears the icon**: login alone, `sync` (time set), or confirming offline data. It may also start
   blinking again after disconnect until the next connection.
7. **Side effects**: whether a connected central stops the MiBeacon broadcast of the next weigh-in, and
   whether confirming offline data on the scale breaks Xiaomi Home's own sync later (it would see no
   records; harmless for us).
8. **Fallback without capture**: `tools/fetch_plugin.py` downloads the ms113 Xiaomi Home plugin
   (a React Native bundle). It needs one more QR login. The bundle would answer 4 and 5 directly.

## 9. Capture and decoding plan

### Capture (Pixel)
1. Developer options → **Enable Bluetooth HCI snoop log → Enabled** (not "Filtered": filtered mode
   truncates ACL payloads, and the decoder warns about it). Toggle Bluetooth off and on so the log restarts.
2. Leave Xiaomi Home closed. Weigh in 2–3 times so the scale holds unsynced readings and the icon blinks.
   Note the weights and times.
3. Open Xiaomi Home, then the scale's page. Wait until the readings appear and the icon stops blinking.
   Note the clock time when the icon changed. Keep the page open about 30 s, then close Xiaomi Home.
4. Second, shorter session: step on once more, open the scale page again (one record, quick sync).
   Then once with nothing pending, if possible.
5. `adb bugreport s200.zip`, then take `FS/data/misc/bluetooth/logs/btsnoop_hci.log` (one or more
   files) from the zip.
6. Don't re-pair the scale and don't touch "reset" in Xiaomi Home, or the token rotates.

### Decode
```
python3 tools/decode_snoop.py btsnoop_hci.log --mac AA:BB:CC:DD:EE:FF \
    --token-file /path/to/out.json --dump-handles      # or S200_TOKEN=<24 hex>
```
What it does:
- Parses btsnoop (H4/1002), maps LE connection handles to peer MAC from LE (Enhanced) Connection
  Complete, and reassembles L2CAP over ACL fragments.
- Collects ATT write-req/cmd, notify and indicate values, plus the handle → UUID map from Read By Type
  responses (characteristic discovery), so each line names the characteristic.
- Runs the rxfer state machine per (connection, handle, direction) and prints every control frame.
- Pulls APP_RANDOM, DEV_RANDOM and DEV_CONFIRMATION out of the login, derives the keys from the token,
  verifies the device HMAC (it reports a wrong or rotated token), and checks the app HMAC.
- Decrypts every PASS_THROUGH payload and every raw `mi.miot.ble` value with the per-direction key and
  nonce (tracking 16-bit counter wrap), and decodes the spec op with ms113 names. It prints the plaintext
  hex too.
- Never prints the token or keys unless `--show-keys` is given.

Tests: `python3 -m unittest tools/test_decode_snoop.py`. They cover the real HOTO capture vectors (keys,
both HMACs and channel frames, pushed through a synthesized btsnoop including L2CAP fragmentation and an
MNG negotiation), real Cuktech SET/SET_RSP/PROP_CHANGED plaintexts, the S400 event shape, a full
synthetic S200 session (login, sync, request, event, confirm) over 0x19/0x1a/0x1b with SEG and SGL
transfers, wrong-token detection, MAC filtering and token loading from the extractor JSON.

### What I need from the decoded output
1. The `--dump-handles` table, which settles unknown 1.
2. The login lines through LOGIN_OK, which settle unknown 2.
3. Every `ENC` line in order, with the `pt=` hex, for both sessions. That settles unknowns 3–5 and gives
   exact bytes to replay.
4. The clock times when the icon changed, matched to capture timestamps, which settles unknown 6.

With those, the app side is: a `ScaleSync` worker (CompanionDeviceService wake or after a MiBeacon
reading). It connects, logs in, replays the confirmed sequence, writes any offline records to Health
Connect (dedup via clientRecordId as today), confirms them and disconnects.

## Sources
- Xiaomi mijia BLE SDK: `mible_rxfer.c`, `gatt_spec.h`, `mi_spec_type.h` (in Telink/JieLi SDK trees on GitHub).
- `nokistin/xiaomi-s400-live`: login, the 0x1b channel and S400 event plaintexts.
- `pedroct/hoto-xiaomi-balanca`: a real Xiaomi Home capture, validated keys and frames, `mi.miot.ble` channel.
- `kairui1108/cuktech-ble-ha`, `zuyan9/ha-cuk-ble`: spec TLV from Xiaomi Home's `SpecWriteChannelManager`, real SET/GET captures.
- miot-spec.org instances for `xiaomi.scales.ms113`, `yunmai.scales.ms106`, `yunmai.scales.ms104`.
