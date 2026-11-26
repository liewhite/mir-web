"""生成幻境一层～十层的补充表 tools/data/huanjing.json（#1317，结果进仓库，改了重跑）。

幻境（2004 年 4 月加入，1.76 数据表里没有）的 10 张地图在 1.80 官方客户端里是 H001～H010，
逐字节等于下面 LAYERS 里的旧图，所以 asset 直接指向旧图，不加素材。
- 门：GitHub dreamHeroK/Mir200 的 Envir/MapInfo.txt（和 2004 年攻略、旧图楼梯口对得上），抄在 ROUTES。
  七层去八层原版要穿过「幻境迷宫」，迷宫地图和走法找不到原版数据（用户 11-25 定：迷宫要做就和原版一样），
  这里照那份 MapInfo 直接通八层；三层去四层原版找幻境向导对话、六层去七层原版踩石墓尸王的坑，都做成普通的门。
  六层那扇门地上没画东西，在门上补画尸王钻出来的坑（LOOKS，#1322）。
- 刷怪：没有原版刷怪表（用户 11-26 认可的做法，#1245/#1317）：沿用旧图原来的刷怪点（位置、范围、数量、间隔），
  怪换成攻略里那层的怪（旧图本来就刷的照旧，别的按顺序轮换）；旧图的 boss 和不在名单里的怪都换掉。
  小 boss（攻略写的）和每层另加的 2 个 boss 刷在整张图随机位置。数值、爆率都用数据表里同名怪的。
- 不做双倍经验/爆率（用户 11-26）。入口在传送菜单（build_data.py ENTRIES），一层出口回盟重。
- 十层另刷 1.80 的魔龙教主和本服加的暗之魔龙教主（用户 11-26，#1329），暗之魔龙教主定义在 DARK_LORD；
  另刷本服加的暗之赤月恶魔（用户 11-26，#1331），定义在 DARK_MOON；
  还另刷少量高级魔龙怪和一些普通魔龙怪（用户 11-26，#1330），见 EXTRA。

用法：uv run --no-project --with numpy python import_huanjing.py
"""
from __future__ import annotations

import json

from catalog import CATALOG, EXTRA_DIR, map_file
from import_v180 import MONSTERS as MOLONG_MONSTERS
from wemade import MapFile


def _dark_lord() -> dict:
    """暗之魔龙教主（用户 11-26 定，#1329）：血 32000、攻击上下限是魔龙教主的 1.5 倍，其余（含外观、经验）同魔龙教主。
    1.80 素材和 GeeM2 数据库里都没有这只怪，外观用魔龙教主的；爆率同魔龙教主（import_v180_drops.py）。"""
    lord = next(m for m in MOLONG_MONSTERS if m["Name"] == "魔龙教主")
    return lord | {"Name": "暗之魔龙教主", "HP": 32000,
                   "DC": round(lord["DC"] * 1.5), "DCMAX": round(lord["DCMAX"] * 1.5)}


DARK_LORD = _dark_lord()


def _dark_moon() -> dict:
    """暗之赤月恶魔（用户 11-26 定，#1331）：血 20000，其余（外观、原地不动打周围一圈、防御、经验）同 1.76 数据表的赤月恶魔；
    爆率同魔龙教主（import_v180_drops.py）。
    攻击 130~170（平均 150）：用户 11-26 在 #1340 定「削弱一点，平均攻击 150 左右」
    （#1331 原定魔龙教主的 80% 即 200~280，45 级道士两下就死）。"""
    moon = next(m for m in json.loads(CATALOG.read_text())["monsters"] if m["Name"] == "赤月恶魔")
    return {k: v for k, v in moon.items() if k != "Idx"} | {"Name": "暗之赤月恶魔", "HP": 20000,
                   "DC": 130, "DCMAX": 170}


DARK_MOON = _dark_moon()
# 本服加的、数据表里没有的怪，爆率都同魔龙教主
DARK_BOSSES = [DARK_LORD, DARK_MOON]

# 编号、标题、用的旧图、这层的普通怪、这层的小 boss/boss（攻略写的 + 每层另加的 2 个），每项 (怪, 只数, 分钟)
LAYERS = [
    ("H001", "幻境一层", "E602", ["蜈蚣", "恶灵僵尸", "恶灵尸王", "钳虫", "跳跳蜂", "黑色恶蛆"],
     [("骷髅精灵", 2, 30), ("尸王", 2, 30), ("石墓尸王", 1, 90), ("黄泉教主", 1, 90)]),
    ("H002", "幻境二层", "D711", ["红野猪", "黑野猪", "楔蛾", "蝎蛇", "黑色恶蛆"],
     [("石墓尸王", 1, 90), ("千年树妖", 1, 90)]),
    ("H003", "幻境三层", "D024", ["沃玛战士", "沃玛勇士", "沃玛战将", "火焰沃玛"],
     [("沃玛卫士", 2, 60), ("沃玛教主", 1, 90), ("暗之沃玛教主", 1, 90)]),
    ("H004", "幻境四层", "D606", ["蜈蚣", "钳虫", "巨型蠕虫", "跳跳蜂", "黑色恶蛆"],
     [("邪恶钳虫", 1, 20), ("触龙神", 1, 20), ("祖玛教主", 1, 90), ("暗之虹魔教主", 1, 90)]),
    ("H005", "幻境五层", "D2053", ["骷髅长枪兵", "骷髅锤兵", "骷髅刀斧手", "骷髅弓箭手"],
     [("黄泉教主", 1, 120), ("暗之黄泉教主", 1, 90)]),
    ("H006", "幻境六层", "D717", ["黑色恶蛆", "楔蛾", "蝎蛇", "白野猪"],
     [("邪恶毒蛇", 1, 20), ("石墓尸王", 1, 30), ("双头金刚", 1, 90), ("双头血魔", 1, 90)]),
    ("H007", "幻境七层", "D505", ["祖玛弓箭手", "祖玛雕像", "祖玛卫士", "楔蛾", "角蝇"],
     [("祖玛教主", 1, 90), ("暗之双头金刚", 1, 90)]),
    ("H008", "幻境八层", "D2013", ["祖玛弓箭手", "祖玛雕像", "祖玛卫士", "楔蛾", "角蝇"],
     [("虹魔蝎卫", 2, 30), ("虹魔教主", 1, 30), ("暗之虹魔教主", 1, 90)]),
    ("H009", "幻境九层", "D10031", ["天狼蜘蛛", "钢牙蜘蛛", "花吻蜘蛛", "月魔蜘蛛", "暴牙蜘蛛", "黑锷蜘蛛"],
     [("双头金刚", 1, 120), ("双头血魔", 1, 120), ("暗之双头血魔", 1, 90)]),
    ("H010", "幻境十层", "D2073", ["牛头魔", "牛魔斗士", "牛魔战士", "牛魔侍卫", "牛魔法师"],
     [("赤月恶魔", 1, 120), ("牛魔王", 1, 120), ("暗之牛魔王", 1, 90), ("暗之双头金刚", 1, 90),
      ("魔龙教主", 1, 120), ("暗之魔龙教主", 1, 120),  # 用户 11-26 加（#1329）
      ("暗之赤月恶魔", 1, 120)]),  # 用户 11-26 加（#1331）
]
# 每层另加的怪（不算 boss，也刷整张图随机位置）：(怪, 只数, 分钟)
# 十层（用户 11-26「刷少量魔龙高级怪，刷一些魔龙普通怪」，#1330）：高级怪同龙源血路每种 3 只、间隔照 GeeM2；
# 普通怪每种 30 只、10 分钟（十层牛魔小怪 268 只，魔龙普通怪约占三分之一，不挤掉牛魔怪）。
EXTRA = {
    "H010": [("魔龙巨蛾", 3, 120), ("魔龙力士", 3, 240), ("魔龙战将", 3, 240),
             ("魔龙刀兵", 30, 10), ("魔龙破甲兵", 30, 10), ("魔龙射手", 30, 10)],
}
# 地图标记：照那份 MapInfo（不能回城），断线重连回盟重
FLAGS = "NORECALL NORECONNECT(3)"
# (图, x, y) -> (去的图, x, y)；dreamHeroK/Mir200 MapInfo 原样，另加：一层出口回盟重（旧图那扇门的位置），
# 八层回七层（MapInfo 没写，用封魔殿原来回炼狱回廊的门）
ROUTES = [
    ("H001", 72, 65, "3", 350, 340),
    ("H001", 10, 9, "H002", 205, 198), ("H002", 205, 197, "H001", 11, 10),
    ("H002", 364, 229, "H003", 16, 19), ("H003", 16, 18, "H002", 363, 230),
    ("H003", 88, 81, "H004", 9, 27), ("H004", 9, 25, "H003", 87, 81),
    ("H004", 193, 124, "H005", 193, 279), ("H005", 193, 280, "H004", 192, 123),
    ("H005", 36, 225, "H006", 26, 74), ("H006", 25, 75, "H005", 38, 227),
    ("H006", 83, 25, "H007", 161, 172), ("H007", 5, 102, "H006", 26, 74),
    ("H007", 123, 10, "H008", 79, 80), ("H008", 82, 83, "H007", 122, 11),
    ("H008", 21, 25, "H009", 289, 150), ("H009", 290, 151, "H008", 22, 25),
    ("H009", 149, 103, "H010", 155, 157), ("H010", 155, 156, "H009", 151, 105),
]
# 门那格补画的样子（地图里没画门的，取值见 scala mir.data.GateLook）：(图, x, y) -> 样子
LOOKS = {("H006", 83, 25): "zombie-pit"}


def main() -> None:
    cat = json.loads(CATALOG.read_text())
    known = {m["Name"] for m in cat["monsters"] + MOLONG_MONSTERS + DARK_BOSSES}
    titles = {m["id"]: m for m in cat["maps"]}
    files = {h: MapFile.load(map_file(old)) for h, _, old, _, _ in LAYERS} | {"3": MapFile.load(map_file("3"))}

    def walkable(m: str, x: int, y: int) -> bool:
        f = files[m]
        return 0 <= x < f.width and 0 <= y < f.height and bool(f.walkable[x, y])

    maps, spawns, minimaps = [], [], []
    old_minimap = {m["map"]: m["image"] for m in cat["minimaps"]}
    for h, title, old, common, bosses in LAYERS:
        scattered = bosses + EXTRA.get(h, [])
        if missing := [n for n in common + [b[0] for b in scattered] if n not in known]:
            raise SystemExit(f"{h} 的怪数据表里没有：{missing}")
        w, ht = titles[old]["width"], titles[old]["height"]
        maps.append({"id": h, "asset": old, "title": title, "flags": FLAGS, "width": w, "height": ht})
        if old in old_minimap:
            minimaps.append({"map": h, "image": old_minimap[old]})
        turn = 0
        for s in (s for s in cat["spawns"] if s["map"] == old):
            name = s["monster"]
            if name not in common:
                name = common[turn % len(common)]
                turn += 1
            spawns.append(s | {"map": h, "monster": name})
        for name, count, minutes in scattered:  # 整张图随机位置
            spawns.append({"map": h, "x": w // 2, "y": ht // 2, "monster": name,
                           "radius": max(w, ht) // 2, "count": count, "minutes": minutes})

    routes = []
    for a, x, y, b, tx, ty in ROUTES:
        if not walkable(a, x, y) or not walkable(b, tx, ty):
            raise SystemExit(f"门不对：{a} {x},{y} -> {b} {tx},{ty}")
        look = {"look": LOOKS[(a, x, y)]} if (a, x, y) in LOOKS else {}
        routes.append({"from": a, "x": x, "y": y, "to": b, "tx": tx, "ty": ty} | look)
    if stray := set(LOOKS) - {(a, x, y) for a, x, y, *_ in ROUTES}:
        raise SystemExit(f"LOOKS 对不上门：{sorted(stray)}")

    out = {
        "_说明": "幻境一层～十层（#1317），由 tools/import_huanjing.py 生成，格式同 ref/Mir176 catalog.json，见 tools/catalog.py",
        "maps": maps, "routes": routes, "spawns": spawns, "minimaps": minimaps, "monsters": DARK_BOSSES,
    }
    EXTRA_DIR.mkdir(exist_ok=True)
    (EXTRA_DIR / "huanjing.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    print(f"地图 {len(maps)}、门 {len(routes)}、刷怪点 {len(spawns)}、小地图 {len(minimaps)}、怪 {len(DARK_BOSSES)}")


if __name__ == "__main__":
    main()
