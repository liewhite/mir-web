"""从本机客户端素材生成网页资源（只在本机，不外传）。

用法：uv run --no-project --with numpy --with pillow python build_assets.py [--only maps|libs|ground]
输出：site/mir176/assets/{img,ground,map}
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image

from atlas import write_block
from wemade import MapFile, open_library

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "raw/m176/Resource/Data"
MAP_DIR = ROOT / "raw/m176/Map"
OUT = ROOT / "public/assets"
CATALOG = ROOT / "ref/Mir176/content/catalog.json"

UNITX, UNITY = 48, 32
GROUND_BLOCK = 16  # 每张地面图 16x16 格 = 768x512 像素


def block_size(lib: str) -> int:
    """图集分块大小：人物 600 帧一套；地图物件、大图按 10 帧一块，只下载看得见的；其余 200。
    改这里要同步 game/src/assets/sprites.ts blockSize。"""
    base = lib.rstrip("0123456789")
    if base in ("hum", "weapon", "hair"):
        return 600
    if base in ("objects", "stateitem", "mmap", "npc"):
        return 10
    return 200


def objects_lib(area: int) -> str:
    return "objects" if area == 0 else f"objects{area + 1}"


_LIB_NAMES = {p.stem.lower() for p in DATA_DIR.iterdir() if p.suffix.lower() in (".wil", ".wzl")}


def object_areas(m: MapFile) -> np.ndarray:
    """前景物件的图库编号；没有对应图库文件的（如 255）按原版 GetObjs 当 0（objects）。"""
    area = m.area.copy()
    for a in np.unique(area):
        if objects_lib(int(a)) not in _LIB_NAMES:
            area[area == a] = 0
    return area


# ---------------- 选哪些地图 ----------------

def game_maps(catalog: dict) -> dict[str, str]:
    """游戏里的地图 → 它用的素材名（map/<素材名>.bin、ground/<素材名>/）。

    选图：数据表里有地图文件的全部放（用户 11-23 #1144）。从比奇走不到的（白日门、封魔谷、
    苍月岛等原版靠 NPC 传送的）由传送菜单进（build_data.py TOWNS / ENTRIES）。
    素材名：内容相同的地图（sha256 一样，如祖玛阁 D5061–D5069）共用一份，取组里最小的文件名。"""
    have = {p.stem for p in MAP_DIR.iterdir()}
    meta = {m["id"]: m for m in catalog["maps"] if m["asset"] in have}
    by_sha: dict[str, str] = {}
    for m in meta.values():
        by_sha[m["sha256"]] = min(by_sha.get(m["sha256"], m["asset"]), m["asset"])
    return {i: by_sha[meta[i]["sha256"]] for i in sorted(meta)}


def prune_stale(assets: set[str]) -> None:
    """删掉不再用的地图素材（换了素材名或地图下线），免得部署时带上。"""
    import shutil
    for d in (OUT / "ground").iterdir() if (OUT / "ground").exists() else ():
        if d.name not in assets:
            shutil.rmtree(d)
    for f in (OUT / "map").glob("*.bin") if (OUT / "map").exists() else ():
        if f.stem not in assets:
            f.unlink()


# ---------------- 地图格子数据 ----------------

def write_map_bin(name: str, m: MapFile) -> None:
    """map/<name>.bin：头 'M176' w h，再按 行优先(y*w+x) 存各列：
    flags(u8: 1=不可走 2=不可飞) front(u16) area(u8) ani(u8) aniTick(u8) doorIdx(u8) doorOff(u8) light(u8)"""
    out = OUT / "map"
    out.mkdir(parents=True, exist_ok=True)
    t = lambda a: np.ascontiguousarray(a.T)  # [w,h] -> [h,w] 行优先
    flags = ((~m.walkable).astype(np.uint8)) | (((m.front & 0x8000) != 0).astype(np.uint8) << 1)
    parts = [
        b"M176", struct.pack("<HH", m.width, m.height),
        t(flags).tobytes(),
        t((m.front & 0x7FFF).astype("<u2")).tobytes(),
        t(object_areas(m)).tobytes(), t(m.anim_frame).tobytes(), t(m.anim_tick).tobytes(),
        t(m.door_index).tobytes(), t(m.door_offset).tobytes(), t(m.light).tobytes(),
    ]
    (out / f"{name}.bin").write_bytes(b"".join(parts))


def map_object_needs(m: MapFile) -> dict[str, set[int]]:
    """地图前景层用到的 (图库, 帧号)：含动画帧与开门帧。"""
    need: dict[str, set[int]] = {}
    fr = (m.front & 0x7FFF).astype(np.int64)
    area = object_areas(m)
    xs, ys = np.nonzero(fr)
    for x, y in zip(xs, ys):
        base = int(fr[x, y]) - 1
        ani = int(m.anim_frame[x, y]) & 0x7F
        door = int(m.door_offset[x, y]) & 0x7F if int(m.door_index[x, y]) & 0x7F else 0
        s = need.setdefault(objects_lib(int(area[x, y])), set())
        for k in range(max(ani, 1)):
            s.add(base + k)
        if door:
            s.add(base + door)
    return need


# ---------------- 地面预渲染 ----------------

def _render_ground(args):
    name, bx, by = args
    m = MapFile.load(MAP_DIR / f"{name}.map")
    tiles = _lib_cache("tiles")
    out = OUT / "ground" / name
    W, H = GROUND_BLOCK * UNITX, GROUND_BLOCK * UNITY
    canvas = Image.new("RGB", (W, H), (0, 0, 0))
    x0, y0 = bx * GROUND_BLOCK, by * GROUND_BLOCK
    # 大地砖 96x64 画在偶数格上，可能从左/上一格伸进来
    for x in range(x0 - 2, x0 + GROUND_BLOCK):
        for y in range(y0 - 2, y0 + GROUND_BLOCK):
            if x < 0 or y < 0 or x >= m.width or y >= m.height or x % 2 or y % 2:
                continue
            b = int(m.back[x, y]) & 0x7FFF
            if b:
                f = tiles.frame(b - 1)
                if not f.empty:
                    im = Image.fromarray(f.rgba, "RGBA")
                    canvas.paste(im, ((x - x0) * UNITX, (y - y0) * UNITY), im)
    for x in range(x0 - 2, x0 + GROUND_BLOCK):
        for y in range(y0 - 2, y0 + GROUND_BLOCK):
            if x < 0 or y < 0 or x >= m.width or y >= m.height:
                continue
            mid = int(m.middle[x, y])
            if mid:
                f = _lib_cache("smtiles").frame(mid - 1)
                if not f.empty:
                    im = Image.fromarray(f.rgba, "RGBA")
                    canvas.paste(im, ((x - x0) * UNITX, (y - y0) * UNITY), im)
    canvas.save(out / f"{bx}_{by}.webp", quality=82, method=4)
    return 1


_LIBS: dict = {}


def _lib_cache(name: str):
    if name not in _LIBS:
        _LIBS[name] = open_library(DATA_DIR, name)
    return _LIBS[name]


def build_ground(maps: list[str]) -> None:
    jobs = []
    for name in maps:
        m = MapFile.load(MAP_DIR / f"{name}.map")
        (OUT / "ground" / name).mkdir(parents=True, exist_ok=True)
        nbx = (m.width + GROUND_BLOCK - 1) // GROUND_BLOCK
        nby = (m.height + GROUND_BLOCK - 1) // GROUND_BLOCK
        for bx in range(nbx):
            for by in range(nby):
                x0, y0 = bx * GROUND_BLOCK, by * GROUND_BLOCK
                sub_b = m.back[x0:x0 + GROUND_BLOCK, y0:y0 + GROUND_BLOCK] & 0x7FFF
                sub_m = m.middle[x0:x0 + GROUND_BLOCK, y0:y0 + GROUND_BLOCK]
                if sub_b.any() or sub_m.any():
                    jobs.append((name, bx, by))
                else:
                    (OUT / "ground" / name / f"{bx}_{by}.webp").unlink(missing_ok=True)
    print(f"ground: {len(jobs)} 块", flush=True)
    with ProcessPoolExecutor(8) as ex:
        done = 0
        for _ in ex.map(_render_ground, jobs, chunksize=16):
            done += 1
            if done % 500 == 0:
                print(f"  {done}/{len(jobs)}", flush=True)


# ---------------- 图集块 ----------------

class _Patched:
    """素材修补：盛大 WZL 里底栏聊天区是白色(255)，原版是透明/黑底，改成透明（0）。"""

    def __init__(self, lib, name: str):
        self.lib = lib
        self.name = name
        self.palette = lib.palette

    def frame(self, i: int):
        f = self.lib.frame(i)
        if self.name == "prguse" and i == 1 and f.index is not None:
            idx = f.index.copy()
            area = idx[102:244, 208:595]
            area[area == 255] = 0
            from wemade import Frame
            return Frame(f.w, f.h, f.x, f.y, self.palette[idx], idx)
        return f

    def __len__(self):
        return len(self.lib)


def _write_lib_block(args):
    lib_name, block = args
    lib = _Patched(_lib_cache(lib_name), lib_name)
    size = block_size(lib_name)
    return write_block(lib, lib.palette, OUT / "img" / lib_name, block * size, size)


def rebuild_block(lib_name: str, frame: int) -> None:
    """只重建某一帧所在的块（修补素材后用）。"""
    _write_lib_block((lib_name, frame // block_size(lib_name)))
    bump_version()


def bump_version() -> None:
    """素材版本号：网页按它给素材 URL 加 ?v=，改了素材浏览器才会重新下载。"""
    import time
    (OUT / "version.json").write_text(json.dumps({"v": int(time.time())}))


def build_libs(needs: dict[str, set[int]]) -> None:
    jobs = []
    for lib_name, frames in sorted(needs.items()):
        size = block_size(lib_name)
        for b in sorted({f // size for f in frames}):
            jobs.append((lib_name, b))
    print(f"img: {len(jobs)} 块", flush=True)
    with ProcessPoolExecutor(8) as ex:
        n = sum(ex.map(_write_lib_block, jobs, chunksize=4))
    print(f"img: {n} 帧", flush=True)


def whole(lib: str) -> set[int]:
    return set(range(len(_lib_cache(lib))))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["maps", "libs", "ground"])
    args = ap.parse_args()
    catalog = json.loads(CATALOG.read_text())
    maps = game_maps(catalog)
    assets = sorted(set(maps.values()))
    print(f"maps: {len(maps)} 张，素材 {len(assets)} 份:", " ".join(assets), flush=True)

    needs: dict[str, set[int]] = {}

    def add(lib: str, frames) -> None:
        needs.setdefault(lib, set()).update(frames)

    if args.only in (None, "maps", "libs"):
        for name in assets:
            m = MapFile.load(MAP_DIR / f"{name}.map")
            if args.only != "libs":
                write_map_bin(name, m)
            for lib, fr in map_object_needs(m).items():
                add(lib, fr)
        (OUT / "map" / "index.json").unlink(missing_ok=True)  # 旧版产物，游戏不读

    if args.only in (None, "libs"):
        # 角色、怪物、NPC、技能、物品、界面：图库体积不大的整库导出；怪物按需
        for lib in ["hum", "hair", "weapon", "magic", "magic2", "items", "dnitems", "stateitem",
                    "prguse", "prguse2", "magicon", "npc", "mmap", "effect", "chrsel"]:
            add(lib, whole(lib))
        sys.path.insert(0, str(Path(__file__).parent))
        from monsters import monster_libs  # noqa: E402
        for lib in sorted(monster_libs(catalog, list(maps))):
            add(lib, whole(lib))
        build_libs(needs)

    if args.only in (None, "ground"):
        build_ground(assets)
    if args.only is None:
        prune_stale(set(assets))
    bump_version()


if __name__ == "__main__":
    main()
