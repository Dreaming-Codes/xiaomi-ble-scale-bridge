#!/usr/bin/env python3
"""Download the Xiaomi Home plugin bundle for a model (default xiaomi.scales.ms113).

Logs in with the QR flow from PiotrMachowski/Xiaomi-cloud-tokens-extractor
(scan the QR with Xiaomi Home), then asks /v2/plugin/fetch_plugin for the
latest package and saves and unpacks it.

    python3 tools/fetch_plugin.py --extractor ~/src/Xiaomi-cloud-tokens-extractor/token_extractor.py \
        --server de --out plugin-ms113
"""

from __future__ import annotations

import argparse
import importlib.util
import io
import json
import sys
import zipfile
from pathlib import Path


def load_extractor(path: Path):
    spec = importlib.util.spec_from_file_location("token_extractor", path)
    mod = importlib.util.module_from_spec(spec)
    sys.argv = [str(path)]
    spec.loader.exec_module(mod)
    return mod


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--extractor", type=Path, required=True)
    ap.add_argument("--server", default="de")
    ap.add_argument("--model", default="xiaomi.scales.ms113")
    ap.add_argument("--api-version", type=int, default=10090)
    ap.add_argument("--out", type=Path, default=Path("plugin"))
    args = ap.parse_args()

    te = load_extractor(args.extractor)
    conn = te.QrCodeXiaomiCloudConnector()
    if not conn.login():
        raise SystemExit("login failed")
    req = {
        "latest_req": {"api_version": args.api_version, "app_platform": "Android",
                       "region": args.server.upper(), "package_type": "",
                       "plugins": [{"model": args.model}]},
    }
    url = conn.get_api_url(args.server) + "/v2/plugin/fetch_plugin"
    resp = conn.execute_api_call_encrypted(url, {"data": json.dumps(req)})
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "fetch_plugin.json").write_text(json.dumps(resp, indent=2))
    infos = (resp or {}).get("result", {}).get("latest_info") or []
    urls = [i.get("download_url") or i.get("safe_url") for i in infos if i.get("download_url") or i.get("safe_url")]
    if not urls:
        raise SystemExit(f"no download_url; see {args.out / 'fetch_plugin.json'}")
    blob = conn._session.get(urls[0], timeout=120).content
    (args.out / "plugin.mpk").write_bytes(blob)
    try:
        zipfile.ZipFile(io.BytesIO(blob)).extractall(args.out / "unpacked")
        print(f"unpacked to {args.out / 'unpacked'}; look for main.bundle / *.jsbundle")
    except zipfile.BadZipFile:
        print(f"saved {args.out / 'plugin.mpk'} (not a zip)")


if __name__ == "__main__":
    main()
