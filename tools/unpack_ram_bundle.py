"""Split a React Native indexed RAM bundle (Xiaomi Home plugin main.bundle) into one JS file per module.

python3 tools/unpack_ram_bundle.py main.bundle out/
"""

import struct
import sys
from pathlib import Path

MAGIC = 0xFB0BD1E5


def unpack(bundle: bytes) -> tuple[bytes, dict[int, tuple[int, bytes]]]:
    magic, count, startup = struct.unpack_from("<III", bundle, 0)
    if magic != MAGIC:
        raise ValueError(f"not an indexed RAM bundle (magic {magic:#x})")
    base = 12 + count * 8
    mods = {}
    for i in range(count):
        off, length = struct.unpack_from("<II", bundle, 12 + i * 8)
        if length:
            mods[i] = (base + off, bundle[base + off:base + off + length].rstrip(b"\0"))
    return bundle[base:base + startup], mods


def main() -> None:
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    startup, mods = unpack(src.read_bytes())
    (out / "startup.js").write_bytes(startup)
    for i, (off, code) in mods.items():
        (out / f"{i:05d}@{off}.js").write_bytes(code)
    print(f"{len(mods)} modules -> {out}")


if __name__ == "__main__":
    main()
