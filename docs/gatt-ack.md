# S200 GATT login and offline-data acknowledgement

Goal: connect to the S200 (`xiaomi.scales.ms113`), log in the way Xiaomi Home does, and acknowledge
stored readings so the scale's Bluetooth icon stops blinking. The passive MiBeacon path stays as it is.

Status: the **ack sequence** (which actions, which ids, value types, payload formats, ordering) is now
**confirmed from the Xiaomi Home plugin** for this model (section 0). The transport, login and spec
wire framing are taken from Xiaomi SDK source and real captures of other Xiaomi devices, because in
Xiaomi Home they live in the native app, not in the plugin. What still needs an HCI capture is listed
in section 8.

Labels used throughout:
- **Confirmed**: seen in Xiaomi SDK source or in decrypted real captures, and reproduced by the tests in `tools/`.
- **Inferred**: the same shape as a confirmed sibling, but never seen on the wire for this op or device.
- **Unknown**: needs the capture or the Xiaomi Home plugin.

## 0. What Xiaomi Home does (ms113 plugin v11, confirmed)

Source: plugin `xiaomi.scales.ms113` v11 (plugin_id 1021775, package 1817412, sdk 10082, built
2025-09-25; internally `projects_yunmaiscalesms106`, i.e. it is the yunmai S200 plugin re-used for ms113).
`main.bundle` is a plain-JS **indexed RAM bundle** (magic `0xFB0BD1E5`, 13676 slots, 483 modules), not
Hermes; `tools/unpack_ram_bundle.py` splits it. Offsets below are byte offsets of the module in `main.bundle`.

| module @ offset | role |
| --- | --- |
| 10568 @ 2271196 | `BleManager`: `Device.getBluetoothLE().connect(authType, {did, timeout})`, waits for chars `001A`/`001B` on `FE95`, serialises spec calls through `Bluetooth.spec.getPropertiesValue / setPropertiesValue / doAction(mac, JSON)` |
| 10571 @ 2300106 | `ble_config`: `authType: 4` (**Standard Auth**), connect timeout 16 s, reconnect 9 s, max 2 reconnects |
| 10574 @ 2300529 | `Specs` generator: every siid/aiid/piid and value type (table below) |
| 10577 @ 2308852 | `SpecQueue`: one spec op in flight at a time |
| 10586 @ 2315069 | `SpecHandler.parseEventData` / `parseSyncInfoResponse`: event and response formats |
| 10532 @ 2162541 | home page: connect flow `bleConnectListener` → `ble_subscribeProps` → `ble_syncinfo` → `ble_requestOffline` → `ble_receivedOfflineData` |
| 10691 @ 2463583 | `handleOfflineData`, `uploadMatchData`, `uploadUncliaimData`, `replayDeviceOfflineIdxs` |

Everything below the JSON level is **native** Xiaomi Home code (`miot` SDK, not in the bundle): the
login (`connect(4, …)`), rxfer framing, encryption, the spec TLV encoding of `doAction`, and
`subscribeMessages`. The plugin never touches a characteristic directly.

### Sequence on opening the scale page
1. `connect(authType 4)`; when `001A` and `001B` are discovered the state becomes `discovered`.
2. `subscribeMessages("event.5.3", "event.6.1", "event.7.1")` (realtime weight, offline upload, battery).
3. **Action 7.1 sync** with `[p1 string = JSON]`, JSON built by `Specs.syncDeviceInfo`:
   ```json
   {"mid": <Xiaomi account id>, "duid": <device user id of the account owner>, "uc": <user count>,
    "ow": 1, "unit": <0 kg | 1 jin | 2 lb>, "time": <unix seconds>,
    "ud": [{"duid": n, "ut": type, "age": years, "sex": n, "hi": cm, "wt": target kg×100}, …]}
   ```
   The user list comes from Xiaomi's cloud (`getIndexinfo().accountInfoList`). Response out-args
   (`parseSyncInfoResponse`): p2 offline-msg-cnt, p3 sn, p4 mac, p5 firmware-version, p6 battery-status.
   On failure it is resent once.
4. Only **if offline-msg-cnt > 0**: **action 6.1 request-offline-data**, no args. Response: p3 data-num.
5. The scale then sends **event 6.1** (`event.6.1` = siid 6, eiid 1) one or more times, p1 string:
   records separated by `_`, fields separated by `,`:
   `idx,miid,duid,weight×100,status,unix_time` (status 0 ok, 1 only-weight, 2 unmatched, 3 userinfo
   error, 4 multiple matches, 7 overweight). The plugin accumulates until it has data-num records.
6. The records are uploaded to Xiaomi's cloud; **after a successful upload** it sends **action 6.2
   comfirm-offline-data** `[p2 string = idx list joined with ","]`, e.g. `"12,13"`. It confirms
   matched and unclaimed records in separate calls.
7. Other actions it uses: 4.1 update-user, 4.2 del-user, 4.3 update-base-weight, 4.4 switch-user
   (all `p1 miid Int64, p2 duid Uint8, …`), 5.1 wakeup, 8.1 del-share-users `[p1 JSON array]`,
   properties 1.4/1.5 (firmware), 2.1 unit, 5.1 work-mode.

Nothing in the plugin mentions the Bluetooth icon/LED. Whatever clears it is firmware behaviour
triggered by one of: a successful login, sync (7.1), or emptying the offline store (6.2).

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

## 3. Login (Mi BLE "v2" token login; confirmed on S400/HOTO, consistent with the plugin)

The plugin connects with `authType 4` (Standard Auth). For an already-bound Standard Auth device the
native layer logs in with the cloud token using exactly this flow; the S400 (same yunmai family) is
driven this way by `xiaomi-s400-live`. Not yet seen on the wire for the S200 itself.

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
| ACTION 05 | `siid:u8 aiid:u16 nargs:u8` then args as EVENT | **inferred** (wire layout is native code; the ids, arg order and types are confirmed by the plugin) |
| ACTION_RSP 06 | `siid:u8 aiid:u16 code:i16 nargs:u8` then out args | **inferred**; the decoder and the app also accept it without `code` |

Error codes come from `mi_spec_type.h`: 0 OK, −4001 cannot read, −4002 cannot write, −4003 invalid,
−4004 internal, −4005 value, −4006 method, −4007 did.

Example (layout inferred, ids confirmed): request-offline-data, tid 2, as plaintext:
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
p5 firmware-version, p6 battery-status]`; the ms113 plugin reads exactly those piids from the sync
response, so ms113 returns them too (confirmed by the plugin's parser, section 0).
Value types the plugin uses: 6.p2 idx-string String, 6.p3 data-num read as a number, 7.p1 String,
4.p1 miid Int64, 4.p2 duid Uint8, 4.p8 weight Uint16 (kg×100).

## 7. Sequence implemented in the app (v0.4)

```
connect (TRANSPORT_LE, no bonding), MTU 247, notify on 0x10/0x19/0x1a/0x1b
echo any MNG_CMD on a channel back as MNG_ACK
login (section 3)                                   -> UPNP 21 00 00 00
ACTION 6.1 request-offline-data []                  -> ACTION_RSP [6.p3 data-num = N]
  collect EVENT 6.e1 [6.p1 string] until N records  (8 s cap)
write the records to Health Connect (dedup against readings already written from MiBeacon)
ACTION 6.2 comfirm-offline-data [6.p2 = "idx,idx"]  -> ACTION_RSP, only for records that are now
                                                       in Health Connect and match the profile filter
disconnect
```
Implementation: `protocol/…/AckSession.kt` (pure Kotlin, unit-tested against a simulated scale and the
HOTO vectors), `app/…/ScaleAck.kt` (GATT link + expedited WorkManager job, 45 s hard cap). It runs once
after each new MiBeacon weigh-in written by `Bridge.handle`, only when a BLE token is configured. It
never changes the scan path: the broadcast reading is already stored before the connection starts.

Deliberately **not** sent:
- **7.1 sync.** It needs the Xiaomi account id and the cloud user table (`duid`, age, sex, height,
  target weight per user). Sending a wrong or empty `ud` list could rewrite the scale's user table and
  break profile matching, so the app skips it and calls 6.1 directly. Xiaomi Home only calls 6.1 when
  sync reports pending records; whether the scale accepts 6.1 without a prior sync is unconfirmed.
- 4.x user management, 5.1 wakeup, 8.1 share cleanup: unrelated to the ack.

If 6.1 alone does not stop the blinking, the next step is a sync with the real user table
or a capture showing which call clears it. The user table comes from Xiaomi's cloud and is not fetched yet.

## 8. Open unknowns

1. **Which channel**: 0x1a/0x1b with rxfer (what the plugin waits for, so very likely) or `mi.miot.ble`.
2. **Wire layout of ACTION / ACTION_RSP** (`code` field, `nargs` width). The app accepts both variants.
3. **SEG vs SGL**: the app sends everything segmented (like the S400 client) and accepts both from the scale.
4. **Whether 6.1 works without 7.1 first** (section 7).
5. **What clears the icon**: login alone, sync, or confirming offline data. It may also start
   blinking again after disconnect until the next connection.
6. **Side effects**: confirming removes the records from the scale, so Xiaomi Home will not see them
   later (they are in Health Connect instead).

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
2. The login lines through LOGIN_OK (confirms section 3 for the S200).
3. Every `ENC` line in order, with the `pt=` hex, for both sessions. That settles unknowns 2–4.
4. The clock times when the icon changed, matched to capture timestamps, which settles unknown 5.

The app's own session can be captured the same way (snoop log on, weigh in with Xiaomi Home closed)
and decoded with the same command; `S200 Bridge → Scale ack` shows its result on the status card.

## Sources
- Xiaomi mijia BLE SDK: `mible_rxfer.c`, `gatt_spec.h`, `mi_spec_type.h` (in Telink/JieLi SDK trees on GitHub).
- `nokistin/xiaomi-s400-live`: login, the 0x1b channel and S400 event plaintexts.
- `pedroct/hoto-xiaomi-balanca`: a real Xiaomi Home capture, validated keys and frames, `mi.miot.ble` channel.
- `kairui1108/cuktech-ble-ha`, `zuyan9/ha-cuk-ble`: spec TLV from Xiaomi Home's `SpecWriteChannelManager`, real SET/GET captures.
- Xiaomi Home plugin `xiaomi.scales.ms113` v11 (`tools/fetch_plugin.py`, `tools/unpack_ram_bundle.py`), modules listed in section 0.
- miot-spec.org instances for `xiaomi.scales.ms113`, `yunmai.scales.ms106`, `yunmai.scales.ms104`.
