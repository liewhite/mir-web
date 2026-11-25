"""游戏数据的来源：1.76 数据表（ref/Mir176 catalog.json，不进仓库）+ 仓库里的补充表（tools/data/*.json）。

补充表是 1.76 数据表之外加的内容（如 1.80 魔龙系列，#1313），格式和 catalog.json 一样，按键合并：
- 列表（maps、routes、spawns、merchants、guards、minimaps、monsters、items）接在后面；
- 字典（drops、monster_display_names）按键补上。
地图条目的 asset 是地图文件名（不带 .map），在 MAP_DIRS 里按顺序找，可以指向 1.76 已有的地图文件
（内容相同的地图共用素材）；没写 sha256 的按文件现算。同名的地图编号、怪物名、物品名重复就报错。
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "ref/Mir176/content/catalog.json"
EXTRA_DIR = Path(__file__).resolve().parent / "data"
# 地图文件：1.76 客户端素材，1.80 官方客户端镜像 qigedashen/mir2-1.80-mirror 的 Map（只放本机，不进仓库）
MAP_DIRS = [ROOT / "raw/m176/Map", ROOT / "raw/c180/Map"]

_UNIQUE = {"maps": "id", "monsters": "Name", "items": "Name"}


def map_file(asset: str) -> Path | None:
    """地图文件路径；本机没有就 None。"""
    for d in MAP_DIRS:
        p = d / f"{asset}.map"
        if p.exists():
            return p
    return None


def _merge(cat: dict, extra: dict, source: str) -> None:
    for key, val in extra.items():
        if key.startswith("_"):
            continue  # 说明
        if isinstance(val, list):
            have = cat.setdefault(key, [])
            if key in _UNIQUE:
                k = _UNIQUE[key]
                dup = {e[k] for e in have} & {e[k] for e in val}
                if dup:
                    raise SystemExit(f"{source}: {key} 和已有的重名：{sorted(dup)}")
            have.extend(val)
        elif isinstance(val, dict):
            dup = set(cat.setdefault(key, {})) & set(val)
            if dup:
                raise SystemExit(f"{source}: {key} 和已有的重名：{sorted(dup)}")
            cat[key].update(val)
        else:
            raise SystemExit(f"{source}: 不认识的键 {key}")


def load_catalog() -> dict:
    cat = json.loads(CATALOG.read_text())
    for f in sorted(EXTRA_DIR.glob("*.json")):
        _merge(cat, json.loads(f.read_text()), f.name)
    for m in cat["maps"]:
        if "sha256" not in m and (p := map_file(m["asset"])) is not None:
            m["sha256"] = hashlib.sha256(p.read_bytes()).hexdigest()
    return cat
