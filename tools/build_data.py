"""把 Mir176 的 1.76 数据表编译成游戏用的 game.json（只留用得到的字段）。

用法：python3 build_data.py   输出 public/assets/data/game.json
"""
from __future__ import annotations

import json
from pathlib import Path

from build_assets import OUT, bump_version, game_maps
from catalog import load_catalog
from monsters import ACT, action_table, monster_lib, monster_offset

ROOT = Path(__file__).resolve().parent.parent
EXP = ROOT / "ref/Mir176/content/level-experience.json"


# ---------------- 传送菜单（用户 11-23 #1139：任意地方直接传到城中心、打怪地图入口） ----------------

# 城中心：比奇是出生点（game/src/main.ts START），其余取城里商店、守卫中间（都核对过是可走格）
# 白日门、封魔谷、苍月岛数据表里没有路通过去（原版靠 NPC 传送），只能从这里进（#1144）
TOWNS = [
    ("0", "比奇省", 333, 272), ("3", "盟重省", 350, 340),
    ("11", "白日门", 178, 325), ("4", "封魔谷", 241, 230), ("5", "苍月岛", 140, 325),
    # 1.80 魔龙城（#1313，补充表 tools/data/v180-molong.json）：国服从盟重找 NPC 传过去，本服只从传送菜单进；落点是 GeeM2 StartPoint 的出生点
    ("6", "魔龙城", 124, 156),
]
# 不在任何城后面、也没有路进去的打怪地图：自成一个地区，落点取地图中间附近的可走格
ENTRIES = [("M001", 139, 140), ("T1341", 20, 21)]
# 幻境（#1317，补充表 tools/data/huanjing.json）：原版从盟重找老兵进一层（脚本 mapmove H001 73 67），本服只从传送菜单进；
# 每层都有随机位置的 boss，但用户 11-26 定传送到五层（见 BOSS_OK、DEEPEST）
ENTRIES.append(("H001", 73, 67))
# 地区名：按「出城后第一张图」起名；没列出的用那张图的标题去掉「一层/入口/大殿/出入口」
REGION_NAMES = {
    "D001": "骷髅洞", "1": "沃玛", "D401": "废矿", "D601": "地牢", "D701": "香石古墓",
    "0157": "祖玛寺庙", "D710": "石墓（猪洞）",
    "12": "丛林迷宫·赤月", "D2000": "封魔矿区·封魔殿", "B341": "封魔皇宫·姻缘",
    "H001": "幻境",
    # 魔龙东郊、西郊两条路合成一组（用户 11-26）
    "61": "魔龙", "62": "魔龙",
}
# 城市另在这个地区组的最前面再列一次（魔龙城放进「魔龙」组，用户 11-26）
TOWN_IN_GROUP = {"6": "魔龙"}
# 传送菜单不到 boss 层（用户 11-25 #1245/#1315）：刷这些怪的图、迷宫、以及只能穿过它们才到得了的更深的图都不列，剩下自己走。
# boss 名单同 scala Rates.bosses（GameDataSpec 核对传送菜单里没有刷 Rates.bosses 的图，名单不同步会报）。
BOSSES = {
    "沃玛教主", "祖玛教主", "千年树妖", "石墓尸王", "牛魔王", "赤月恶魔", "双头金刚", "双头血魔", "黄泉教主", "虹魔教主",
    "暗之沃玛教主", "暗之牛魔王", "暗之双头金刚", "暗之双头血魔", "暗之黄泉教主", "暗之虹魔教主",
    "魔龙教主", "暗之魔龙教主",
}
# 迷宫（一个标题拆成很多张图、靠门互通的）：石墓阵 D716xx、祖玛阁 D506x
MAZE_TITLES = {"石墓阵", "祖玛阁"}
# 用户点名的最深一层：祖玛、牛魔到五层，赤月到抉择之地；这些图列，再往里的不列
DEEPEST = {"D505", "D2076", "D1004"}
# 魔龙：到魔龙谷为止，龙源血路、魔龙血域自己走（用户 11-26）；幻境到五层（用户 11-26）
DEEPEST |= {"64", "H005"}
# 刷 boss 也照列的图：幻境每层的 boss 是本服随机位置加的（#1317），不算 boss 层；用户点名传到五层
BOSS_OK = {"H002", "H003", "H004", "H005"}
# 迷宫门照原版改的地方（Mir176 数据表与 1.76 服务端 MapInfo 不同处；核对见 #1315）：(图, x, y) -> (去的图, x, y)
ROUTE_FIXES = {
    # 祖玛阁第一格的这扇门数据表直通祖玛六层大厅（等于跳过迷宫），原版通往迷宫另一格
    ("D5061", 66, 16): ("D5062", 85, 12),
}


def fixed_routes(cat: dict) -> list[dict]:
    """数据表的传送点，套上 ROUTE_FIXES；每条修正都得对上数据表里的一扇门，对不上（数据表变了）就报错。"""
    out, used = [], set()
    for r in cat["routes"]:
        key = (r["from"], r["x"], r["y"])
        if key in ROUTE_FIXES:
            used.add(key)
            to, tx, ty = ROUTE_FIXES[key]
            r = r | {"to": to, "tx": tx, "ty": ty}
        out.append(r)
    if missing := set(ROUTE_FIXES) - used:
        raise SystemExit(f"ROUTE_FIXES 对不上数据表的门：{sorted(missing)}")
    return out


def teleports(cat: dict, maps: dict[str, str]) -> list[dict]:
    """传送目的地：城市一组；其余按地区分组，每组是出城后第一张图（入口）和组里有怪的地图。
    落点 = 从比奇走过去时进这张图的传送点落点（BFS 先到的那条路），同名的只留最近的一张。
    比奇走不到的从 TOWNS、ENTRIES 依次接着搜（先搜完比奇，原有地图的来路不变）；
    ENTRIES 自己是地区入口，落点用表里给的。
    只列不穿过 boss 图、迷宫、DEEPEST 就走得到的图（城和 ENTRIES 本身总列：ENTRIES 没有路进去）。"""
    towns = [t for t in TOWNS if t[0] in maps]  # 本机没有地图文件的城（如没下 1.80 魔龙城）不列
    town_ids = {t[0] for t in towns}
    seeds = {t[0]: (t[2], t[3]) for t in towns} | {e[0]: (e[1], e[2]) for e in ENTRIES}
    titles = {m["id"]: m["title"] for m in cat["maps"]}
    routes: dict[str, list[dict]] = {}
    for r in fixed_routes(cat):
        if r["from"] in maps and r["to"] in maps:
            routes.setdefault(r["from"], []).append(r)
    came: dict[str, dict | None] = {}
    order: list[str] = []
    i = 0
    for seed in seeds:  # 每个起点一轮 BFS：order 边走边长，i 是下一个要展开的
        if seed in came:
            continue
        came[seed] = None
        order.append(seed)
        while i < len(order):
            for r in routes.get(order[i], []):
                if r["to"] not in came:
                    came[r["to"]] = r
                    order.append(r["to"])
            i += 1
    with_monsters = {s["map"] for s in cat["spawns"]}
    boss_maps = {s["map"] for s in cat["spawns"] if s["monster"] in BOSSES} - town_ids - BOSS_OK
    blocked = boss_maps | {m for m in maps if titles[m] in MAZE_TITLES}
    open_maps = set(seeds)  # 不穿过 blocked、DEEPEST 走得到的
    frontier = list(seeds)
    while frontier:
        m = frontier.pop()
        if m in DEEPEST and m not in town_ids:
            continue
        for r in routes.get(m, []):
            if r["to"] not in open_maps and r["to"] not in blocked:
                open_maps.add(r["to"])
                frontier.append(r["to"])

    def region(m: str) -> str | None:
        """沿来路往回找，最后一座城之后的第一张图；路上没有城（ENTRIES 搜到的）就是起点那张。"""
        path = [m]
        while (r := came[path[-1]]) is not None:
            path.append(r["from"])
        path.reverse()
        last_town = max((i for i, x in enumerate(path) if x in town_ids), default=-1)
        return path[last_town + 1] if last_town + 1 < len(path) else None

    def landing(m: str) -> tuple[int, int]:
        r = came[m]
        return seeds[m] if r is None else (r["tx"], r["ty"])

    out = [{"group": "城市", "name": name, "map": m, "x": x, "y": y} for m, name, x, y in towns]
    def group_name(root: str) -> str:
        return REGION_NAMES.get(root) or titles[root].removesuffix("一层").removesuffix("出入口").removesuffix("入口").removesuffix("大殿")

    # 按组名归并：几个入口（如魔龙东郊、西郊）同名就合成一组，按从比奇走过去的先后排
    groups: dict[str, list[str]] = {}
    for m in order:
        root = None if m in town_ids else region(m)
        if root and m in open_maps and (m == root or m in with_monsters):
            groups.setdefault(group_name(root), []).append(m)
    town_by_group = {g: t for t, g in TOWN_IN_GROUP.items() if t in town_ids}
    for group, ms in groups.items():
        if not any(m in with_monsters for m in ms):
            continue  # 城里的店铺、房屋
        if (t := town_by_group.get(group)) is not None:
            out.append(next(o | {"group": group} for o in out if o["map"] == t and o["group"] == "城市"))
        seen: set[str] = set()
        for m in ms:
            if titles[m] in seen:
                continue
            seen.add(titles[m])
            x, y = landing(m)
            out.append({"group": group, "name": titles[m], "map": m, "x": x, "y": y})
    return out


def pair(lo, hi) -> list[int]:
    return [int(lo or 0), int(hi or 0)]


def main() -> None:
    cat = load_catalog()
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
        "routes": [r for r in fixed_routes(cat) if r["from"] in maps and r["to"] in maps],
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
