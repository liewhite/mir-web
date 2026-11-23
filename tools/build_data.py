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


# ---------------- 传送菜单（用户 11-23 #1139：任意地方直接传到城中心、打怪地图入口） ----------------

# 城中心：比奇是出生点（game/src/main.ts START），盟重取城里商店、守卫中间
TOWNS = [("0", "比奇省", 333, 272), ("3", "盟重省", 350, 340)]
# 地区名：按「出城后第一张图」起名；没列出的用那张图的标题去掉「一层/入口/大殿」
REGION_NAMES = {
    "D001": "骷髅洞", "1": "沃玛", "D401": "废矿", "D601": "地牢", "D701": "香石古墓",
    "0157": "祖玛寺庙", "D710": "石墓（猪洞）",
}


def teleports(cat: dict, maps: dict[str, str]) -> list[dict]:
    """传送目的地：城市一组；其余按地区分组，每组是出城后第一张图（入口）和组里有怪的地图。
    落点 = 从比奇走过去时进这张图的传送点落点（BFS 先到的那条路），同名的只留最近的一张。"""
    town_ids = {t[0] for t in TOWNS}
    titles = {m["id"]: m["title"] for m in cat["maps"]}
    routes: dict[str, list[dict]] = {}
    for r in cat["routes"]:
        if r["from"] in maps and r["to"] in maps:
            routes.setdefault(r["from"], []).append(r)
    came: dict[str, dict | None] = {"0": None}
    order = ["0"]
    for u in order:  # BFS：order 边走边长
        for r in routes.get(u, []):
            if r["to"] not in came:
                came[r["to"]] = r
                order.append(r["to"])
    with_monsters = {s["map"] for s in cat["spawns"]}

    def region(m: str) -> str | None:
        """沿来路往回找，最后一座城之后的第一张图。"""
        path = [m]
        while (r := came[path[-1]]) is not None:
            path.append(r["from"])
        path.reverse()
        last_town = max(i for i, x in enumerate(path) if x in town_ids)
        return path[last_town + 1] if last_town + 1 < len(path) else None

    out = [{"group": "城市", "name": name, "map": m, "x": x, "y": y} for m, name, x, y in TOWNS]
    groups: dict[str, list[str]] = {}
    for m in order:
        root = None if m in town_ids else region(m)
        if root and (m == root or m in with_monsters):
            groups.setdefault(root, []).append(m)
    for root, ms in groups.items():
        if not any(m in with_monsters for m in ms):
            continue  # 城里的店铺、房屋
        group = REGION_NAMES.get(root) or titles[root].removesuffix("一层").removesuffix("入口").removesuffix("大殿")
        seen: set[str] = set()
        for m in ms:
            if titles[m] in seen:
                continue
            seen.add(titles[m])
            r = came[m]
            out.append({"group": group, "name": titles[m], "map": m, "x": r["tx"], "y": r["ty"]})
    return out


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
        "teleports": teleports(cat, maps),
    }
    out = OUT / "data"
    out.mkdir(parents=True, exist_ok=True)
    (out / "game.json").write_text(json.dumps(game, ensure_ascii=False, separators=(",", ":")))
    bump_version()
    print("game.json", (out / "game.json").stat().st_size, "bytes;",
          {k: len(v) for k, v in game.items() if isinstance(v, (list, dict))})


if __name__ == "__main__":
    main()
