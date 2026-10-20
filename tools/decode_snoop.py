#!/usr/bin/env python3
"""Decode a Xiaomi Home <-> S200 session from an Android btsnoop_hci.log.

Recovers the Mi BLE v2 login from the capture, derives the session keys
from the 12-byte cloud token, decrypts the post-login channel and prints
the MIoT spec operations. See docs/gatt-ack.md for the protocol.

    python3 tools/decode_snoop.py btsnoop_hci.log --mac AA:BB:CC:DD:EE:FF \
        --token-file /path/to/out.json            # or S200_TOKEN=<24 hex>

Keys and token are never printed unless --show-keys is given.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import struct
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

from cryptography.hazmat.primitives import hashes, hmac
from cryptography.hazmat.primitives.ciphers.aead import AESCCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

UUID_NAMES = {
    0x0004: "version", 0x0010: "upnp", 0x0017: "avctp", 0x0018: "auth-v2",
    0x0019: "avdtp", 0x001A: "ctrl-tx", 0x001B: "ctrl-rx", 0x001C: "info",
}
MIOT_BLE_WRITE = "00000101-0065-6c62-2e74-6f696d2e696d"
MIOT_BLE_NOTIFY = "00000102-0065-6c62-2e74-6f696d2e696d"

UPNP_NAMES = {
    0xA2: "GET_INFO", 0xA4: "XFER_NEGOTIATE", 0x15: "SET_KEY", 0x24: "LOGIN",
    0x13: "AUTH", 0x11: "REGISTER_OK", 0x12: "REGISTER_ERR", 0x21: "LOGIN_OK",
    0x23: "LOGIN_ERR", 0xE0: "AUTH_ERR0", 0xE1: "AUTH_ERR1", 0xE2: "AUTH_ERR2",
    0xE3: "AUTH_ERR3",
}

XFER_TYPES = {
    0x00: "PASS_THROUGH", 0x01: "DEV_CERT", 0x02: "DEV_MANU_CERT", 0x03: "ECC_PUBKEY",
    0x04: "DEV_SIGNATURE", 0x05: "DEV_LOGIN_INFO", 0x06: "DEV_SHARE_INFO",
    0x07: "SERVER_CERT", 0x08: "SERVER_SIGN", 0x09: "MESH_CONFIG",
    0x0A: "APP_CONFIRMATION", 0x0B: "APP_RANDOM", 0x0C: "DEV_CONFIRMATION",
    0x0D: "DEV_RANDOM", 0x0E: "BIND_KEY",
}
SEG_CMD, SEG_ACK, SGL_CMD, SGL_ACK, MNG_CMD, MNG_ACK = range(6)
ACK_NAMES = {0: "SUCCESS", 1: "READY", 2: "BUSY", 3: "TIMEOUT", 4: "CANCEL", 5: "LOST"}

SPEC_OPS = {
    0x00: "SET", 0x01: "SET_RSP", 0x02: "GET", 0x03: "GET_RSP",
    0x04: "PROP_CHANGED", 0x05: "ACTION", 0x06: "ACTION_RSP", 0x07: "EVENT",
    0xF0: "PROTOCOL_EXCHANGE",
}

# xiaomi.scales.ms113, from miot-spec.org (urn ...:xiaomi-ms113:1)
MS113 = {
    (1, "p", 1): "manufacturer", (1, "p", 2): "model", (1, "p", 3): "serial-number",
    (1, "p", 4): "firmware-revision", (1, "p", 5): "serial-no",
    (2, "p", 1): "unit", (2, "p", 2): "current-time",
    (3, "e", 1022): "customized-event-1",
    (4, "a", 1): "update-user", (4, "a", 2): "del-user", (4, "a", 3): "update-weight",
    (4, "a", 4): "switch-current-user",
    (4, "p", 1): "miid", (4, "p", 2): "user-id", (4, "p", 3): "is-owner",
    (4, "p", 4): "user-type", (4, "p", 5): "user-age", (4, "p", 6): "user-sex",
    (4, "p", 7): "user-height", (4, "p", 8): "user-weight",
    (5, "p", 1): "work-mode", (5, "p", 2): "work-status", (5, "p", 3): "work-data",
    (5, "p", 4): "weight-result", (5, "e", 1): "baby-data-report",
    (5, "e", 2): "balance-data-report", (5, "e", 3): "raw-weight-report",
    (5, "e", 4): "weight-result-report",
    (6, "p", 1): "offline-data", (6, "p", 2): "idx-string", (6, "p", 3): "data-num",
    (6, "a", 1): "request-offline-data", (6, "a", 2): "comfirm-offline-data",
    (6, "e", 1): "report-offline-data",
    (7, "p", 1): "json-string", (7, "p", 2): "offline-msg-cnt", (7, "p", 3): "sn",
    (7, "p", 4): "mac", (7, "p", 5): "firmware-version", (7, "p", 6): "battery-status",
    (7, "a", 1): "sync", (7, "e", 1): "batt-status-report",
    (8, "p", 1): "share-user-list", (8, "a", 1): "del-share-users",
    (9, "p", 1): "battery-level", (9, "p", 1004): "status",
    (9, "e", 1001): "low-battery", (9, "e", 1035): "battery-status-change",
}


# ---------------------------------------------------------------- crypto

@dataclass(frozen=True)
class SessionKeys:
    dev_key: bytes
    app_key: bytes
    dev_iv: bytes
    app_iv: bytes


def derive_keys(token: bytes, app_rand: bytes, dev_rand: bytes) -> SessionKeys:
    okm = HKDF(hashes.SHA256(), 64, app_rand + dev_rand, b"mible-login-info").derive(token)
    return SessionKeys(okm[0:16], okm[16:32], okm[32:36], okm[36:40])


def hmac_sha256(key: bytes, data: bytes) -> bytes:
    h = hmac.HMAC(key, hashes.SHA256())
    h.update(data)
    return h.finalize()


def ccm_nonce(iv: bytes, counter: int) -> bytes:
    return iv + bytes(4) + counter.to_bytes(4, "little")


def decrypt(key: bytes, iv: bytes, counter: int, ct_tag: bytes) -> bytes | None:
    try:
        return AESCCM(key, tag_length=4).decrypt(ccm_nonce(iv, counter), ct_tag, None)
    except Exception:
        return None


def encrypt(key: bytes, iv: bytes, counter: int, plaintext: bytes) -> bytes:
    ct = AESCCM(key, tag_length=4).encrypt(ccm_nonce(iv, counter), plaintext, None)
    return (counter & 0xFFFF).to_bytes(2, "little") + ct


# ---------------------------------------------------------------- btsnoop / HCI / ATT

@dataclass(frozen=True)
class Att:
    ts_us: int
    conn: int
    sent: bool          # host -> controller, i.e. phone -> scale
    opcode: int
    handle: int
    value: bytes


ATT_VALUE_OPS = {0x12: "write-req", 0x52: "write-cmd", 0x1B: "notify", 0x1D: "indicate", 0x16: "prep-write"}
BTSNOOP_EPOCH_DELTA_US = 0x00DCDDB30F2F8000  # 0000-01-01 -> 1970-01-01


def iter_btsnoop(path: Path) -> Iterator[tuple[int, bool, int, bytes, bool]]:
    """Yield (ts_us_unix, sent, h4_type, payload, truncated)."""
    with path.open("rb") as f:
        hdr = f.read(16)
        if hdr[:8] != b"btsnoop\x00":
            raise SystemExit(f"{path}: not a btsnoop file")
        _, datalink = struct.unpack(">II", hdr[8:])
        while True:
            rec = f.read(24)
            if len(rec) < 24:
                return
            orig, incl, flags, _, ts = struct.unpack(">IIIIq", rec)
            cut = incl < orig
            data = f.read(incl)
            if len(data) < incl or not data:
                return
            sent = not (flags & 1)
            ts_unix = ts - BTSNOOP_EPOCH_DELTA_US
            if datalink == 1002:
                yield ts_unix, sent, data[0], data[1:], cut
            else:
                kind = 0x01 if flags & 2 and sent else 0x04 if flags & 2 else 0x02
                yield ts_unix, sent, kind, data, cut


@dataclass
class Capture:
    att: list[Att] = field(default_factory=list)
    peers: dict[int, str] = field(default_factory=dict)
    uuids: dict[tuple[int, int], str] = field(default_factory=dict)
    truncated: int = 0


def mac_str(raw_le: bytes) -> str:
    return ":".join(f"{b:02X}" for b in reversed(raw_le))


def uuid_str(raw: bytes) -> str:
    if len(raw) == 2:
        return f"{int.from_bytes(raw, 'little'):04x}"
    b = raw[::-1].hex()
    return f"{b[0:8]}-{b[8:12]}-{b[12:16]}-{b[16:20]}-{b[20:32]}"


def parse_capture(path: Path) -> Capture:
    cap = Capture()
    partial: dict[tuple[int, bool], tuple[int, bytearray]] = {}
    for ts, sent, kind, p, cut in iter_btsnoop(path):
        if cut and kind == 0x02:
            cap.truncated += 1
        if kind == 0x04 and len(p) >= 2 and p[0] == 0x3E:
            sub = p[2]
            if sub in (0x01, 0x0A) and p[3] == 0:
                handle = struct.unpack_from("<H", p, 4)[0] & 0x0FFF
                cap.peers[handle] = mac_str(p[8:14])
            continue
        if kind != 0x02 or len(p) < 4:
            continue
        hf, length = struct.unpack_from("<HH", p)
        conn, pb = hf & 0x0FFF, (hf >> 12) & 0x3
        frag = p[4:4 + length]
        key = (conn, sent)
        if pb in (0b00, 0b10):
            if len(frag) < 4:
                continue
            l2len = struct.unpack_from("<H", frag)[0]
            partial[key] = (l2len, bytearray(frag))
        elif key in partial:
            partial[key][1].extend(frag)
        else:
            continue
        l2len, buf = partial[key]
        if len(buf) < l2len + 4:
            continue
        del partial[key]
        cid = struct.unpack_from("<H", buf, 2)[0]
        pdu = bytes(buf[4:4 + l2len])
        if cid != 0x0004 or not pdu:
            continue
        op = pdu[0]
        if op == 0x09 and not sent and len(pdu) >= 2:
            each = pdu[1]
            for i in range(2, len(pdu) - each + 1, each):
                item = pdu[i:i + each]
                if each in (7, 21):
                    vh = struct.unpack_from("<H", item, 3)[0]
                    cap.uuids[(conn, vh)] = uuid_str(item[5:])
        elif op in ATT_VALUE_OPS and len(pdu) >= 3:
            handle = struct.unpack_from("<H", pdu, 1)[0]
            value = pdu[5:] if op == 0x16 else pdu[3:]
            cap.att.append(Att(ts, conn, sent, op, handle, value))
    return cap


# ---------------------------------------------------------------- rxfer framing

@dataclass
class Msg:
    ts_us: int
    conn: int
    handle: int
    sent: bool
    xtype: int | None   # rxfer data type, None for raw channel values
    data: bytes


class Reassembler:
    """Mi rxfer: sn==0 control frames, sn>=1 data segments (see mible_rxfer.c)."""

    def __init__(self) -> None:
        self.seg: dict[tuple[int, int, bool], tuple[int, int, dict[int, bytes]]] = {}

    def feed(self, a: Att) -> tuple[str, Msg | None]:
        v = a.value
        key = (a.conn, a.handle, a.sent)
        if len(v) < 2:
            return f"short {v.hex()}", None
        sn = struct.unpack_from("<H", v)[0]
        if sn == 0 and len(v) >= 4:
            mode, typ, arg = v[2], v[3], v[4:]
            if mode == SEG_CMD and len(arg) >= 2:
                count = struct.unpack_from("<H", arg)[0]
                self.seg[key] = (typ, count, {})
                return f"SEG_CMD {XFER_TYPES.get(typ, hex(typ))} frames={count}", None
            if mode == SGL_CMD:
                return f"SGL_CMD {XFER_TYPES.get(typ, hex(typ))} len={len(arg)}", Msg(a.ts_us, a.conn, a.handle, a.sent, typ, arg)
            if mode in (SEG_ACK, SGL_ACK):
                extra = f" sns={[struct.unpack_from('<H', arg, i)[0] for i in range(0, len(arg) - 1, 2)]}" if typ == 5 else ""
                return f"{'SEG' if mode == SEG_ACK else 'SGL'}_ACK {ACK_NAMES.get(typ, hex(typ))}{extra}", None
            if mode in (MNG_CMD, MNG_ACK):
                what = {0: "FEATURE", 1: "LENGTH"}.get(typ, hex(typ))
                detail = f" simul_retran={arg[0]} dmtu={arg[1]}" if typ == 0 and len(arg) >= 2 else f" len={len(arg)}"
                return f"{'MNG_CMD' if mode == MNG_CMD else 'MNG_ACK'} {what}{detail}", None
            return f"ctrl? {v.hex()}", None
        if key not in self.seg:
            return f"data sn={sn} with no transfer open", None
        typ, count, parts = self.seg[key]
        parts[sn] = v[2:]
        if len(parts) < count:
            return f"  seg {sn}/{count}", None
        del self.seg[key]
        data = b"".join(parts[i] for i in sorted(parts))
        return f"  seg {sn}/{count} -> {XFER_TYPES.get(typ, hex(typ))} {len(data)} B", Msg(a.ts_us, a.conn, a.handle, a.sent, typ, data)


# ---------------------------------------------------------------- MIoT spec over BLE

VALUE_TYPES = ["bool", "u8", "i8", "u16", "i16", "u32", "i32", "u64", "i64", "float", "string"]


def spec_name(siid: int, kind: str, iid: int, names: dict = MS113) -> str:
    n = names.get((siid, kind, iid))
    return f"{siid}.{kind}{iid}" + (f"({n})" if n else "")


def fmt_value(vt: int, raw: bytes) -> str:
    t = vt >> 12
    if t == 10:
        return json.dumps(raw.decode("utf-8", "replace"))
    if t == 9 and len(raw) == 4:
        return repr(struct.unpack("<f", raw)[0])
    if t <= 8 and raw:
        signed = t in (2, 4, 6, 8)
        return str(int.from_bytes(raw, "little", signed=signed))
    return raw.hex()


class Cursor:
    def __init__(self, b: bytes, i: int = 0) -> None:
        self.b, self.i = b, i

    def take(self, n: int) -> bytes:
        if self.i + n > len(self.b):
            raise ValueError("truncated")
        out = self.b[self.i:self.i + n]
        self.i += n
        return out

    def u8(self) -> int:
        return self.take(1)[0]

    def u16(self) -> int:
        return int.from_bytes(self.take(2), "little")

    def i16(self) -> int:
        return int.from_bytes(self.take(2), "little", signed=True)

    def typed(self) -> str:
        vt = self.u16()
        t = VALUE_TYPES[vt >> 12] if (vt >> 12) < len(VALUE_TYPES) else hex(vt >> 12)
        return f"{t}:{fmt_value(vt, self.take(vt & 0x0FFF))}"

    def done(self) -> bool:
        return self.i == len(self.b)


def _args(c: Cursor, siid: int, names: dict) -> list[str]:
    return [f"{spec_name(siid, 'p', c.u16(), names)}={c.typed()}" for _ in range(c.u8())]


def decode_spec(pt: bytes, names: dict = MS113) -> str:
    if len(pt) < 5:
        return f"? {pt.hex()}"
    hdr, tid, op = struct.unpack_from("<HHB", pt)
    ver, length = hdr >> 12, hdr & 0x0FFF
    head = f"v{ver} tid={tid} {SPEC_OPS.get(op, hex(op))}"
    if length != len(pt):
        head += f" (len field {length} != {len(pt)})"
    c = Cursor(pt, 5)
    try:
        if op in (0x00, 0x04):
            items = []
            for _ in range(c.u8()):
                s = c.u8()
                items.append(f"{spec_name(s, 'p', c.u16(), names)}={c.typed()}")
            body = ", ".join(items)
        elif op == 0x01:
            items = []
            for _ in range(c.u8()):
                s = c.u8()
                items.append(f"{spec_name(s, 'p', c.u16(), names)} code={c.i16()}")
            body = ", ".join(items)
        elif op == 0x02:
            items = []
            for _ in range(c.u8()):
                s = c.u8()
                items.append(spec_name(s, "p", c.u16(), names))
            body = ", ".join(items)
        elif op == 0x03:
            items = []
            for _ in range(c.u8()):
                s, p, code = c.u8(), c.u16(), c.i16()
                items.append(f"{spec_name(s, 'p', p, names)} code={code}" + (f" {c.typed()}" if code == 0 else ""))
            body = ", ".join(items)
        elif op in (0x05, 0x07):
            s = c.u8()
            iid = c.u16()
            body = f"{spec_name(s, 'a' if op == 0x05 else 'e', iid, names)} [{', '.join(_args(c, s, names))}]"
        elif op == 0x06:
            s, aiid = c.u8(), c.u16()
            mark = c.i
            try:
                code = c.i16()
                args = _args(c, s, names)
                if not c.done():
                    raise ValueError
            except ValueError:
                c.i, code = mark, None
                args = _args(c, s, names)
            body = f"{spec_name(s, 'a', aiid, names)} code={code} [{', '.join(args)}]"
        else:
            return f"{head} raw={pt[5:].hex()}"
        if not c.done():
            body += f" trailing={pt[c.i:].hex()}"
        return f"{head} {body}"
    except (ValueError, IndexError):
        return f"{head} undecoded={pt[5:].hex()}"


OFFLINE_FIELDS = ("idx", "miid", "duid", "weight", "status", "time")


def offline_records(s: str) -> list[dict]:
    out = []
    for rec in s.split("_"):
        f = rec.split(",")
        if len(f) < 6 or not all(x.strip().lstrip("-").isdigit() for x in f[:6]):
            continue
        r = dict(zip(OFFLINE_FIELDS, (int(x) for x in f[:6])))
        r["weight"] = r["weight"] / 100
        out.append(r)
    return out


def describe_offline(pt: bytes) -> str | None:
    if len(pt) < 9 or pt[4] != 0x07 or pt[5] != 6 or struct.unpack_from("<H", pt, 6)[0] != 1:
        return None
    m = re.search(rb'[0-9,_]{11,}', pt[9:])
    if not m:
        return None
    recs = offline_records(m.group().decode())
    return "; ".join(f"idx={r['idx']} duid={r['duid']} {r['weight']:.2f} kg status={r['status']} t={r['time']}" for r in recs) or None


# ---------------------------------------------------------------- session tracking

@dataclass
class Login:
    app_rand: bytes | None = None
    dev_rand: bytes | None = None
    dev_confirm: bytes | None = None
    keys: SessionKeys | None = None
    counters: dict[bool, int] = field(default_factory=lambda: {True: -1, False: -1})


def full_counter(last: int, low16: int) -> int:
    if last < 0:
        return low16
    base = last & ~0xFFFF
    c = base | low16
    if c < last - 0x8000:
        c += 0x10000
    return c


def try_decrypt(login: Login, sent: bool, blob: bytes) -> tuple[int, bytes] | None:
    if login.keys is None or len(blob) < 6:
        return None
    k = login.keys
    key, iv = (k.app_key, k.app_iv) if sent else (k.dev_key, k.dev_iv)
    ctr = full_counter(login.counters[sent], int.from_bytes(blob[:2], "little"))
    pt = decrypt(key, iv, ctr, blob[2:])
    if pt is None:
        return None
    login.counters[sent] = max(login.counters[sent], ctr)
    return ctr, pt


def handle_label(cap: Capture, conn: int, handle: int) -> str:
    u = cap.uuids.get((conn, handle))
    if u is None:
        return f"h{handle:#06x}"
    if len(u) == 4:
        return f"h{handle:#06x}/{UUID_NAMES.get(int(u, 16), u)}"
    return f"h{handle:#06x}/" + {MIOT_BLE_WRITE: "miot.ble-tx", MIOT_BLE_NOTIFY: "miot.ble-rx"}.get(u, u)


def run(cap: Capture, token: bytes | None, mac: str | None, show_keys: bool, out=sys.stdout) -> dict:
    stats = {"logins": 0, "verified": 0, "decrypted": 0, "undecrypted": 0, "spec": []}
    conns = {c for c, m in cap.peers.items() if mac is None or m == mac.upper()}
    if cap.truncated:
        print(f"! {cap.truncated} ACL records were truncated: set the HCI snoop log to 'Enabled', not 'Filtered'", file=out)
    if mac and not conns:
        print(f"! no LE connection to {mac} in capture; decoding every connection", file=out)
    rx = Reassembler()
    login = Login()
    t0 = cap.att[0].ts_us if cap.att else 0
    for a in cap.att:
        if conns and a.conn not in conns:
            continue
        arrow = "->" if a.sent else "<-"
        label = handle_label(cap, a.conn, a.handle)
        stamp = f"{(a.ts_us - t0) / 1e6:9.3f}s c{a.conn:#05x} {arrow} {label:<22}"

        if len(a.value) == 4 and a.value[1:] == b"\x00\x00\x00" and a.value[0] in UPNP_NAMES:
            print(f"{stamp} UPNP {UPNP_NAMES[a.value[0]]}", file=out)
            if a.value[0] == 0x21 and login.keys is None and login.app_rand:
                print(f"{'':>34}! login OK but keys not derived (no token or HMAC mismatch)", file=out)
            continue
        if len(a.value) == 1 and a.value[0] in UPNP_NAMES:
            print(f"{stamp} UPNP {UPNP_NAMES[a.value[0]]}", file=out)
            continue

        raw = try_decrypt(login, a.sent, a.value)
        if raw is not None:
            ctr, pt = raw
            stats["decrypted"] += 1
            line = decode_spec(pt)
            stats["spec"].append((a.sent, pt))
            print(f"{stamp} ENC ctr={ctr} {line}", file=out)
            continue

        note, msg = rx.feed(a)
        print(f"{stamp} {note}", file=out)
        if msg is None:
            continue
        if msg.xtype == 0x0B and msg.sent:
            login = Login(app_rand=msg.data[:16])
            stats["logins"] += 1
        elif msg.xtype == 0x0D and not msg.sent:
            login.dev_rand = msg.data[:16]
            if len(msg.data) >= 48:
                login.dev_confirm = msg.data[16:48]
        elif msg.xtype == 0x0C and not msg.sent:
            login.dev_confirm = msg.data[:32]
        elif msg.xtype == 0x0A and msg.sent and login.keys is not None:
            want = hmac_sha256(login.keys.app_key, login.app_rand + login.dev_rand)
            print(f"{'':>34}app confirm {'matches' if msg.data[:32] == want else 'DIFFERS from'} HMAC(app_key)", file=out)
        elif msg.xtype == 0x00:
            got = try_decrypt(login, msg.sent, msg.data)
            if got is None:
                stats["undecrypted"] += 1
                print(f"{'':>34}PASS_THROUGH not decryptable ({len(msg.data)} B)", file=out)
            else:
                ctr, pt = got
                stats["decrypted"] += 1
                stats["spec"].append((msg.sent, pt))
                print(f"{'':>34}ENC ctr={ctr} {decode_spec(pt)}", file=out)
                if (recs := describe_offline(pt)):
                    print(f"{'':>34}    offline: {recs}", file=out)
                print(f"{'':>34}    pt={pt.hex()}", file=out)

        if token and login.app_rand and login.dev_rand and login.dev_confirm and login.keys is None:
            keys = derive_keys(token, login.app_rand, login.dev_rand)
            if hmac_sha256(keys.dev_key, login.dev_rand + login.app_rand) == login.dev_confirm:
                login.keys = keys
                stats["verified"] += 1
                print(f"{'':>34}login verified: token matches device confirm, session keys derived", file=out)
                if show_keys:
                    print(f"{'':>34}dev_key={keys.dev_key.hex()} dev_iv={keys.dev_iv.hex()} "
                          f"app_key={keys.app_key.hex()} app_iv={keys.app_iv.hex()}", file=out)
            else:
                print(f"{'':>34}! device confirm does not match HMAC from this token (wrong/rotated token?)", file=out)
    return stats


def load_token(path: str | None, mac: str | None) -> bytes | None:
    env = os.environ.get("S200_TOKEN")
    if path is None and env:
        return bytes.fromhex(env.strip())
    if path is None:
        return None
    text = Path(path).expanduser().read_text().strip()
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return bytes.fromhex(text)

    def walk(x):
        if isinstance(x, dict):
            if "token" in x and "mac" in x:
                yield x
            for v in x.values():
                yield from walk(v)
        elif isinstance(x, list):
            for v in x:
                yield from walk(v)

    devices = [d for d in walk(data) if len(str(d["token"])) == 24]
    if mac:
        devices = [d for d in devices if str(d["mac"]).upper() == mac.upper()] or devices
    if not devices:
        raise SystemExit(f"{path}: no 12-byte device token found")
    return bytes.fromhex(devices[0]["token"])


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("btsnoop", type=Path)
    ap.add_argument("--mac", help="scale MAC; restricts decoding to its connections")
    ap.add_argument("--token-file", help="24-hex token file or token-extractor JSON (else $S200_TOKEN)")
    ap.add_argument("--show-keys", action="store_true", help="print derived session keys")
    ap.add_argument("--dump-handles", action="store_true", help="print discovered handle -> UUID map")
    args = ap.parse_args()
    cap = parse_capture(args.btsnoop)
    if args.dump_handles:
        for (conn, h), u in sorted(cap.uuids.items()):
            print(f"c{conn:#05x} h{h:#06x} {u}")
    token = load_token(args.token_file, args.mac)
    if token is not None and len(token) != 12:
        raise SystemExit("token must be 12 bytes")
    stats = run(cap, token, args.mac, args.show_keys)
    print(f"\nlogins={stats['logins']} verified={stats['verified']} "
          f"decrypted={stats['decrypted']} undecrypted={stats['undecrypted']}")
    if token is None:
        print("no token given: login and channel left encrypted")


if __name__ == "__main__":
    main()
