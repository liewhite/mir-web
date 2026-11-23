/** 世界里的角色：玩家、怪物、NPC。只有状态，行为在 world/*.ts 的系统里。 */
import type { Dir, Pos } from '../core/geom';
import type { MerchantDef, MonsterDef, Range } from '../data/defs';
import type { EquipSlot, ItemInstance } from './items';
import type { Job, Stats } from './rules';

/** 正在做的动作；渲染按它取帧和插值位置。 */
export type ActKind = 'stand' | 'walk' | 'run' | 'attack' | 'heavy' | 'spell' | 'struck' | 'die' | 'dead';

/** 普通攻击的种类，决定人物动作和刀光。 */
export type HitStyle = 'normal' | 'power' | 'long' | 'wide' | 'fire';

export interface Action {
  readonly kind: ActKind;
  readonly startedAt: number;
  readonly duration: number;
  /** 移动动作的起点（终点 = 当前坐标） */
  readonly from?: Pos;
  readonly hit?: HitStyle;
  /** 施法时的特效编号（技能 effect） */
  readonly spellEffect?: number;
}

export interface Poison {
  /** 绿毒掉血、红毒降防 */
  readonly kind: 'green' | 'red';
  readonly until: number;
  readonly power: number;
  nextTick: number;
  readonly by: number;
}

abstract class ActorBase {
  map: string;
  x: number;
  y: number;
  dir: Dir = 4;
  hp = 1;
  mp = 0;
  action: Action;
  /** 下一次能接受新动作的时间 */
  busyUntil = 0;
  dead = false;
  deadAt = 0;
  poison: Poison | null = null;
  /** 魔法盾 / 隐身 / 神圣战甲 / 幽灵盾 等状态的结束时间 */
  shieldUntil = 0;
  shieldLevel = 0;
  invisibleUntil = 0;
  /** 最近打过我的角色，用于反击和经验归属 */
  lastHitBy = 0;

  constructor(readonly id: number, map: string, x: number, y: number, now: number) {
    this.map = map;
    this.x = x;
    this.y = y;
    this.action = { kind: 'stand', startedAt: now, duration: 0 };
  }

  get pos(): Pos {
    return { x: this.x, y: this.y };
  }
}

export interface LearnedSkill {
  readonly id: number;
  level: number;
  train: number;
  /** 快捷键 1-8 对应 F1-F8，0 未设 */
  key: number;
}

export class Player extends ActorBase {
  readonly kind = 'player' as const;
  name: string;
  job: Job;
  sex: 0 | 1;
  hair: number;
  level = 1;
  exp = 0;
  gold = 0;
  bag: (ItemInstance | null)[] = new Array(46).fill(null);
  belt: (ItemInstance | null)[] = new Array(6).fill(null);
  storage: ItemInstance[] = [];
  equip: Record<EquipSlot, ItemInstance | null> = {
    weapon: null, dress: null, helmet: null, necklace: null,
    braceletL: null, braceletR: null, ringL: null, ringR: null,
  };
  skills: LearnedSkill[] = [];
  stats!: Stats;
  /** 战士：攻杀剑术计数、烈火是否已蓄好、刺杀/半月开关 */
  powerHitReady = false;
  powerHitCount = 0;
  powerHitPoint = 0;
  fireReady = false;
  fireCooldownUntil = 0;
  longHitOn = true;
  wideHitOn = false;
  /** 技能冷却：技能 id → 可再用时间 */
  cooldown = new Map<number, number>();
  /** 宝宝：召唤物 id */
  pets: number[] = [];
  /** 上次进入的城（回城卷、死亡复活的位置） */
  home: { map: string; x: number; y: number };
  lastRegen = 0;

  constructor(id: number, name: string, job: Job, sex: 0 | 1, map: string, x: number, y: number, now: number) {
    super(id, map, x, y, now);
    this.name = name;
    this.job = job;
    this.sex = sex;
    this.hair = 1;
    this.home = { map, x, y };
  }
}

export class Monster extends ActorBase {
  readonly kind = 'monster' as const;
  readonly def: MonsterDef;
  readonly spawn: number;
  maxHp: number;
  ac: Range;
  mac: Range;
  dc: Range;
  hit: number;
  agility: number;
  target = 0;
  nextThink = 0;
  lastAttack = 0;
  /** 召唤物或被诱惑的怪：主人的 id */
  master = 0;
  /** 宝宝等级（0-3，影响属性） */
  petLevel = 0;
  /** 尸体消失时间 */
  removeAt = 0;
  /** 被打到时短暂停手 */
  stunnedUntil = 0;

  constructor(id: number, def: MonsterDef, spawn: number, map: string, x: number, y: number, now: number) {
    super(id, map, x, y, now);
    this.def = def;
    this.spawn = spawn;
    this.maxHp = def.hp;
    this.hp = def.hp;
    this.ac = def.ac;
    this.mac = def.mac;
    this.dc = def.dc;
    this.hit = def.hit;
    this.agility = def.speed;
    this.dir = Math.floor(Math.random() * 8) as Dir;
  }
}

export class Npc extends ActorBase {
  readonly kind = 'npc' as const;
  constructor(id: number, readonly def: MerchantDef, now: number) {
    super(id, def.map, def.x, def.y, now);
    this.hp = 1;
  }
}

export type Actor = Player | Monster | Npc;

/** 地上的物品。 */
export interface GroundItem {
  readonly id: number;
  readonly map: string;
  readonly x: number;
  readonly y: number;
  readonly item: ItemInstance | { gold: number };
  readonly droppedAt: number;
}

export function isGold(it: GroundItem['item']): it is { gold: number } {
  return 'gold' in it;
}
