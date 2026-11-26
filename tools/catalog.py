"""游戏数据的来源：1.76 数据表（ref/Mir176 catalog.json，不进仓库）+ 仓库里的补充表（tools/data/*.json）。

补充表是 1.76 数据表之外加的内容（如 1.80 魔龙系列，#1313），格式和 catalog.json 一样，按键合并：
- 列表（maps、routes、spawns、merchants、guards、minimaps、monsters、items）接在后面；
- 字典（drops、monster_display_names）按键补上；
- drops_append（怪名 → 爆率行）接在已有怪的爆率表后面（全部补充表合并完再接，怪没有爆率表就报错）。
地图条目的 asset 是地图文件名（不带 .map），在 MAP_DIRS 里按顺序找，可以指向 1.76 已有的地图文件
（内容相同的地图共用素材）；没写 sha256 的按文件现算。同名的地图编号、怪物名、物品名重复就报错。
怪物条目可以写 StatsFrom（另一只怪的名字）：只写名字和外观（Race、RaceImg、Appr），其余数值照那只怪抄，
免得同一份数值抄两处（白虎照大神兽，#1336）。
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


def _merge(cat: dict, extra: dict, source: str, append: list[tuple[str, dict]]) -> None:
    for key, val in extra.items():
        if key.startswith("_"):
            continue  # 说明
        if key == "drops_append":
            append.append((source, val))
            continue
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


_OWN_FIELDS = {"Name", "Race", "RaceImg", "Appr", "StatsFrom"}


def _fill_stats_from(monsters: list[dict]) -> None:
    by_name = {m["Name"]: m for m in monsters}
    copied = {m["Name"] for m in monsters if "StatsFrom" in m}
    for m in monsters:
        src_name = m.pop("StatsFrom", None)
        if src_name is None:
            continue
        src = by_name.get(src_name)
        if src is None or src_name in copied:
            raise SystemExit(f"{m['Name']}: StatsFrom 的 {src_name} 不存在或自己也是抄来的")
        extra = set(m) - _OWN_FIELDS
        if extra:
            raise SystemExit(f"{m['Name']}: 写了 StatsFrom 就不能再写数值：{sorted(extra)}")
        m.update({k: v for k, v in src.items() if k not in _OWN_FIELDS})


def load_catalog() -> dict:
    cat = json.loads(CATALOG.read_text())
    append: list[tuple[str, dict]] = []
    for f in sorted(EXTRA_DIR.glob("*.json")):
        _merge(cat, json.loads(f.read_text()), f.name, append)
    for source, rows_by_monster in append:
        for monster, rows in rows_by_monster.items():
            if monster not in cat["drops"]:
                raise SystemExit(f"{source}: drops_append 的 {monster} 没有爆率表")
            cat["drops"][monster].extend(rows)
    _fill_stats_from(cat["monsters"])
    for m in cat["maps"]:
        if "sha256" not in m and (p := map_file(m["asset"])) is not None:
            m["sha256"] = hashlib.sha256(p.read_bytes()).hexdigest()
    return cat
