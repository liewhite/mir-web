"""生成 1.80 的爆率补充表 tools/data/v180-drops.json（#1314，结果进仓库，改爆率改这里重跑：python3 import_v180_drops.py）。

爆率按原版写法「1/N」记，游戏里再乘 Rates.drop（20 倍，封顶 100%）；boss 的高级装备另有保底（Rates.bossHighEquipMin）。
用户 11-25 定（#1314）：
- 低级魔龙怪（刀兵、破甲兵、射手）掉祖玛档装备；
- 高级魔龙怪（力士、战将、巨蛾）掉赤月档，另有较低概率掉雷霆档（雷霆、烈焰、光芒）；
- 魔龙教主大概率掉雷霆档，小概率掉终极档。
具体概率用户 11-26 定（#1314，乘 20 倍后每件）：低级怪祖玛档 10%；高级怪赤月档 10%、雷霆档 1%；
教主雷霆档 40%、终极档 10%（boss 保底照旧）；一只最多掉 10 件照旧。
金币和药照 GeeM2 Envir/MonItems 魔龙怪的消耗品部分：1.76 没有的「金创药/魔法药(特量)」换成强效金创药/强效魔法药，
高级怪 GeeM2 每种药各写 4 行，这里每种只留 1 行（每次必掉 16 瓶药太多，地上全是药）；技能书、龙鳞等 1.76 没有的不要。
1.80 的普通腰带、靴子（#1312）加给 1.76 已有的、等级相当的普通怪（drops_append：接在原来的爆率表后面）。
"""
from __future__ import annotations

import json

from build_data import BOSSES
from catalog import CATALOG, EXTRA_DIR
from import_v180 import MONSTERS as MOLONG_MONSTERS


def row(n: int, item: str, amount: int = 1) -> dict:
    """原版「1/n 物品」：数据表写成 10/(10n)，和 1.76 表一样。"""
    return {"item": item, "numerator": 10, "denominator": 10 * n, "amount": amount}


ZUMA = [  # GearTiers 祖玛档
    "绿色项链", "骑士手镯", "力量戒指", "恶魔铃铛", "龙之手镯", "紫碧螺", "灵魂项链", "心灵手镯", "泰坦戒指",
    "阎罗手套", "黑铁头盔", "战神盔甲(男)", "战神盔甲(女)", "恶魔长袍(男)", "恶魔长袍(女)", "幽灵战衣(男)", "幽灵战衣(女)",
]
ZUMA_WEAPONS = ["裁决之杖", "骨玉权杖", "无极棍", "龙纹剑", "血饮"]
REDMOON = [  # GearTiers 赤月档
    "圣战头盔", "圣战项链", "圣战手镯", "圣战戒指", "法神头盔", "法神项链", "法神手镯", "法神戒指",
    "天尊头盔", "天尊项链", "天尊手镯", "天尊戒指", "天魔神甲", "圣战宝甲", "法神披风", "霓裳羽衣", "天尊道袍", "天师长袍",
]
REDMOON_WEAPONS = ["屠龙", "嗜魂法杖", "龙牙", "怒斩", "逍遥扇"]
THUNDER = [
    "雷霆战甲(男)", "雷霆战甲(女)", "雷霆项链", "雷霆护腕", "雷霆战戒", "雷霆腰带", "雷霆战靴",
    "烈焰魔衣(男)", "烈焰魔衣(女)", "烈焰项链", "烈焰护腕", "烈焰魔戒", "烈焰腰带", "烈焰魔靴",
    "光芒道袍(男)", "光芒道袍(女)", "光芒项链", "光芒护腕", "光芒道戒", "光芒腰带", "光芒道靴",
]
ULTIMATE = [
    "开天", "镇天", "玄天", "凤天魔甲", "凰天魔衣",
    "战神项链", "战神手镯", "战神戒指", "圣魔项链", "圣魔手镯", "圣魔戒指", "真魂项链", "真魂手镯", "真魂戒指",
]

LOW = [  # GeeM2 魔龙刀兵/破甲兵/射手的消耗品，加祖玛档
    row(3, "金币", 1000), row(4, "强效金创药"), row(4, "强效魔法药"), row(8, "太阳水"), row(16, "强效太阳水"),
    row(8000, "祝福油"), row(8000, "战神油"),
    *(row(200, n) for n in ZUMA + ZUMA_WEAPONS),
]
HIGH = [  # GeeM2 魔龙力士/战将/巨蛾的消耗品（每种一行），加赤月档和雷霆档
    row(1, "金币", 1000), row(2, "强效太阳水"), row(2, "太阳水"), row(2, "强效金创药"), row(2, "强效魔法药"),
    row(800, "祝福油"), row(800, "战神油"),
    *(row(200, n) for n in REDMOON + REDMOON_WEAPONS), *(row(2000, n) for n in THUNDER),
]
LORD = [  # GeeM2 魔龙教主的消耗品（原样，每种 8 行），加雷霆档和终极档
    row(1, "金币", 30000),
    *(row(1, "强效金创药") for _ in range(8)), *(row(1, "强效魔法药") for _ in range(8)),
    *(row(1, "强效太阳水") for _ in range(8)), *(row(2, "强效太阳水") for _ in range(8)),
    row(2, "祝福油"),
    *(row(50, n) for n in THUNDER), *(row(200, n) for n in ULTIMATE),
]
MOLONG = {"魔龙刀兵": LOW, "魔龙破甲兵": LOW, "魔龙射手": LOW,
          "魔龙力士": HIGH, "魔龙战将": HIGH, "魔龙巨蛾": HIGH, "魔龙教主": LORD}

# 1.80 普通腰带靴子：(物品, 怪物等级下限, 上限)，给 1.76 有爆率表、有刷怪的普通怪，每件 1/2000
BELTS_BOOTS = [
    ("布鞋", 15, 19), ("兽皮腰带", 15, 19),
    ("鹿皮靴", 20, 29), ("铁腰带", 20, 29),
    ("紫绸靴", 30, 39), ("青铜腰带", 30, 39),
    ("钢铁腰带", 40, 49),
]
BELT_BOOT_RATE = 2000
NOT_MONSTERS = {"宝箱", "鹰卫", "虎卫"}  # 石墓的宝箱、守卫不掉


def main() -> None:
    cat = json.loads(CATALOG.read_text())
    assert set(MOLONG) == {m["Name"] for m in MOLONG_MONSTERS}, "魔龙怪名单和 import_v180 对不上"
    spawned = {s["monster"] for s in cat["spawns"]}
    level = {m["Name"]: m["Lvl"] for m in cat["monsters"]}
    append: dict[str, list[dict]] = {}
    for name in sorted(cat["drops"]):
        if name not in spawned or name in BOSSES or name in NOT_MONSTERS:
            continue
        rows = [row(BELT_BOOT_RATE, item) for item, lo, hi in BELTS_BOOTS if lo <= level[name] <= hi]
        if rows:
            append[name] = rows
    out = {
        "_说明": "1.80 爆率（#1314），由 tools/import_v180_drops.py 生成，格式同 ref/Mir176 catalog.json 的 drops，见 tools/catalog.py",
        "drops": MOLONG,
        "drops_append": append,
    }
    path = EXTRA_DIR / "v180-drops.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    print(f"{path.name}：魔龙怪 {len(MOLONG)} 种；普通腰带靴子加给 {len(append)} 种怪")


if __name__ == "__main__":
    main()
