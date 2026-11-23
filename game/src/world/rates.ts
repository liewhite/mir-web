/**
 * 倍率（用户 11-23 在 #1137 定：大幅降低升级难度、大幅提高爆率、装备爆率至少 20%、每只怪能爆什么不变）。
 * 原版数值在 game.json 里不动，只在这里换算。
 */
import type { DropDef, ItemDef } from '../data/defs';
import { itemKind } from './items';

export const RATES = {
  /** 打怪经验倍数 */
  exp: 10,
  /** 非装备物品（金币、药、书、材料）的掉落概率倍数，最高 100% */
  drop: 20,
  /** 高级装备（要求 20 级以上，或要求攻击/魔法/道术）的掉落概率下限 */
  highEquipMin: 0.2,
  highEquipLevel: 20,
} as const;

/** 一行爆率实际的掉落概率（0..1）。def 为空表示金币。 */
export function dropChance(d: DropDef, def: ItemDef | null): number {
  const base = d.numerator / d.denominator;
  const scaled = Math.min(1, base * RATES.drop);
  return def && isHighEquip(def) ? Math.max(scaled, RATES.highEquipMin) : scaled;
}

export function isHighEquip(def: ItemDef): boolean {
  return itemKind(def).kind === 'equip' && (def.need !== 0 || def.needLevel >= RATES.highEquipLevel);
}
