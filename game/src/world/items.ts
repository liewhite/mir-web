/**
 * 物品：实例、装备位、以及「这件东西到底加什么」。
 *
 * 1.76 的物品表把很多属性藏在 AC/MAC 的高低字节里（不同 StdMode 含义不同），原版界面只显示
 * 「防御 0-2」这种字面值，玩家看不出项链其实加的是准确/敏捷。这里按服务端 ApplyItemParameters
 * （ref/Mir176 PlayObject.cs:4414）把每种 StdMode 翻成明确的效果，属性计算和物品说明都从这一处取。
 */
import type { ItemDef, Range } from '../data/defs';

export type EquipSlot =
  | 'weapon'
  | 'dress'
  | 'helmet'
  | 'necklace'
  | 'braceletL'
  | 'braceletR'
  | 'ringL'
  | 'ringR';

export const EQUIP_SLOTS: readonly EquipSlot[] = [
  'weapon', 'dress', 'helmet', 'necklace', 'braceletL', 'braceletR', 'ringL', 'ringR',
];

export const SLOT_NAME: Record<EquipSlot, string> = {
  weapon: '武器', dress: '衣服', helmet: '头盔', necklace: '项链',
  braceletL: '左手镯', braceletR: '右手镯', ringL: '左戒指', ringR: '右戒指',
};

export interface ItemInstance {
  readonly uid: number;
  readonly name: string;
  /** 当前持久（药品、卷轴为 1；符和毒是剩余次数×100） */
  dura: number;
  duraMax: number;
  /**
   * 一格里叠了几个（#1159，原版不叠）：可叠的消耗品（见 stacks）同名放一格、不设上限；
   * 其他物品永远是 1。旧存档没有这个字段，读档时补 1。
   */
  count: number;
}

/** 物品的用途，按 StdMode 分类。 */
export type ItemKind =
  | { kind: 'potion'; hp: number; mp: number; instant: boolean }
  | { kind: 'scroll'; effect: 'escape' | 'random' | 'town' | 'other' }
  | { kind: 'book'; job: number; level: number }
  | { kind: 'bundle'; of: number } // 捆装药：拆成 6 个
  | { kind: 'equip'; slots: readonly EquipSlot[] }
  | { kind: 'amulet'; poison: 0 | 1 | 2 } // 护身符(0)/灰色药粉(1 绿毒)/黄色药粉(2 红毒)
  | { kind: 'material' }
  | { kind: 'other' };

/**
 * 哪些物品同名叠成一格（用户 #1159 定）：药、卷轴、技能书、捆装药叠；装备（每件持久不同）、
 * 护身符和毒粉（剩余次数记在持久里）、矿石等材料（纯度不同）和其他都不叠。
 */
const STACKS: Record<ItemKind['kind'], boolean> = {
  potion: true, scroll: true, book: true, bundle: true,
  equip: false, amulet: false, material: false, other: false,
};

export function stacks(d: ItemDef): boolean {
  return STACKS[itemKind(d).kind];
}

export function itemKind(d: ItemDef): ItemKind {
  switch (d.mode) {
    case 0:
      return { kind: 'potion', hp: d.ac[0], mp: d.mac[0], instant: d.shape === 1 };
    case 3:
      return {
        kind: 'scroll',
        effect: d.shape === 1 ? 'escape' : d.shape === 2 ? 'random' : d.shape === 3 ? 'town' : 'other',
      };
    case 4:
      return { kind: 'book', job: d.shape, level: d.dura };
    case 31:
      return { kind: 'bundle', of: d.shape };
    case 5:
    case 6:
      return { kind: 'equip', slots: ['weapon'] };
    case 10:
    case 11:
      return { kind: 'equip', slots: ['dress'] };
    case 15:
      return { kind: 'equip', slots: ['helmet'] };
    case 19:
    case 20:
    case 21:
      return { kind: 'equip', slots: ['necklace'] };
    case 22:
    case 23:
      return { kind: 'equip', slots: ['ringL', 'ringR'] };
    case 24:
    case 26:
      return { kind: 'equip', slots: ['braceletL', 'braceletR'] };
    case 25:
      return { kind: 'amulet', poison: d.shape === 1 ? 1 : d.shape === 2 ? 2 : 0 };
    case 42:
    case 43:
      return { kind: 'material' };
    default:
      return { kind: 'other' };
  }
}

/** 一件装备带来的全部加成，全部是明确含义的字段。 */
export interface ItemEffects {
  ac: Range;
  mac: Range;
  dc: Range;
  mc: Range;
  sc: Range;
  /** 准确：命中率 */
  hit: number;
  /** 敏捷：闪避率 */
  agility: number;
  /** 攻击速度档位：每档攻击间隔缩短 60 毫秒 */
  attackSpeed: number;
  luck: number;
  curse: number;
  /** 魔法躲避 */
  magicAvoid: number;
  /** 毒物躲避 */
  poisonAvoid: number;
  /** 体力恢复、魔法恢复、中毒恢复 */
  hpRegen: number;
  mpRegen: number;
  poisonRegen: number;
}

const ZERO: Range = [0, 0];

export function emptyEffects(): ItemEffects {
  return {
    ac: ZERO, mac: ZERO, dc: ZERO, mc: ZERO, sc: ZERO,
    hit: 0, agility: 0, attackSpeed: 0, luck: 0, curse: 0,
    magicAvoid: 0, poisonAvoid: 0, hpRegen: 0, mpRegen: 0, poisonRegen: 0,
  };
}

/** 武器 MAC 高字节是攻速：≤10 为减速，>10 为加速（GameItemSystem.RealAttackSpeed）。 */
function weaponSpeed(v: number): number {
  return v <= 10 ? -v : v - 10;
}

export function itemEffects(d: ItemDef): ItemEffects {
  const e = emptyEffects();
  e.dc = d.dc;
  e.mc = d.mc;
  e.sc = d.sc;
  const [acLo, acHi] = d.ac;
  const [macLo, macHi] = d.mac;
  switch (d.mode) {
    case 5:
    case 6:
      e.hit = acHi;
      e.attackSpeed = weaponSpeed(macHi);
      e.luck = acLo;
      e.curse = macLo;
      break;
    case 19:
      e.magicAvoid = acHi;
      e.curse = macLo;
      e.luck = macHi;
      break;
    case 20:
    case 24:
      e.hit = acHi;
      e.agility = macHi;
      break;
    case 21:
      e.hpRegen = acHi;
      e.mpRegen = macHi;
      e.attackSpeed = acLo - macLo;
      break;
    case 23:
      e.poisonAvoid = acHi;
      e.poisonRegen = macHi;
      e.attackSpeed = acLo - macLo;
      break;
    case 0:
    case 3:
    case 4:
    case 25:
    case 31:
      break; // 消耗品：AC/MAC 是药效，不是属性
    default:
      e.ac = d.ac;
      e.mac = d.mac;
  }
  return e;
}

/** 穿戴要求：need 0 等级、1 攻击上限、2 魔法上限、3 道术上限。 */
export type Requirement = { stat: 'level' | 'dc' | 'mc' | 'sc'; value: number };

export function requirement(d: ItemDef): Requirement {
  const stat = (['level', 'dc', 'mc', 'sc'] as const)[d.need] ?? 'level';
  return { stat, value: d.needLevel };
}

/** 男装/女装：10 男 11 女。 */
export function dressSex(d: ItemDef): 0 | 1 | null {
  return d.mode === 10 ? 0 : d.mode === 11 ? 1 : null;
}

let nextUid = 1;

export function setNextUid(n: number): void {
  nextUid = Math.max(nextUid, n);
}

/** 新物品；count 只对可叠的有意义，不可叠的传了也按 1。 */
export function newItem(d: ItemDef, count = 1): ItemInstance {
  return { uid: nextUid++, name: d.name, dura: d.dura, duraMax: d.dura, count: stacks(d) ? Math.max(1, count) : 1 };
}

/** 提示里的叫法：一叠写成「金创药(小量) ×5」。 */
export function itemLabel(it: ItemInstance): string {
  return it.count > 1 ? `${it.name} ×${it.count}` : it.name;
}

/** 从一叠里分出 n 个成为新的一件（新编号）；原来那叠不动，调用方负责减数量。 */
export function splitOff(it: ItemInstance, n: number): ItemInstance {
  return { ...it, uid: nextUid++, count: n };
}
