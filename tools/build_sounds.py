"""原版音效（Wav/sound.lst 编号 → wav）转成网页用的 AAC（.m4a，Safari/Chrome 都能播）。

输出 public/assets/sound/<编号>.m4a、bgm_<名>.m4a、index.json（有哪些编号）。
"""
from __future__ import annotations

import json
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from build_assets import OUT, bump_version

ROOT = Path(__file__).resolve().parent.parent
WAV = ROOT / "raw/m176/Wav"
DST = OUT / "sound"
BGM = {"field": "Field2.wav", "intro": "log-in-long2.wav"}


def entries() -> dict[int, Path]:
    files = {p.name.lower(): p for p in WAV.iterdir()}
    out: dict[int, Path] = {}
    for line in (WAV / "sound.lst").read_bytes().decode("gbk", "replace").splitlines():
        m = re.match(r"\s*(\d+)\s*:\s*(\S+)", line)
        if not m:
            continue
        name = m.group(2).replace("\\", "/").split("/")[-1].lower()
        if name in files:
            out[int(m.group(1))] = files[name]
    return out


def convert(src: Path, dst: Path, bitrate: str) -> bool:
    if dst.exists() and dst.stat().st_mtime >= src.stat().st_mtime:
        return True
    r = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-i", str(src), "-ac", "1", "-c:a", "aac", "-b:a", bitrate, str(dst)],
        capture_output=True,
    )
    return r.returncode == 0


def main() -> None:
    DST.mkdir(parents=True, exist_ok=True)
    es = entries()
    with ThreadPoolExecutor(8) as ex:
        ok = list(ex.map(lambda kv: (kv[0], convert(kv[1], DST / f"{kv[0]}.m4a", "48k")), es.items()))
        bgm = list(ex.map(lambda kv: (kv[0], convert(WAV / kv[1], DST / f"bgm_{kv[0]}.m4a", "64k")), BGM.items()))
    ids = sorted(i for i, good in ok if good)
    (DST / "index.json").write_text(json.dumps({"ids": ids, "bgm": [k for k, good in bgm if good]}))
    bump_version()
    print(f"sounds {len(ids)}/{len(es)}, bgm {bgm}")


if __name__ == "__main__":
    main()
