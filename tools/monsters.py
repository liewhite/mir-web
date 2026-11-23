"""怪物外观：图库、起始帧、动作表。照原版客户端 Actor.pas 的 aGetMonImg / GetOffset / GetRaceByPM。"""
from __future__ import annotations

import json
from pathlib import Path

ACT = json.loads((Path(__file__).parent / "monact.json").read_text())

_OFFSET_TABLES = {
    13: {0: 0, 1: 360, 2: 440, 3: 550},
    17: {2: 920},
    18: {0: 0, 1: 520, 2: 950},
    19: {0: 0, 1: 370, 2: 810, 3: 1250, 4: 1630, 5: 2010, 6: 2390},
    20: {0: 0, 1: 360, 2: 720, 3: 1080, 4: 1440, 5: 1800, 6: 2350, 7: 3060},
    21: {0: 0, 1: 460, 2: 820, 3: 1180, 4: 1540, 5: 1900, 6: 2440, 7: 2570, 8: 2700},
    22: {0: 0, 1: 430, 2: 1290, 3: 1810},
}


def monster_lib(appr: int) -> str:
    return f"mon{appr // 10 + 1}"


def monster_offset(appr: int) -> int:
    if appr >= 1000:
        return 0
    race, pos = divmod(appr, 10)
    if race == 0:
        return pos * 280
    if race == 1:
        return pos * 230
    if race in (2, 3, 7, 8, 9, 10, 11, 12, 14, 15, 16, 49, 50, 51, 52, 53):
        return pos * 360
    if race == 4:
        return 600 if pos == 1 else pos * 360
    if race == 5:
        return pos * 430
    if race == 6:
        return pos * 440
    if race == 17:
        return _OFFSET_TABLES[17].get(pos, pos * 350)
    if race in _OFFSET_TABLES:
        return _OFFSET_TABLES[race].get(pos, pos * 360 if race == 13 else 0)
    return 0


def action_table(race_img: int, appr: int) -> str | None:
    if race_img == 50:
        return ACT["race50"].get(str(appr), ACT["race50_default"])
    return ACT["race"].get(str(race_img))


def monster_libs(catalog: dict, maps: list[str]) -> set[str]:
    """这些地图上会出现的怪物用到的图库。整库导出：特殊怪的攻击特效（闪电、毒气、钻地等）
    也在同一个图库里、不在动作表范围内，按帧挑容易漏。"""
    names = {s["monster"] for s in catalog["spawns"] if s["map"] in maps}
    names |= {g["monster"] for g in catalog["guards"] if g["map"] in maps}
    names |= {"变异骷髅", "神兽", "骷髅", "卫士", "弓箭守卫", "带刀护卫"}  # 召唤物、守卫
    names |= {"蝙蝠", "爆裂蜘蛛"}  # 怪物召唤的小怪（角蝇、幻影蜘蛛；game/src/world/monster_ai.ts）
    by_name = {m["Name"]: m for m in catalog["monsters"]}
    libs: set[str] = set()
    for n in sorted(names):
        m = by_name.get(n)
        if m is None:
            continue
        if action_table(m["RaceImg"], m["Appr"]) is None:
            print("无动作表:", n, m["RaceImg"], m["Appr"])
            continue
        libs.add(monster_lib(m["Appr"]))
    return libs
