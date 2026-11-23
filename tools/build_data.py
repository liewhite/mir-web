"""把 Mir176 的 1.76 数据表编译成游戏用的 game.json（只留用得到的字段）。

用法：python3 build_data.py   输出 site/mir176/assets/data/game.json
"""
from __future__ import annotations

import json
from pathlib import Path

from build_assets import CATALOG, OUT, bump_version, game_maps
from monsters import ACT, action_table, monster_lib, monster_offset

ROOT = Path(__file__).resolve().parent.parent
EXP = ROOT / "ref/Mir176/content/level-experience.json"


def pair(lo, hi) -> list[int]:
    return [int(lo or 0), int(hi or 0)]


def main() -> None:
    cat = json.loads(CATALOG.read_text())
    maps = game_maps(cat)
    map_meta = {m["id"]: m for m in cat["maps"]}

    items = []
    for it in cat["items"]:
        items.append({
            "id": it["Id"], "name": it["Name"], "mode": it["StdMode"], "shape": it["Shape"],
            "weight": it["Weight"], "ani": it["AniCount"], "source": it["Source"], "look": it["ImgIndex"],
            "dura": it["DuraMax"], "ac": pair(it["Ac"], it["AcMax"]), "mac": pair(it["Mac"], it["MacMax"]),
            "dc": pair(it["Dc"], it["DcMax"]), "mc": pair(it["Mc"], it["McMax"]), "sc": pair(it["Sc"], it["ScMax"]),
            "need": it["Need"], "needLevel": it["NeedLevel"], "price": it["Price"],
        })

    monsters = []
    for m in cat["monsters"]:
        table = action_table(m["RaceImg"], m["Appr"])
        monsters.append({
            "name": m["Name"], "race": m["Race"], "raceImg": m["RaceImg"], "appr": m["Appr"],
            "lib": monster_lib(m["Appr"]), "offset": monster_offset(m["Appr"]), "act": table,
            "level": m["Lvl"], "undead": m["Undead"], "exp": m["Exp"], "hp": m["HP"], "mp": m["MP"],
            "ac": [m["AC"], m["AC"]], "mac": [m["MAC"], m["MAC"]], "dc": [m["DC"], m["DCMAX"]], "mc": m["MC"], "sc": m["SC"],
            "speed": m["SPEED"], "hit": m["HIT"], "walkSpeed": m["WALK_SPD"], "walkStep": m["WalkStep"],
            "walkWait": m["WaLkWait"], "attackSpeed": m["ATTACK_SPD"], "coolEye": m["CoolEye"],
            "viewRange": m["ViewRange"],
        })

    drops = cat["drops"]
    display = cat["monster_display_names"]

    skills = [{
        "id": s["MagID"], "name": s["MagName"], "job": s["Job"], "effectType": s["EffectType"], "effect": s["Effect"],
        "spell": s["Spell"], "power": [s["Power"], s["MaxPower"]], "defSpell": s["DefSpell"],
        "defPower": [s["DefPower"], s["DefMaxPower"]], "needLevel": [s["NeedL1"], s["NeedL2"], s["NeedL3"]],
        "train": [s["L1Train"], s["L2Train"], s["L3Train"]], "delay": s["Delay"] * 10,
    } for s in cat["skills"]]

    in_maps = lambda r: r["map"] in maps
    game = {
        "maps": [{"id": i, "asset": a, "title": map_meta[i]["title"], "flags": map_meta[i]["flags"],
                  "width": map_meta[i]["width"], "height": map_meta[i]["height"]} for i, a in maps.items()],
        "routes": [r for r in cat["routes"] if r["from"] in maps and r["to"] in maps],
        "spawns": [s for s in cat["spawns"] if in_maps(s)],
        "guards": [g for g in cat["guards"] if in_maps(g)],
        "merchants": [m for m in cat["merchants"] if in_maps(m)],
        "minimaps": {m["map"]: m["image"] for m in cat["minimaps"] if m["map"] in maps},
        "items": items, "monsters": monsters, "drops": drops, "displayNames": display, "skills": skills,
        "exp": json.loads(EXP.read_text())["experience_to_next_level"],
        "monsterActions": ACT["tables"],
    }
    out = OUT / "data"
    out.mkdir(parents=True, exist_ok=True)
    (out / "game.json").write_text(json.dumps(game, ensure_ascii=False, separators=(",", ":")))
    bump_version()
    print("game.json", (out / "game.json").stat().st_size, "bytes;",
          {k: len(v) for k, v in game.items() if isinstance(v, (list, dict))})


if __name__ == "__main__":
    main()
