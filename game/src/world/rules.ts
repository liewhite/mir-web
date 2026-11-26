/**
 * 数值规则：全部照 1.76 服务端（ref/Mir176 OpenMir2）的公式，出处写在每个函数上。
 * 只做计算，不改任何状态。
 */
import type { Range } from '../data/defs';
import type { ItemEffects } from './items';
import { jobRange, RATES } from './rates';

export type Job = 0 | 1 | 2; // 战士 法师 道士
export const JOB_NAME = ['战士', '法师', '道士'] as const;

/** Random(n)：0..n-1；n ≤ 0 时为 0。 */
export function random(n: number): number {
  return n > 0 ? Math.floor(Math.random() * n) : 0;
}

/** 在区间里取一个值：lo + Random(hi - lo + 1)。 */
export function roll(r: Range): number {
  const [lo, hi] = r;
  return lo + random(Math.abs(hi - lo) + 1);
}

const round = (x: number): number => Math.round(x);

/** 等级带来的基础属性（PlayObject.RecalcLevelAbilitys，配置取默认值）。 */
export interface LevelAbility {
  maxHp: number;
  maxMp: number;
  dc: Range;
  mc: Range;
  sc: Range;
  ac: Range;
  mac: Range;
  maxBagWeight: number;
  maxWearWeight: number;
  maxHandWeight: number;
}

export function levelAbility(job: Job, lv: number): LevelAbility {
  const n7 = Math.floor(lv / 7);
  switch (job) {
    case 2: {
      const n6 = round(lv / 6);
      return {
        maxHp: 14 + round((lv / 6 + 2.5) * lv),
        maxMp: 13 + round((lv / 8) * 2.2 * lv),
        maxBagWeight: 50 + round((lv / 4) * lv),
        maxWearWeight: Math.min(255, 15 + round((lv / 50) * lv)),
        maxHandWeight: Math.min(255, 12 + round((lv / 42) * lv)),
        dc: [Math.max(n7 - 1, 0), Math.max(1, n7)],
        mc: [0, 0],
        sc: [Math.max(n7 - 1, 0), Math.max(1, n7)],
        ac: [0, 0],
        mac: [Math.floor(n6 / 2), n6 + 1],
      };
    }
    case 1:
      return {
        maxHp: 14 + round((lv / 15 + 1.8) * lv),
        maxMp: 13 + round((lv / 5 + 2) * 2.2 * lv),
        maxBagWeight: 50 + round((lv / 5) * lv),
        maxWearWeight: Math.min(255, 15 + round((lv / 100) * lv)),
        maxHandWeight: Math.min(255, 12 + round((lv / 90) * lv)),
        dc: [Math.max(n7 - 1, 0), Math.max(1, n7)],
        mc: [Math.max(n7 - 1, 0), Math.max(1, n7)],
        sc: [0, 0],
        ac: [0, 0],
        mac: [0, 0],
      };
    case 0:
      return {
        maxHp: 14 + round((lv / 4 + 4.5 + lv / 20) * lv),
        maxMp: 11 + round(lv * 3.5),
        maxBagWeight: 50 + round((lv / 3) * lv),
        maxWearWeight: Math.min(255, 15 + round((lv / 20) * lv)),
        maxHandWeight: Math.min(255, 12 + round((lv / 13) * lv)),
        dc: [Math.max(Math.floor(lv / 5) - 1, 1), Math.max(1, Math.floor(lv / 5))],
        mc: [0, 0],
        sc: [0, 0],
        ac: [0, n7],
        mac: [0, 0],
      };
  }
}

const addR = (a: Range, b: Range): Range => [Math.min(255, a[0] + b[0]), Math.min(255, a[1] + b[1])];

/** 人物最终属性。 */
export interface Stats {
  maxHp: number;
  maxMp: number;
  ac: Range;
  mac: Range;
  dc: Range;
  mc: Range;
  sc: Range;
  hit: number;
  agility: number;
  /** 两次普通攻击的最短间隔（毫秒） */
  hitInterval: number;
  luck: number;
  magicAvoid: number;
  poisonAvoid: number;
  hpRegen: number;
  mpRegen: number;
  maxBagWeight: number;
  maxWearWeight: number;
  maxHandWeight: number;
}

export interface PassiveSkills {
  /** 基本剑术、精神力战法、攻杀剑术 的等级（没学为 -1） */
  oneSword: number;
  spiritSword: number;
  powerHit: number;
}

/**
 * 汇总：等级属性 + 装备 + 被动技能。
 * 准确默认 5、敏捷默认 15（道士 +3）（PlayObject.RecalcHitSpeed）。
 * 攻速：装备攻速合计 x，x>0 取 x/2，否则 (x-1)/2，战士再 +RATES.warriorHitSpeed（#1349），上限 15；攻击间隔 = 900 - 攻速×60。
 */
export function computeStats(job: Job, level: number, equips: ItemEffects[], passive: PassiveSkills): Stats {
  const base = levelAbility(job, level);
  let s: Stats = {
    maxHp: base.maxHp, maxMp: base.maxMp, ac: base.ac, mac: base.mac, dc: base.dc, mc: base.mc, sc: base.sc,
    hit: 5, agility: job === 2 ? 18 : 15, hitInterval: 900, luck: 0, magicAvoid: 0, poisonAvoid: 0,
    hpRegen: 0, mpRegen: 0,
    maxBagWeight: base.maxBagWeight, maxWearWeight: base.maxWearWeight, maxHandWeight: base.maxHandWeight,
  };
  let speed = 0;
  for (const e of equips) {
    s = {
      ...s,
      ac: addR(s.ac, e.ac), mac: addR(s.mac, e.mac), dc: addR(s.dc, e.dc), mc: addR(s.mc, e.mc), sc: addR(s.sc, e.sc),
      hit: s.hit + e.hit, agility: s.agility + e.agility, luck: s.luck + e.luck - e.curse,
      magicAvoid: s.magicAvoid + e.magicAvoid, poisonAvoid: s.poisonAvoid + e.poisonAvoid,
      hpRegen: s.hpRegen + e.hpRegen, mpRegen: s.mpRegen + e.mpRegen,
    };
    speed += e.attackSpeed;
  }
  if (passive.oneSword > 0) s.hit += round((9 / 3) * passive.oneSword);
  if (passive.spiritSword > 0) s.hit += round((8 / 3) * passive.spiritSword);
  if (passive.powerHit > 0) s.hit += passive.powerHit;
  const jobSpeed = job === 0 ? RATES.warriorHitSpeed : 0;
  const hitSpeed = Math.min(15, (speed > 0 ? Math.floor(speed / 2) : Math.trunc((speed - 1) / 2)) + jobSpeed);
  s.hitInterval = Math.max(300, 900 - hitSpeed * 60);
  s.dc = jobRange(job, s.dc);
  s.ac = jobRange(job, s.ac);
  s.mac = jobRange(job, s.mac);
  return s;
}

/** 命中判定：攻方准确 < Random(守方敏捷) 时打空（BaseObject._Attack）。 */
export function hits(attackerHit: number, defenderAgility: number): boolean {
  return !(attackerHit < random(defenderAgility));
}

/** 物理伤害被防御抵消后的值（BaseObject.GetHitStruckDamage）。 */
export function afterArmor(power: number, ac: Range): number {
  return Math.max(0, power - roll(ac));
}

/** 魔法伤害被魔防抵消后的值（GetMagStruckDamage）。 */
export function afterMagicArmor(power: number, mac: Range): number {
  return Math.max(0, power - roll(mac));
}

/**
 * 命中概率（给属性面板用的说明值）：攻方准确 h，守方敏捷 a，
 * 打空当且仅当 h < Random(a)，Random(a) 均匀取 0..a-1，所以命中率 = min(1, (h+1)/a)。
 */
export function hitChance(h: number, a: number): number {
  return a <= 0 ? 1 : Math.min(1, (h + 1) / a);
}

/** 技能威力（MagicManager.GetPower）：按技能等级放大的基础威力 + 默认威力随机部分。 */
export function skillPower(base: number, level: number, defPower: Range): number {
  return round((base / 4) * (level + 1)) + defPower[0] + random(defPower[1] - defPower[0]);
}

/** MagicManager.GetPower13 */
export function skillPower13(n: number, level: number, defPower: Range): number {
  const d10 = n / 3;
  const d18 = n - d10;
  return round((d18 / 4) * (level + 1) + d10 + (defPower[0] + random(defPower[1] - defPower[0])));
}

/** MagicManager.MPow */
export function magicBase(power: Range): number {
  return power[0] + random(power[1] - power[0]);
}

/** GetRPow：区间里随机一个值 */
export function rpow(r: Range): number {
  return r[1] > r[0] ? random(r[1] - r[0] + 1) + r[0] : r[0];
}

/** GetAttackPower(base, spread)：base + Random(spread + 1) */
export function attackPower(base: number, spread: number): number {
  return base + random(Math.max(0, spread) + 1);
}
