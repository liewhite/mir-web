"""生成 1.80 魔龙系列的补充表 tools/data/v180-molong.json（#1313，一次性导入，结果进仓库，改数值直接改这里重跑）。

来源（都不进仓库）：
- 地图文件：1.80 官方客户端镜像 qigedashen/mir2-1.80-mirror 的 Map，放本机 raw/c180/Map/（魔龙城以官方为准）。
- 地图门、刷怪、NPC 位置：cjlaaa/Mir2-GeeM2 的 Envir/mapinfo.txt、mongen.txt、merchant.txt（UTF-8）。
- 怪物外观（RaceImg、Appr）：GeeM2 数据库；数值按用户 11-25 认可的 #1245 评论（见 MONSTERS）。

用法：uv run --no-project --with numpy python import_v180.py <GeeM2 目录>
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from catalog import CATALOG, EXTRA_DIR, map_file
from wemade import MapFile

# 地图编号、名字、标记（mapinfo.txt 原样）
MAPS = [
    ("6", "魔龙城", ""), ("B357", "魔龙军营", "DAY"), ("GA1", "魔龙宫殿", "DAY"),
    ("61", "魔龙东郊", ""), ("62", "魔龙西郊", ""), ("63", "魔龙岭", ""),
    ("64", "魔龙谷", "NORECALL NORECONNECT(63)"), ("65", "龙源血路", "NORECALL NORECONNECT(63)"),
    ("66", "魔龙血域", "NORECALL NORECONNECT(63)"),
    ("611", "东林间胜地", ""), ("621", "西林间胜地", ""), ("612", "魔龙旧寨", ""), ("613", "魔龙祭坛", ""),
    ("622", "魔龙沼泽", ""), ("632", "东魔龙关", "NORECALL NORECONNECT(63)"),
    ("631", "西魔龙关", "NORECALL NORECONNECT(63)"),
]
# GeeM2 MiniMap.txt（核对过 1.76 素材的 mmap 里有这些帧）
MINIMAPS = {"6": 191, "61": 192, "611": 193, "612": 194, "613": 195, "62": 196, "621": 197, "622": 198,
            "63": 199, "631": 200, "632": 201, "64": 202, "65": 203, "66": 204}
# GeeM2 mapinfo 里写错的门（落点或起点不是可走格，照同一扇门其他格子的规律改）：(图, x, y) -> (x, y, 去的图, tx, ty)
GATE_FIXES = {
    ("622", 265, 36): ("622", 265, 36, "63", 258, 365),
    ("622", 621, 40): ("622", 261, 40, "63", 255, 369),
    ("622", 620, 41): ("622", 260, 41, "63", 254, 370),
    ("63", 255, 369): ("63", 255, 369, "622", 262, 41),
    ("63", 254, 370): ("63", 254, 370, "622", 261, 42),
}


def monster(name, race, race_img, appr, lvl, hp, ac, dc, hit, attack_ms, exp, speed, walk_ms, cool_eye):
    return {
        "Name": name, "Race": race, "RaceImg": race_img, "Appr": appr, "Lvl": lvl, "Undead": 1, "CoolEye": cool_eye,
        "Exp": exp, "HP": hp, "MP": 0, "AC": ac, "MAC": ac, "DC": dc[0], "DCMAX": dc[1], "MC": 0, "SC": 0,
        "SPEED": speed, "HIT": hit, "WALK_SPD": walk_ms, "WalkStep": 1, "WaLkWait": 0, "ATTACK_SPD": attack_ms,
        "ViewRange": 0,
    }


# 用户 11-25 认可的强度（#1245 评论）：低级 攻 50~90 防/魔防 15 血 1200 出手 1.8 秒 准确 18 经验 1500 等级 54；
# 高级 攻 70~130 防 20 血 2000 出手 1.5 秒 准确 20 经验 2500 等级 57；教主 攻 250~350 防 30 血 2 万 出手 1.5 秒 准确 30
# 经验 2 万 等级 60。外观 Appr 照 GeeM2（mon22 各槽位）；躲避、走路间隔照 GeeM2。
# Race（服务端出招，见 scala Behavior.of）：81 普通近战；104 远程射箭；121 远程带毒（巨蛾，本服加）。
# RaceImg（客户端画法，见 Actor.pas）：19 普通（TCatMon，MA19）；45 弓箭手（TArcherMon）；78 魔龙教主（TBanyaGuardMon，MA40）。
# 战将 GeeM2 是 RaceImg 21（沃玛教主的画法，会放 mon4 的沃玛特效），这里按普通的 19 画。
LOW = dict(lvl=54, hp=1200, ac=15, dc=(50, 90), hit=18, attack_ms=1800, exp=1500)
HIGH = dict(lvl=57, hp=2000, ac=20, dc=(70, 130), hit=20, attack_ms=1500, exp=2500)
MONSTERS = [
    monster("魔龙刀兵", 81, 19, 213, speed=16, walk_ms=900, cool_eye=10, **LOW),
    monster("魔龙破甲兵", 81, 19, 214, speed=16, walk_ms=900, cool_eye=10, **LOW),
    monster("魔龙射手", 104, 45, 215, speed=15, walk_ms=1000, cool_eye=10, **LOW),
    monster("魔龙巨蛾", 121, 45, 210, speed=15, walk_ms=500, cool_eye=10, **HIGH),
    monster("魔龙力士", 81, 19, 211, speed=18, walk_ms=800, cool_eye=50, **HIGH),
    monster("魔龙战将", 81, 19, 212, speed=17, walk_ms=800, cool_eye=50, **HIGH),
    monster("魔龙教主", 81, 78, 218, lvl=60, hp=20000, ac=30, dc=(250, 350), hit=30, attack_ms=1500, exp=20000,
            speed=25, walk_ms=700, cool_eye=100),
]
# 不刷的（魔龙石碑这期不做；GeeM2 里 1.85 以后的怪 1.76 数据表没有）；「X0」是 GeeM2 的精英批次，并成同名怪
SKIP = {"魔龙石碑"}
# 高级魔龙怪只在魔龙血域（最深一层）刷；外面各图 GeeM2 里零星的巨蛾、龙源血路的力士战将巨蛾都不刷（用户 11-26，#1245）
HIGH_NAMES = {"魔龙巨蛾", "魔龙力士", "魔龙战将"}
HIGH_MAPS = {"66"}

# 魔龙城的 NPC（GeeM2 merchant.txt 的位置和外观）：卖的东西照盟重对应的店（数据表里已有的物品、本服已定的价钱），
# GeeM2 的货单是它自己改过的（祖玛装、1.85 物品），不用。
# (图, x, y, 名字, 外观, [(盟重的图, 店名)] 或 "storage")
MERCHANTS = [
    ("6", 138, 154, "魔龙城杂货店", 17, [("3", "小贩")]),
    ("6", 129, 143, "魔龙城药店", 22, [("3", "药店老板")]),
    ("6", 106, 136, "魔龙城武器店", 28, [("0151", "武器店老板")]),
    ("6", 113, 126, "魔龙城首饰店", 20, [("0154", "项链店老板"), ("0154", "手镯店老板"), ("0154", "戒指店老板")]),
    ("6", 94, 156, "仓库管理员", 17, "storage"),
    ("6", 108, 162, "魔龙城服装店", 20, [("0155", "布衣店老板")]),
    ("63", 254, 40, "向导", 15, [("3", "药店老板")]),
    ("63", 256, 42, "仓库保管员", 5, "storage"),
]

ROUTE = re.compile(r"^\s*(\S+)\s+(\d+)[\s,]+(\d+)\s*->\s*(\S+)\s+(\d+)[\s,]+(\d+)")


def main() -> None:
    geem2 = Path(sys.argv[1]) / "Envir"
    cat = json.loads(CATALOG.read_text())
    ids = {m[0] for m in MAPS}
    files = {i: MapFile.load(map_file(i)) for i in ids}

    def walkable(m: str, x: int, y: int) -> bool:
        f = files[m]
        return 0 <= x < f.width and 0 <= y < f.height and bool(f.walkable[x, y])

    maps = [{"id": i, "asset": i, "title": t, "flags": fl, "width": files[i].width, "height": files[i].height}
            for i, t, fl in MAPS]

    routes = []
    for line in (geem2 / "mapinfo.txt").read_text().splitlines():
        if not (r := ROUTE.match(line)):
            continue
        a, x, y, b, tx, ty = r.groups()
        if a not in ids:
            continue
        x, y, tx, ty = map(int, (x, y, tx, ty))
        a, x, y, b, tx, ty = GATE_FIXES.get((a, x, y), (a, x, y, b, tx, ty))
        if b not in ids or not walkable(a, x, y) or not walkable(b, tx, ty):
            raise SystemExit(f"门不对：{line.strip()}")
        routes.append({"from": a, "x": x, "y": y, "to": b, "tx": tx, "ty": ty})

    known = {m["Name"] for m in cat["monsters"]} | {m["Name"] for m in MONSTERS}
    spawns, skipped = [], set()
    for line in (geem2 / "mongen.txt").read_text().splitlines():
        f = line.split(";")[0].split()
        if len(f) < 7 or f[0] not in ids:
            continue
        name = f[3].removesuffix("0") if f[3].endswith("0") and f[3][:-1] in known else f[3]
        if name in SKIP or name not in known:
            skipped.add(name)
            continue
        if name in HIGH_NAMES and f[0] not in HIGH_MAPS:
            continue
        spawns.append({"map": f[0], "x": int(f[1]), "y": int(f[2]), "monster": name,
                       "radius": int(f[4]), "count": int(f[5]), "minutes": int(f[6])})

    shops = {(m["map"], m["name"]): m for m in cat["merchants"]}
    merchants = []
    for mp, x, y, name, look, src in MERCHANTS:
        if src == "storage":
            merchants.append({"map": mp, "x": x, "y": y, "name": name, "appearance": look, "castle": 0,
                              "service": "storage", "item_modes": [], "goods": []})
            continue
        modes, goods = [], []
        for key in src:
            s = shops[key]
            modes += [m for m in s["item_modes"] if m not in modes]
            goods += [g for g in s["goods"] if g["name"] not in {x["name"] for x in goods}]
        merchants.append({"map": mp, "x": x, "y": y, "name": name, "appearance": look, "castle": 0,
                          "service": "shop", "item_modes": modes, "goods": goods})

    out = {
        "_说明": "1.80 魔龙系列（#1313），由 tools/import_v180.py 生成，格式同 ref/Mir176 catalog.json，见 tools/catalog.py",
        "maps": maps, "routes": routes, "spawns": spawns, "merchants": merchants, "monsters": MONSTERS,
        "minimaps": [{"map": m, "image": i} for m, i in MINIMAPS.items()],
    }
    EXTRA_DIR.mkdir(exist_ok=True)
    (EXTRA_DIR / "v180-molong.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    print(f"地图 {len(maps)}、门 {len(routes)}、刷怪点 {len(spawns)}、NPC {len(merchants)}、怪 {len(MONSTERS)}；"
          f"没刷的怪：{sorted(skipped)}")


if __name__ == "__main__":
    main()
