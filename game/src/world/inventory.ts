/**
 * 身上（包裹 40 格 + 快捷栏 6 格）和仓库里物品的进出（#1159）：
 * 可叠的（items.ts stacks）同名并成一叠、不设上限；格子按「格」算满，重量按个数算（一叠 10 瓶药重 10 瓶）。
 * 用一个只减数量；卖、存、取可以只动一叠里的几个（分出一件新的）。
 */
import type { Catalog, ItemDef } from '../data/defs';
import type { Player } from './entities';
import { type ItemInstance, itemKind, splitOff, stacks } from './items';

export interface Carried {
  where: 'bag' | 'belt';
  index: number;
  item: ItemInstance;
}

/** 药品、卷轴可以放快捷栏 */
export function beltable(d: ItemDef): boolean {
  const k = itemKind(d).kind;
  return k === 'potion' || k === 'scroll';
}

export function find(p: Player, uid: number): Carried | null {
  let i = p.bag.findIndex((x) => x?.uid === uid);
  if (i >= 0) return { where: 'bag', index: i, item: p.bag[i]! };
  i = p.belt.findIndex((x) => x?.uid === uid);
  if (i >= 0) return { where: 'belt', index: i, item: p.belt[i]! };
  return null;
}

/** 身上已有的同名一叠（快捷栏优先）；不可叠的物品没有「一叠」。 */
function stackOf(d: ItemDef, p: Player): ItemInstance | null {
  if (!stacks(d)) return null;
  return [...p.belt, ...p.bag].find((x) => x?.name === d.name) ?? null;
}

/** 空位：药品、卷轴先快捷栏，再包裹。 */
function freeSlot(d: ItemDef, p: Player): { where: 'bag' | 'belt'; index: number } | null {
  const b = beltable(d) ? p.belt.indexOf(null) : -1;
  if (b >= 0) return { where: 'belt', index: b };
  const i = p.bag.indexOf(null);
  return i >= 0 ? { where: 'bag', index: i } : null;
}

/** 格子放不放得下（并进已有的一叠也算放得下）；重量另算。 */
export function hasRoom(d: ItemDef, p: Player): boolean {
  return stackOf(d, p) !== null || freeSlot(d, p) !== null;
}

/** 放到身上：并进同名一叠，否则找空位。放不下返回 false（物品不动）。 */
export function add(catalog: Catalog, p: Player, it: ItemInstance): boolean {
  const d = catalog.item(it.name);
  const s = stackOf(d, p);
  if (s) {
    s.count += it.count;
    return true;
  }
  const slot = freeSlot(d, p);
  if (!slot) return false;
  (slot.where === 'bag' ? p.bag : p.belt)[slot.index] = it;
  return true;
}

/** 从一组物品里拿走 n 个（默认整件）：整件拿走返回原物，拿一部分返回分出来的新件。 */
function takeFrom(it: ItemInstance, n: number | undefined, remove: () => void): ItemInstance {
  const k = Math.min(it.count, Math.max(1, n ?? it.count));
  if (k >= it.count) {
    remove();
    return it;
  }
  it.count -= k;
  return splitOff(it, k);
}

/** 从身上（包裹或快捷栏）拿走 n 个（默认整件）；没有这件返回 null。 */
export function take(p: Player, uid: number, n?: number): ItemInstance | null {
  const f = find(p, uid);
  if (!f) return null;
  return takeFrom(f.item, n, () => ((f.where === 'bag' ? p.bag : p.belt)[f.index] = null));
}

/** 身上物品的总重：每个都算（一叠按个数乘）。 */
export function carriedWeight(catalog: Catalog, p: Player): number {
  return [...p.bag, ...p.belt].reduce((s, it) => s + (it ? catalog.item(it.name).weight * it.count : 0), 0);
}

/** 存进仓库：并进同名一叠。 */
export function store(catalog: Catalog, storage: ItemInstance[], it: ItemInstance): void {
  const s = stacks(catalog.item(it.name)) ? storage.find((x) => x.name === it.name) : undefined;
  if (s) s.count += it.count;
  else storage.push(it);
}

/** 从仓库拿走 n 个（默认整件）。 */
export function unstore(storage: ItemInstance[], uid: number, n?: number): ItemInstance | null {
  const i = storage.findIndex((x) => x.uid === uid);
  if (i < 0) return null;
  return takeFrom(storage[i]!, n, () => storage.splice(i, 1));
}

/**
 * 读档整理：补上旧存档没有的数量（缺省 1）；身上和仓库各自把同名可叠的并成一叠（留第一次出现的那格）。
 * 不可叠的数量一律是 1。
 */
export function normalize(catalog: Catalog, p: Player): void {
  const fix = (it: ItemInstance): void => {
    const c = Math.floor(Number(it.count));
    it.count = stacks(catalog.item(it.name)) && c >= 1 ? c : 1;
  };
  const merge = (slots: (ItemInstance | null)[]): void => {
    const first = new Map<string, ItemInstance>();
    slots.forEach((it, i) => {
      if (!it) return;
      fix(it);
      if (!stacks(catalog.item(it.name))) return;
      const s = first.get(it.name);
      if (!s) first.set(it.name, it);
      else {
        s.count += it.count;
        slots[i] = null;
      }
    });
  };
  // 包裹和快捷栏当成一组合并（快捷栏在前，药留在快捷栏）
  const carried = [...p.belt, ...p.bag];
  merge(carried);
  p.belt = carried.slice(0, p.belt.length);
  p.bag = carried.slice(p.belt.length);
  const st: (ItemInstance | null)[] = [...p.storage];
  merge(st);
  p.storage = st.filter((x): x is ItemInstance => x !== null);
  for (const it of Object.values(p.equip)) if (it) fix(it);
}
