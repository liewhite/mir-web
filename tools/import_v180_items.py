"""生成 1.80 新装备的补充表 tools/data/v180-items.json（#1312，结果进仓库，改数值改这里重跑：python3 import_v180_items.py）。

属性：两份 1.80 全装备表（搜狐「热血传奇：最全的装备数据」、网文《我在传奇当大佬》第 3 章「高级装备一览表」）互相核对，
三把武器对过盛大官方白金账号活动页，凤天魔甲对过带截图的文章；对不上的取法是用户 11-26 认可的 #1312 评论：
开天等级 43、玄天准确 2、凤天攻击 2-2、烈焰护腕防 0-2 / 烈焰魔戒防 0-1 / 圣魔戒指魔防 0-1 按有、光芒道靴防 1-3。
不做：玄天神圣 +4（本服没有神圣）、靴子的穿戴负重（本服不设负重）。
「攻击 1-0」这类原样照抄：只加下限（腰带、靴子）。

图标（Items/StateItem 同号）与外观：武器 72/73/74、外观 Shape 34/35/36（百度文库 GEEM2 外观表）；衣服外观 Shape 9（雷霆/烈焰/光芒）、
11（凤天/凰天），图标 869/870 金翼 = 雷霆（另有引擎示例行证实 869）、871/872 红翼 = 烈焰、590/591 银翼 = 光芒、596 = 凤天/凰天（只有一张）。
首饰、腰带、靴子的图标查不到对应，按颜色推定（雷霆白、烈焰红、光芒绿；战神/圣魔/真魂是 1.80 官方 Items 新增的 540–548 三组），
普通腰带靴子 550–553、560–562 与 Crystal 库一致。

物品类别：衣服 10 男 / 11 女，武器 5（法杖 6），项链 20（准确/敏捷）或 19（魔法躲避/幸运），手镯 26（防御）或 24（准确/敏捷），
戒指 22，腰带 54，靴子 52。Need：0 等级、1 攻击、2 魔法、3 道术。
"""
from __future__ import annotations

import json

from catalog import EXTRA_DIR

FIRST_ID = 2000  # 1.76 数据表到 639，补充表从这里起编号

LEVEL, DC, MC, SC = 0, 1, 2, 3


def item(name, mode, look, need, need_level, dura, price, *, shape=0, weight=1,
         ac=(0, 0), mac=(0, 0), dc=(0, 0), mc=(0, 0), sc=(0, 0)) -> dict:
    return {
        "Name": name, "StdMode": mode, "Shape": shape, "Weight": weight, "AniCount": 0, "Source": 0, "Reserved": 0,
        "ImgIndex": look, "DuraMax": dura * 1000,
        "Ac": ac[0], "AcMax": ac[1], "Mac": mac[0], "MacMax": mac[1], "Dc": dc[0], "DcMax": dc[1],
        "Mc": mc[0], "McMax": mc[1], "Sc": sc[0], "ScMax": sc[1],
        "Need": need, "NeedLevel": need_level, "Price": price, "Stock": 5,
    }


def armor(name, look, need_level, dura, weight, **stats) -> list[dict]:
    """男女两件，同属性；name 是 (男名, 女名)，look 是 (男图标, 女图标)。"""
    (m, f), (lm, lf) = name, look
    return [item(m, 10, lm, LEVEL, need_level, dura, 60000, weight=weight, **stats),
            item(f, 11, lf, LEVEL, need_level, dura, 60000, weight=weight, **stats)]


ITEMS: list[dict] = [
    # 雷霆（战士）
    *armor(("雷霆战甲(男)", "雷霆战甲(女)"), (869, 870), 42, 60, 60, shape=9, ac=(5, 12), mac=(4, 8), dc=(1, 3)),
    item("雷霆项链", 20, 781, DC, 45, 7, 30000, dc=(3, 7)),
    item("雷霆护腕", 26, 766, DC, 45, 7, 30000, ac=(0, 2), mac=(0, 2), dc=(1, 4)),
    item("雷霆战戒", 22, 773, DC, 45, 7, 30000, dc=(0, 8)),
    item("雷霆腰带", 54, 761, DC, 45, 7, 30000, weight=2, ac=(2, 3), mac=(0, 1), dc=(1, 0)),
    item("雷霆战靴", 52, 752, DC, 45, 7, 30000, weight=2, ac=(0, 3), dc=(1, 0)),
    # 烈焰（法师）
    *armor(("烈焰魔衣(男)", "烈焰魔衣(女)"), (871, 872), 42, 40, 20, shape=9, ac=(5, 12), mac=(4, 8), mc=(3, 5)),
    item("烈焰项链", 20, 775, MC, 30, 5, 30000, ac=(0, 3), mc=(1, 9)),
    item("烈焰护腕", 26, 762, MC, 30, 5, 30000, ac=(0, 2), mc=(2, 4)),
    item("烈焰魔戒", 22, 770, MC, 30, 5, 30000, ac=(0, 1), mc=(1, 7)),
    item("烈焰腰带", 54, 759, MC, 30, 5, 30000, ac=(2, 2), mac=(0, 1), mc=(1, 0)),
    item("烈焰魔靴", 52, 750, MC, 30, 5, 30000, ac=(0, 3), mc=(1, 0)),
    # 光芒（道士）
    *armor(("光芒道袍(男)", "光芒道袍(女)"), (590, 591), 42, 50, 35, shape=9, ac=(5, 12), mac=(4, 8), sc=(3, 5)),
    item("光芒项链", 20, 779, SC, 28, 6, 30000, ac=(0, 3), sc=(2, 8)),
    item("光芒护腕", 26, 764, SC, 28, 6, 30000, ac=(1, 3), sc=(2, 4)),
    item("光芒道戒", 22, 772, SC, 28, 6, 30000, ac=(0, 2), sc=(1, 8)),
    item("光芒腰带", 54, 760, SC, 28, 6, 30000, weight=2, ac=(2, 2), mac=(0, 1), sc=(1, 0)),
    item("光芒道靴", 52, 751, SC, 28, 6, 30000, ac=(1, 3), sc=(1, 0)),
    # 终极：三把武器（5、6 号的 AC 上限是准确）
    item("开天", 5, 72, LEVEL, 43, 35, 100000, shape=34, weight=73, dc=(6, 40)),
    item("镇天", 6, 73, LEVEL, 43, 20, 100000, shape=35, weight=26, dc=(7, 25), mc=(5, 12)),
    item("玄天", 5, 74, LEVEL, 43, 25, 100000, shape=36, weight=44, ac=(0, 2), dc=(8, 31), sc=(5, 12)),
    *armor(("凤天魔甲", "凰天魔衣"), (596, 596), 45, 50, 23, shape=11,
           ac=(8, 12), mac=(8, 8), dc=(2, 2), mc=(3, 5), sc=(3, 5)),
    # 战神 / 圣魔 / 真魂：19 号项链 AC 上限是魔法躲避（×10%）、MAC 上限是幸运；20、24 号 AC 上限准确、MAC 上限敏捷
    item("战神项链", 19, 540, DC, 54, 8, 50000, weight=3, mac=(0, 1), dc=(3, 8)),
    item("战神手镯", 26, 541, DC, 54, 8, 50000, weight=2, ac=(0, 1), dc=(1, 5)),
    item("战神戒指", 22, 542, DC, 54, 8, 50000, weight=2, mac=(0, 1), dc=(0, 9)),
    item("圣魔项链", 20, 543, MC, 32, 6, 50000, mac=(0, 3), mc=(0, 9)),
    item("圣魔手镯", 26, 544, MC, 32, 6, 50000, ac=(0, 2), mac=(0, 2), mc=(1, 5)),
    item("圣魔戒指", 22, 545, MC, 32, 6, 50000, mac=(0, 1), mc=(2, 8)),
    item("真魂项链", 19, 546, SC, 30, 7, 50000, weight=2, ac=(0, 2), sc=(1, 8)),
    item("真魂手镯", 24, 547, SC, 30, 7, 50000, mac=(0, 2), sc=(1, 5)),
    item("真魂戒指", 22, 548, SC, 30, 7, 50000, mac=(0, 1), sc=(2, 9)),
    # 普通腰带、靴子（用户 11-26 #1312 定一起加）
    item("布鞋", 52, 560, LEVEL, 15, 6, 1000, ac=(0, 1)),
    item("鹿皮靴", 52, 561, LEVEL, 22, 6, 3000, ac=(0, 2)),
    item("紫绸靴", 52, 562, LEVEL, 30, 8, 8000, ac=(0, 3)),
    item("兽皮腰带", 54, 550, LEVEL, 15, 6, 1000, ac=(0, 1)),
    item("铁腰带", 54, 551, LEVEL, 22, 6, 3000, ac=(0, 2)),
    item("青铜腰带", 54, 552, LEVEL, 30, 8, 8000, ac=(0, 3)),
    item("钢铁腰带", 54, 553, LEVEL, 35, 10, 15000, ac=(0, 3), mac=(0, 1)),
]


def main() -> None:
    items = [{"Id": FIRST_ID + i} | it for i, it in enumerate(ITEMS)]
    out = {"_说明": "1.80 新装备（#1312），由 tools/import_v180_items.py 生成，格式同 ref/Mir176 catalog.json，见 tools/catalog.py",
           "items": items}
    path = EXTRA_DIR / "v180-items.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    print(path, len(items), "件")


if __name__ == "__main__":
    main()
