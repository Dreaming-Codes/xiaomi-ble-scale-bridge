"""Tests for decode_snoop.py: python3 -m unittest tools/test_decode_snoop.py"""

from __future__ import annotations

import io
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import decode_snoop as D  # noqa: E402

# Real capture from pedroct/hoto-xiaomi-balanca (tests/test_capture.py, PROTOCOL.md):
# a Xiaomi Home <-> HOTO kitchen scale session. Published there as a test vector.
HOTO_TOKEN = bytes.fromhex("728188d09c8b2d304cd87bf6")
HOTO_APP_RAND = bytes.fromhex("1b864b0d0e227c243f1d8fe5b105d48f")
HOTO_DEV_RAND = bytes.fromhex("41b8c8027dce475f2f51477628c3cc56")
HOTO_DEV_CONFIRM = bytes.fromhex("ae040d78df2edfb7d051404995639bea1b43ef316340d78c48473c375ec7d183")
HOTO_APP_CONFIRM = bytes.fromhex("21d026952441fdc4292375c37be021ea35bc0eec2b138f4ee3a88f3501c9daae")
HOTO_NOTIFY = [
    (0x0002, "5470549031741d791a982c", "55aa01020002fe"),
    (0x0008, "5e1af132be137bd70e791a3d94df", "55aa01070347000051fe"),
    (0x000C, "9c2fd5991c08c2151cf693c574ac", "55aa0108030000000bfe"),
]
HOTO_WRITE = [(0x0000, "c97aad8216ab371108f06a8c", "55aa0004010004fe")]

MAC = "D0:7B:6F:00:00:01"
CONN = 0x0040
H_UPNP, H_AVDTP, H_TX, H_RX = 0x000D, 0x0010, 0x0019, 0x001C
H_MIOT_TX, H_MIOT_RX = 0x001F, 0x0021


class Snoop:
    """Minimal btsnoop (datalink 1002) writer."""

    def __init__(self) -> None:
        self.recs: list[tuple[bool, bytes]] = []

    def hci_evt(self, payload: bytes) -> None:
        self.recs.append((False, b"\x04" + payload))

    def connect(self, conn: int, mac: str) -> None:
        addr = bytes(int(x, 16) for x in reversed(mac.split(":")))
        body = bytes([0x01, 0x00]) + struct.pack("<H", conn) + bytes([0x00, 0x00]) + addr + bytes(7)
        self.hci_evt(bytes([0x3E, len(body)]) + body)

    def att(self, sent: bool, pdu: bytes, conn: int = CONN, split: int | None = None) -> None:
        l2 = struct.pack("<HH", len(pdu), 4) + pdu
        chunks = [l2] if split is None else [l2[:split], l2[split:]]
        for i, ch in enumerate(chunks):
            pb = 0b10 if i == 0 else 0b01
            self.recs.append((sent, b"\x02" + struct.pack("<HH", conn | (pb << 12), len(ch)) + ch))

    def write(self, handle: int, value: bytes, **kw) -> None:
        self.att(True, b"\x52" + struct.pack("<H", handle) + value, **kw)

    def notify(self, handle: int, value: bytes, **kw) -> None:
        self.att(False, b"\x1b" + struct.pack("<H", handle) + value, **kw)

    def discovery(self, items: list[tuple[int, bytes]]) -> None:
        self.att(True, b"\x08\x01\x00\xff\xff\x03\x28")
        for each in (7, 21):
            group = [(vh, u) for vh, u in items if len(u) + 5 == each]
            if group:
                body = b"".join(struct.pack("<HBH", vh - 1, 0x14, vh) + u for vh, u in group)
                self.att(False, bytes([0x09, each]) + body)

    def save(self, path: Path) -> None:
        with path.open("wb") as f:
            f.write(b"btsnoop\x00" + struct.pack(">II", 1, 1002))
            ts = D.BTSNOOP_EPOCH_DELTA_US + 1_700_000_000_000_000
            for i, (sent, data) in enumerate(self.recs):
                flags = (0 if sent else 1) | (2 if data[0] in (1, 4) else 0)
                f.write(struct.pack(">IIIIq", len(data), len(data), flags, 0, ts + i * 1000) + data)


def seg_send(s: Snoop, sent: bool, handle: int, xtype: int, data: bytes, dmtu: int = 18) -> None:
    put = s.write if sent else s.notify
    ack = s.notify if sent else s.write
    n = (len(data) + dmtu - 1) // dmtu
    put(handle, b"\x00\x00" + bytes([D.SEG_CMD, xtype]) + struct.pack("<H", n))
    ack(handle, b"\x00\x00\x01\x01")
    for i in range(n):
        put(handle, struct.pack("<H", i + 1) + data[i * dmtu:(i + 1) * dmtu])
    ack(handle, b"\x00\x00\x01\x00")


def sgl_send(s: Snoop, sent: bool, handle: int, xtype: int, data: bytes) -> None:
    (s.write if sent else s.notify)(handle, b"\x00\x00" + bytes([D.SGL_CMD, xtype]) + data)
    (s.notify if sent else s.write)(handle, b"\x00\x00\x03\x00")


def tv(t: int, raw: bytes) -> bytes:
    return struct.pack("<H", (t << 12) | len(raw)) + raw


def spec(tid: int, op: int, body: bytes) -> bytes:
    n = 5 + len(body)
    return struct.pack("<HHB", 0x2000 | n, tid, op) + body


def run_capture(s: Snoop, token: bytes | None, mac: str | None = MAC) -> tuple[str, dict]:
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / "btsnoop_hci.log"
        s.save(p)
        out = io.StringIO()
        stats = D.run(D.parse_capture(p), token, mac, show_keys=False, out=out)
        return out.getvalue(), stats


class CryptoVectors(unittest.TestCase):
    def test_hoto_keys_and_confirms(self):
        k = D.derive_keys(HOTO_TOKEN, HOTO_APP_RAND, HOTO_DEV_RAND)
        self.assertEqual(k.dev_key.hex(), "0d871ae201051ed666676cabda91b7aa")
        self.assertEqual(k.app_key.hex(), "f354d2358cb843546cbba496ef9a7e08")
        self.assertEqual(k.dev_iv.hex(), "e978f66d")
        self.assertEqual(k.app_iv.hex(), "a5496fe4")
        self.assertEqual(D.hmac_sha256(k.dev_key, HOTO_DEV_RAND + HOTO_APP_RAND), HOTO_DEV_CONFIRM)
        self.assertEqual(D.hmac_sha256(k.app_key, HOTO_APP_RAND + HOTO_DEV_RAND), HOTO_APP_CONFIRM)

    def test_hoto_channel_roundtrip(self):
        k = D.derive_keys(HOTO_TOKEN, HOTO_APP_RAND, HOTO_DEV_RAND)
        for ctr, wire, pt in HOTO_NOTIFY:
            self.assertEqual(D.decrypt(k.dev_key, k.dev_iv, ctr, bytes.fromhex(wire)).hex(), pt)
        for ctr, wire, pt in HOTO_WRITE:
            self.assertEqual(D.encrypt(k.app_key, k.app_iv, ctr, bytes.fromhex(pt))[2:].hex(), wire)

    def test_counter_rollover(self):
        self.assertEqual(D.full_counter(-1, 5), 5)
        self.assertEqual(D.full_counter(0xFFFE, 0x0001), 0x10001)
        self.assertEqual(D.full_counter(0x10001, 0x0002), 0x10002)


class SpecFraming(unittest.TestCase):
    def test_real_cuktech_set_and_response(self):
        # AD1204U charger frames from a Xiaomi Home capture (zuyan9/ha-cuk-ble tests).
        self.assertEqual(D.decode_spec(bytes.fromhex("0c20210000010206000110 04".replace(" ", "")), {}),
                         "v2 tid=33 SET 2.p6=u8:4")
        self.assertEqual(D.decode_spec(bytes.fromhex("0b2021000101020600 0000".replace(" ", "")), {}),
                         "v2 tid=33 SET_RSP 2.p6 code=0")
        self.assertEqual(D.decode_spec(bytes.fromhex("0f2034120401020100045001 0a32c8".replace(" ", "")), {}),
                         "v2 tid=4660 PROP_CHANGED 2.p1=u32:3358722561")

    def test_s400_event_shape(self):
        # xiaomi-s400-live: "LL 20 CC 00 07 08 PP 00 01 PP 00 SS a0 <csv>", siid 8 = online-data
        csv = b"523,1"
        pt = spec(7, 0x07, bytes([8]) + struct.pack("<HBH", 1, 1, 1) + tv(10, csv))
        self.assertEqual(pt[1], 0x20)
        self.assertEqual(D.decode_spec(pt, {}), 'v2 tid=7 EVENT 8.e1 [8.p1=string:"523,1"]')

    def test_action_with_and_without_code(self):
        rsp = spec(3, 0x06, bytes([6]) + struct.pack("<Hh", 1, 0) + bytes([1]) + struct.pack("<H", 3) + tv(1, b"\x02"))
        self.assertIn("6.a1(request-offline-data) code=0 [6.p3(data-num)=u8:2]", D.decode_spec(rsp))
        bare = spec(3, 0x06, bytes([6]) + struct.pack("<H", 2) + bytes([0]))
        self.assertIn("6.a2(comfirm-offline-data) code=None []", D.decode_spec(bare))


def s200_session(token: bytes, app_rand: bytes, dev_rand: bytes) -> tuple[Snoop, list[bytes]]:
    k = D.derive_keys(token, app_rand, dev_rand)
    s = Snoop()
    s.connect(CONN, MAC)
    s.discovery([(H_UPNP, b"\x10\x00"), (H_AVDTP, b"\x19\x00"), (H_TX, b"\x1a\x00"), (H_RX, b"\x1b\x00")])
    s.write(H_UPNP, b"\x24\x00\x00\x00")
    seg_send(s, True, H_AVDTP, 0x0B, app_rand)
    seg_send(s, False, H_AVDTP, 0x0D, dev_rand)
    seg_send(s, False, H_AVDTP, 0x0C, D.hmac_sha256(k.dev_key, dev_rand + app_rand))
    seg_send(s, True, H_AVDTP, 0x0A, D.hmac_sha256(k.app_key, app_rand + dev_rand))
    s.notify(H_UPNP, b"\x21\x00\x00\x00")
    sync_json = b'{"unit":0,"time":1792500000,"user":[{"id":1,"h":180}]}'
    frames = [
        (True, spec(1, 0x05, bytes([7]) + struct.pack("<HBH", 1, 1, 1) + tv(10, sync_json))),
        (False, spec(1, 0x06, bytes([7]) + struct.pack("<Hh", 1, 0) + bytes([0]))),
        (True, spec(2, 0x05, bytes([6]) + struct.pack("<HB", 1, 0))),
        (False, spec(2, 0x06, bytes([6]) + struct.pack("<Hh", 1, 0) + bytes([1]) + struct.pack("<H", 3) + tv(1, b"\x01"))),
        (False, spec(9, 0x07, bytes([6]) + struct.pack("<HBH", 1, 1, 1) + tv(10, b"1,7235,1792499000"))),
        (True, spec(3, 0x05, bytes([6]) + struct.pack("<HBH", 2, 1, 2) + tv(10, b"1"))),
        (False, spec(3, 0x06, bytes([6]) + struct.pack("<Hh", 2, 0) + bytes([0]))),
    ]
    ctr = {True: 0, False: 0}
    pts = []
    for sent, pt in frames:
        key, iv = (k.app_key, k.app_iv) if sent else (k.dev_key, k.dev_iv)
        blob = D.encrypt(key, iv, ctr[sent], pt)
        ctr[sent] += 1
        (seg_send if len(blob) > 16 else sgl_send)(s, sent, H_TX if sent else H_RX, 0x00, blob)
        pts.append(pt)
    return s, pts


class EndToEnd(unittest.TestCase):
    TOKEN = bytes.fromhex("00112233445566778899aabb")
    APP = bytes(range(16))
    DEV = bytes(range(16, 32))

    def test_s200_style_session_decrypts(self):
        s, pts = s200_session(self.TOKEN, self.APP, self.DEV)
        out, stats = run_capture(s, self.TOKEN)
        self.assertEqual(stats["verified"], 1, out)
        self.assertEqual(stats["decrypted"], len(pts), out)
        self.assertEqual(stats["undecrypted"], 0, out)
        self.assertEqual([pt for _, pt in stats["spec"]], pts)
        self.assertIn("ACTION 7.a1(sync) [7.p1(json-string)=string:", out)
        self.assertIn("EVENT 6.e1(report-offline-data)", out)
        self.assertIn("ACTION 6.a2(comfirm-offline-data) [6.p2(idx-string)=string:\"1\"]", out)
        self.assertIn("h0x001c/ctrl-rx", out)
        self.assertNotIn(self.TOKEN.hex(), out)
        k = D.derive_keys(self.TOKEN, self.APP, self.DEV)
        self.assertNotIn(k.app_key.hex(), out)

    def test_wrong_token_is_reported(self):
        s, _ = s200_session(self.TOKEN, self.APP, self.DEV)
        out, stats = run_capture(s, bytes(12))
        self.assertEqual(stats["verified"], 0)
        self.assertEqual(stats["decrypted"], 0)
        self.assertIn("does not match", out)

    def test_other_device_filtered_out(self):
        s, _ = s200_session(self.TOKEN, self.APP, self.DEV)
        out, stats = run_capture(s, self.TOKEN, mac="AA:BB:CC:DD:EE:FF")
        self.assertIn("no LE connection", out)

    def test_hoto_real_session_through_pipeline(self):
        s = Snoop()
        s.connect(CONN, MAC)
        s.discovery([(H_MIOT_TX, bytes.fromhex(D.MIOT_BLE_WRITE.replace("-", ""))[::-1]),
                     (H_MIOT_RX, bytes.fromhex(D.MIOT_BLE_NOTIFY.replace("-", ""))[::-1])])
        s.write(H_UPNP, b"\xa4")
        s.notify(H_AVDTP, bytes.fromhex("0000040006f2"))
        s.write(H_AVDTP, bytes.fromhex("0000050006f2"))
        s.write(H_UPNP, b"\x24\x00\x00\x00")
        seg_send(s, True, H_AVDTP, 0x0B, HOTO_APP_RAND)
        sgl_send(s, False, H_AVDTP, 0x0D, HOTO_DEV_RAND)
        sgl_send(s, False, H_AVDTP, 0x0C, HOTO_DEV_CONFIRM)
        seg_send(s, True, H_AVDTP, 0x0A, HOTO_APP_CONFIRM)
        s.notify(H_UPNP, b"\x21\x00\x00\x00")
        for ctr, wire, _ in HOTO_WRITE:
            s.write(H_MIOT_TX, struct.pack("<H", ctr) + bytes.fromhex(wire))
        for ctr, wire, _ in HOTO_NOTIFY:
            s.notify(H_MIOT_RX, struct.pack("<H", ctr) + bytes.fromhex(wire), split=6)
        out, stats = run_capture(s, HOTO_TOKEN)
        self.assertEqual(stats["verified"], 1, out)
        self.assertIn("app confirm matches", out)
        self.assertIn("MNG_CMD FEATURE simul_retran=6 dmtu=242", out)
        got = [pt.hex() for _, pt in stats["spec"]]
        self.assertEqual(got, [p for _, _, p in HOTO_WRITE] + [p for _, _, p in HOTO_NOTIFY])
        self.assertIn("miot.ble-rx", out)


class TokenLoading(unittest.TestCase):
    def test_extractor_json_and_env(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "out.json"
            p.write_text('[{"homes":[{"devices":[{"mac":"AA:BB:CC:DD:EE:FF","token":"ffffffffffffffffffffffff"},'
                         '{"mac":"%s","token":"00112233445566778899aabb"}]}]}]' % MAC)
            self.assertEqual(D.load_token(str(p), MAC).hex(), "00112233445566778899aabb")
        os.environ["S200_TOKEN"] = "0102030405060708090a0b0c"
        try:
            self.assertEqual(D.load_token(None, None), bytes(range(1, 13)))
        finally:
            del os.environ["S200_TOKEN"]


if __name__ == "__main__":
    unittest.main()
