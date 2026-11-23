"""把图片库的帧打成网页用的图集块。

一个块 = 某个图库里帧号 [start, start+size) 的全部帧，输出：
  <out>/<lib>/<block>.json  {"pages": n, "frames": [[page, ax, ay, w, h, ox, oy] | 0, ...]}
  <out>/<lib>/<block>_<page>.png
8 位帧打成调色板 PNG（体积小），含 16 位帧的页打成 RGBA PNG。
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image

from wemade import Frame

PAGE = 2048
PAD = 1


@dataclass
class _Placed:
    i: int
    frame: Frame
    page: int = 0
    x: int = 0
    y: int = 0


def _shelf_pack(items: list[_Placed]) -> list[tuple[int, int]]:
    """按高度降序的货架装箱；返回每页的 (宽, 高)。超大帧单独占一页。"""
    pages: list[tuple[int, int]] = []
    order = sorted(items, key=lambda p: -p.frame.h)
    page = -1
    x = y = shelf_h = used_w = 0
    for p in order:
        w, h = p.frame.w + PAD, p.frame.h + PAD
        if page < 0 or x + w > PAGE:
            # 换行
            y += shelf_h
            x, shelf_h = 0, 0
            if page < 0 or y + h > PAGE:
                if page >= 0:
                    pages.append((used_w, y))
                page += 1
                x = y = shelf_h = used_w = 0
        p.page, p.x, p.y = page, x, y
        x += w
        shelf_h = max(shelf_h, h)
        used_w = max(used_w, x)
    if page >= 0:
        pages.append((used_w, y + shelf_h))
    return pages


def write_block(lib, palette: np.ndarray, out_dir: Path, start: int, size: int) -> int:
    """写一个块，返回非空帧数。"""
    frames: list[Frame | None] = []
    for i in range(start, start + size):
        f = lib.frame(i)
        frames.append(None if f.empty else f)
    placed = [_Placed(i, f) for i, f in enumerate(frames) if f is not None]
    out_dir.mkdir(parents=True, exist_ok=True)
    block = start // size
    meta: list = [0] * size
    if not placed:
        (out_dir / f"{block}.json").write_text(json.dumps({"pages": 0, "frames": meta}, separators=(",", ":")))
        return 0
    pages = _shelf_pack(placed)
    for pg, (pw, ph) in enumerate(pages):
        on_page = [p for p in placed if p.page == pg]
        indexed = all(p.frame.index is not None for p in on_page)
        if indexed:
            canvas = np.zeros((ph, pw), np.uint8)
            for p in on_page:
                canvas[p.y:p.y + p.frame.h, p.x:p.x + p.frame.w] = p.frame.index
            im = Image.fromarray(canvas, "P")
            im.putpalette(palette[:, :3].reshape(-1).tolist())
            im.info["transparency"] = 0
            im.save(out_dir / f"{block}_{pg}.png", optimize=True, transparency=0)
        else:
            canvas = np.zeros((ph, pw, 4), np.uint8)
            for p in on_page:
                canvas[p.y:p.y + p.frame.h, p.x:p.x + p.frame.w] = p.frame.rgba
            Image.fromarray(canvas, "RGBA").save(out_dir / f"{block}_{pg}.png", optimize=True)
    for p in placed:
        f = p.frame
        meta[p.i] = [p.page, p.x, p.y, f.w, f.h, f.x, f.y]
    (out_dir / f"{block}.json").write_text(json.dumps({"pages": len(pages), "frames": meta}, separators=(",", ":")))
    return len(placed)
