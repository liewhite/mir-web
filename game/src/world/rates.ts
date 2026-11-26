/**
 * 倍率（用户 11-23 在 #1137 定：大幅降低升级难度（经验 11-23 21:34 再调到 ×100）、大幅提高爆率、每只怪能爆什么不变；爆率 11-24 在 #1241 改定）。
 * 原版数值在 game.json 里不动，只在这里换算。
 */
import type { DropDef, ItemDef, Range, SkillDef } from '../data/defs';
import { itemKind } from './items';

export const RATES = {
  /** 打怪经验倍数 */
  exp: 100,
  /** 掉落概率倍数（所有怪、所有物品），最高 100% */
  drop: 20,
  /** boss（BOSSES）掉高级装备（要求 20 级以上，或要求攻击/魔法/道术）的概率下限；普通怪没有保底（#1241） */
  bossHighEquipMin: 0.1,
  highEquipLevel: 20,
  /** 打死一只怪最多掉几件物品（金币不算，#1241；只在 Scala 版实现） */
  maxDropsPerKill: 10,
  /** boss（BOSSES）刷新间隔最长几分钟，刷怪表里更短的不变（用户 11-25 在 #1262 定；只在 Scala 版实现） */
  bossRespawnMaxMinutes: 120,
  /** 战士的攻击、防御、魔防（上下限都乘，含装备加成，四舍五入）倍数（用户 11-24 在 #1176 定 1.5，11-25 在 #1285 提到 1.8） */
  warriorBody: 1.8,
  /** 技能每级要的熟练度除以这个数（向上取整、至少 1；用户 11-24 在 #1179 定） */
  skillTrain: 10,
} as const;

/** 爆率算 boss 的怪（用户 11-24 在 #1241 定手写名单，和 Scala 版 Rates.bosses 一致）。 */
export const BOSSES: ReadonlySet<string> = new Set([
  '沃玛教主', '祖玛教主', '千年树妖', '石墓尸王', '牛魔王', '赤月恶魔', '双头金刚', '双头血魔', '黄泉教主', '虹魔教主',
  '暗之沃玛教主', '暗之牛魔王', '暗之双头金刚', '暗之双头血魔', '暗之黄泉教主', '暗之虹魔教主',
  '魔龙教主', '暗之魔龙教主', '暗之赤月恶魔',
]);

/** 技能从 level（0..2）升到 level+1 实际要的熟练度：数据表数值 ÷ skillTrain。 */
export function trainNeed(def: SkillDef, level: number): number {
  return Math.max(1, Math.ceil(def.train[level]! / RATES.skillTrain));
}

/** 职业倍率作用到一段属性区间上（只有战士 job=0 有）。 */
export function jobRange(job: number, r: Range): Range {
  if (job !== 0) return r;
  const scale = (v: number) => Math.floor(v * RATES.warriorBody + 0.5);
  return [scale(r[0]), scale(r[1])];
}

/** 一行爆率实际的掉落概率（0..1）：原版 × drop；boss 的高级装备至少 bossHighEquipMin。def 为空表示金币。 */
export function dropChance(monster: string, d: DropDef, def: ItemDef | null): number {
  const scaled = Math.min(1, (d.numerator / d.denominator) * RATES.drop);
  return BOSSES.has(monster) && def && isHighEquip(def) ? Math.max(scaled, RATES.bossHighEquipMin) : scaled;
}

export function isHighEquip(def: ItemDef): boolean {
  return itemKind(def).kind === 'equip' && (def.need !== 0 || def.needLevel >= RATES.highEquipLevel);
}
