"""生成圣域的补充表 tools/data/shengyu.json（#1332，结果进仓库，改了重跑）。

用户 11-26 定（#1332，「按你推荐的做」）：
- 地图：新的一张「圣域」（编号 SY01），用桃源之门的地图文件 R001（1.76 素材已有）；原来那张桃源之门不动。
  入口：幻境十层右下角小房间里画着的楼梯（旧图牛魔寺庙三层去更深一层的门，幻境里没用上）→ 圣域正中间；
  出口：圣域西边画着的门（旧图桃源之门去困惑殿堂的门）→ 十层楼梯旁。原版要声望、限时 1 小时，都不做；
  和幻境一样不能回城、不进传送菜单。
- 怪：原版圣域掉落表（17173 2005-04-09「勋章暴率的一些看法」）里的「圣域××」，外观和打法照搬 1.76 同名
  或相近的怪（BASE），按原版表分 4 档，数值按档统一（TIERS）；四档算 boss（Rates.bosses）。原版的冥狼、神鹰
  1.76 没有像样的外观，不做。全部刷整张图随机位置。
- 勋章：荣誉勋章 11~45 号（GeeM2 StdItems：StdMode 30、图标 393、要 28 级），十位是档次、个位是属性
  （1 防御、2 魔防、3 攻击、4 魔法、5 道术）；数值是原版的两倍（MEDAL_SPAN）。戴在原版状态窗的照明物格
  （本服叫「勋章」格，EquipSlot.Medal）。
- 爆率：每种怪掉原版表里那个号的勋章，原版写法 1/N（游戏里 ×20，四档算 boss 有 10% 高级装备保底），
  另掉金币和药（同魔龙怪的消耗品，不掉祝福油、战神油：本服没有幸运）。

用法：uv run --no-project --with numpy python import_shengyu.py
"""
from __future__ import annotations

import json

from catalog import CATALOG, EXTRA_DIR, map_file
from wemade import MapFile

MAP_ID, ASSET, TITLE = "SY01", "R001", "圣域"
FLAGS = "NORECALL NORECONNECT(3)"  # 同幻境：不能回城，断线重连回盟重
CENTER = (100, 99)  # 落点：原版石墓七层去桃源之门的落点（地图正中）
# (图, x, y) -> (去的图, x, y)：十层右下角楼梯两格（GeeM2 mapinfo 里 D2073 371,377 / 371,376 两格都是门）、圣域西边的门
ROUTES = [
    ("H010", 371, 377, MAP_ID, *CENTER), ("H010", 371, 376, MAP_ID, *CENTER),
    (MAP_ID, 55, 94, "H010", 370, 376),
]

# 档次 -> (等级, 血, 攻击下限, 上限, 防御=魔防, 经验, 每种只数, 刷新分钟, 原版勋章爆率 1/N)
TIERS = {
    4: (62, 12000, 200, 300, 30, 12000, 1, 60, 500),
    3: (58, 4000, 100, 160, 25, 4000, 3, 30, 300),
    2: (55, 2000, 70, 120, 20, 2000, 6, 10, 200),
    1: (52, 1000, 50, 90, 15, 1200, 12, 10, 100),
}
# 圣域怪 -> (借谁的外观和打法, 档次, 掉几号勋章)；顺序同原版表
BASE = {
    "圣域血魔": ("双头血魔", 4, 41), "圣域金刚": ("双头金刚", 4, 42), "圣域修罗": ("虹魔教主", 4, 42),
    "圣域教主": ("祖玛教主", 4, 43), "圣域魔魂": ("黄泉教主", 4, 44), "圣域牛魔王": ("牛魔王", 4, 45),
    "圣域护法": ("沃玛卫士", 3, 31), "圣域猪卫": ("虹魔猪卫", 3, 31), "圣域蝎卫": ("虹魔蝎卫", 3, 32),
    "圣域月魔": ("月魔蜘蛛", 3, 32), "圣域炎兽": ("火焰沃玛", 3, 33), "圣域蛇王": ("邪恶毒蛇", 3, 34),
    "圣域神龙": ("触龙神", 3, 35),
    "圣域钳虫": ("邪恶钳虫", 2, 21), "圣域花吻": ("花吻蜘蛛", 2, 21), "圣域天狼": ("天狼蜘蛛", 2, 22),
    "圣域楔蛾": ("楔蛾", 2, 22), "圣域杀手": ("暗黑战士", 2, 23), "圣域卫士": ("祖玛卫士", 2, 24),
    "圣域祭司": ("牛魔祭司", 2, 24), "圣域雕像": ("祖玛雕像", 2, 25), "圣域将军": ("牛魔将军", 2, 25),
    "圣域稻草人": ("稻草人", 1, 11), "圣域牛魔": ("牛头魔", 1, 11), "圣域魔蝎": ("蝎子", 1, 12),
    "圣域多钩猫": ("多钩猫", 1, 12), "圣域蝙蝠": ("蝙蝠", 1, 12), "圣域刀斧手": ("骷髅刀斧手", 1, 13),
    "圣域精灵": ("骷髅精灵", 1, 13), "圣域巨虫": ("巨型蠕虫", 1, 14), "圣域侍卫": ("牛魔侍卫", 1, 14),
    "圣域弓箭手": ("骷髅弓箭手", 1, 15), "圣域骷髅": ("骷髅", 1, 15),
}
BOSS_TIER = 4
MIN_HIT = 18  # 准确至少同魔龙怪（蝙蝠、稻草人原来的准确太低，几乎打不中人）

# 勋章：十位档次 -> 属性上下限（原版 0-1、0-2、1-2、1-3 的两倍，用户 11-26 认可）；个位 -> 加在物品表哪一栏
MEDAL_SPAN = {1: (0, 2), 2: (0, 4), 3: (2, 4), 4: (2, 6)}
MEDAL_STAT = {1: ("Ac", "AcMax"), 2: ("Mac", "MacMax"), 3: ("Dc", "DcMax"), 4: ("Mc", "McMax"), 5: ("Sc", "ScMax")}
MEDAL_ID0 = 2100  # 物品编号（1.80 新装备用到 2041）
MEDAL_LOOK = 393  # 1.76 Items 图库的金色奖牌

# 金币和药（同 import_v180_drops 的魔龙怪消耗品，去掉祝福油、战神油）：原版写法 1/N
CONSUMABLES = {
    1: [(3, "金币", 1000), (4, "强效金创药", 1), (4, "强效魔法药", 1), (8, "太阳水", 1), (16, "强效太阳水", 1)],
    2: [(3, "金币", 1000), (4, "强效金创药", 1), (4, "强效魔法药", 1), (8, "太阳水", 1), (16, "强效太阳水", 1)],
    3: [(1, "金币", 1000), (2, "强效太阳水", 1), (2, "太阳水", 1), (2, "强效金创药", 1), (2, "强效魔法药", 1)],
    4: [(1, "金币", 10000), *((1, "强效金创药", 1) for _ in range(2)), *((1, "强效魔法药", 1) for _ in range(2)),
        *((1, "强效太阳水", 1) for _ in range(2))],
}


def row(n: int, item: str, amount: int = 1) -> dict:
    """原版「1/n 物品」：数据表写成 10/(10n)，和 1.76 表一样。"""
    return {"item": item, "numerator": 10, "denominator": 10 * n, "amount": amount}


def medal_name(no: int) -> str:
    return f"荣誉勋章{no}号"


def medals() -> list[dict]:
    out = []
    for k, (tier, kind) in enumerate((t, s) for t in MEDAL_SPAN for s in MEDAL_STAT):
        lo, hi = MEDAL_SPAN[tier]
        stats = {c: 0 for pair in MEDAL_STAT.values() for c in pair}
        stats |= dict(zip(MEDAL_STAT[kind], (lo, hi)))
        out.append({"Id": MEDAL_ID0 + k, "Name": medal_name(tier * 10 + kind), "StdMode": 30, "Shape": 0,
                    "Weight": 1, "AniCount": 0, "Source": 0, "Reserved": 0, "ImgIndex": MEDAL_LOOK,
                    "DuraMax": 50000, **stats, "Need": 0, "NeedLevel": 28, "Price": 1000, "Stock": 5})
    return out


def monsters(cat: dict) -> list[dict]:
    by_name = {m["Name"]: m for m in cat["monsters"]}
    out = []
    for name, (base, tier, _) in BASE.items():
        lvl, hp, dc, dcmax, ac, exp, *_ = TIERS[tier]
        b = by_name[base]
        out.append({k: v for k, v in b.items() if k != "Idx"} | {
            "Name": name, "Lvl": lvl, "HP": hp, "AC": ac, "MAC": ac, "DC": dc, "DCMAX": dcmax, "Exp": exp,
            "HIT": max(b["HIT"], MIN_HIT)})
    return out


def main() -> None:
    cat = json.loads(CATALOG.read_text())
    if missing := sorted({b for b, _, _ in BASE.values()} - {m["Name"] for m in cat["monsters"]}):
        raise SystemExit(f"数据表里没有：{missing}")
    src = next(m for m in cat["maps"] if m["id"] == ASSET)
    w, h = src["width"], src["height"]
    files = {MAP_ID: MapFile.load(map_file(ASSET)), "H010": MapFile.load(map_file("D2073"))}

    def walkable(m: str, x: int, y: int) -> bool:
        f = files[m]
        return 0 <= x < f.width and 0 <= y < f.height and bool(f.walkable[x, y])

    routes = []
    for a, x, y, b, tx, ty in ROUTES:
        if not walkable(a, x, y) or not walkable(b, tx, ty):
            raise SystemExit(f"门不对：{a} {x},{y} -> {b} {tx},{ty}")
        routes.append({"from": a, "x": x, "y": y, "to": b, "tx": tx, "ty": ty})

    spawns = []
    for name, (_, tier, _) in BASE.items():
        *_, count, minutes, _ = TIERS[tier]
        spawns.append({"map": MAP_ID, "x": w // 2, "y": h // 2, "monster": name,
                       "radius": max(w, h) // 2, "count": count, "minutes": minutes})

    drops = {}
    for name, (_, tier, medal) in BASE.items():
        drops[name] = [row(n, item, amount) for n, item, amount in CONSUMABLES[tier]] + [row(TIERS[tier][-1], medal_name(medal))]

    minimap = [{"map": MAP_ID, "image": m["image"]} for m in cat["minimaps"] if m["map"] == ASSET]
    out = {
        "_说明": "圣域（#1332），由 tools/import_shengyu.py 生成，格式同 ref/Mir176 catalog.json，见 tools/catalog.py",
        "maps": [{"id": MAP_ID, "asset": ASSET, "title": TITLE, "flags": FLAGS, "width": w, "height": h}],
        "routes": routes, "spawns": spawns, "minimaps": minimap,
        "monsters": monsters(cat), "items": medals(), "drops": drops,
    }
    EXTRA_DIR.mkdir(exist_ok=True)
    (EXTRA_DIR / "shengyu.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    print(f"怪 {len(BASE)} 种 {sum(s['count'] for s in spawns)} 只、勋章 {len(out['items'])} 种、门 {len(routes)}")


if __name__ == "__main__":
    main()
