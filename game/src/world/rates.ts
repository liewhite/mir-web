/**
 * 倍率（用户 11-23 在 #1137 定：大幅降低升级难度（经验 11-23 21:34 再调到 ×100）、大幅提高爆率、装备爆率至少 20%、每只怪能爆什么不变）。
 * 原版数值在 game.json 里不动，只在这里换算。
 */
import type { DropDef, ItemDef, Range, SkillDef } from '../data/defs';
import { itemKind } from './items';

export const RATES = {
  /** 打怪经验倍数 */
  exp: 100,
  /** 非装备物品（金币、药、书、材料）的掉落概率倍数，最高 100% */
  drop: 20,
  /** 高级装备（要求 20 级以上，或要求攻击/魔法/道术）的掉落概率下限 */
  highEquipMin: 0.2,
  highEquipLevel: 20,
  /** 祖玛首饰和头盔（ZUMA_GEAR）不吃保底，掉率封顶 highEquipMin 除以这个数（用户 11-24 在 #1241 定） */
  zumaGearDivisor: 3,
  /** 打死一只怪最多掉几件物品（金币不算，#1241；只在 Scala 版实现） */
  maxDropsPerKill: 10,
  /** 战士的攻击、防御、魔防（上下限都乘，含装备加成，四舍五入）倍数（用户 11-24 在 #1176 定） */
  warriorBody: 1.5,
  /** 技能每级要的熟练度除以这个数（向上取整、至少 1；用户 11-24 在 #1179 定） */
  skillTrain: 10,
} as const;

/** 祖玛首饰和头盔：掉率 = 原版 × drop，封顶 highEquipMin / zumaGearDivisor，不吃保底（#1241）。 */
export const ZUMA_GEAR: ReadonlySet<string> = new Set([
  '绿色项链', '恶魔铃铛', '灵魂项链', '骑士手镯', '龙之手镯', '三眼手镯',
  '力量戒指', '紫碧螺', '泰坦戒指', '心灵手镯', '阎罗手套', '黑铁头盔',
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

/** 一行爆率实际的掉落概率（0..1）。def 为空表示金币。 */
export function dropChance(d: DropDef, def: ItemDef | null): number {
  const base = d.numerator / d.denominator;
  const scaled = Math.min(1, base * RATES.drop);
  if (def && ZUMA_GEAR.has(def.name)) return Math.min(scaled, RATES.highEquipMin / RATES.zumaGearDivisor);
  return def && isHighEquip(def) ? Math.max(scaled, RATES.highEquipMin) : scaled;
}

export function isHighEquip(def: ItemDef): boolean {
  return itemKind(def).kind === 'equip' && (def.need !== 0 || def.needLevel >= RATES.highEquipLevel);
}
