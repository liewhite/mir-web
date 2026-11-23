/** 世界里的角色：玩家、怪物、NPC。只有状态，行为在 world/*.ts 的系统里。 */
import type { Dir, Pos } from '../core/geom';
import type { MerchantDef, MonsterDef, Range } from '../data/defs';
import type { EquipSlot, ItemInstance } from './items';
import type { Job, Stats } from './rules';

/** 正在做的动作；渲染按它取帧和插值位置。 */
export type ActKind = 'stand' | 'walk' | 'run' | 'attack' | 'heavy' | 'spell' | 'struck' | 'die' | 'dead'
  /** 怪物从地下钻出、石像醒来、僵尸复活；钻回地下 */
  | 'digup' | 'digdown';

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
  /** 麻痹（原版 STONE 状态）到什么时候：期间不能动 */
  paralyzedUntil = 0;
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

export const BAG_SIZE = 40;

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
  /** 包裹 40 格（原版 8x5；另 6 格是快捷栏 belt） */
  bag: (ItemInstance | null)[] = new Array(BAG_SIZE).fill(null);
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
  /** 藏身状态：open 在外面；buried 埋在地下（看不见、打不到）；stone 石像（打不到）。见 monster_ai.ts */
  cover: 'open' | 'buried' | 'stone' = 'open';
  /** 最近一次钻出/钻回/醒来的时间 */
  coverAt = 0;
  bornAt = 0;
  /** 攻击、走路间隔（毫秒）：平时取数据表，沃玛教主狂暴时变快 */
  attackInterval: number;
  walkInterval: number;
  /** 召唤出来的小怪 */
  minions: number[] = [];
  /** 还能复活几次、几时复活（0 = 不复活） */
  revivesLeft = 0;
  reviveAt = 0;
  /** 打死给的经验倍数（复活一次减半） */
  expScale = 1;
  /** 祖玛教主召唤档位、沃玛教主狂暴计数 */
  danger = 5;
  rage: 'calm' | 'ready' | 'crazy' = 'calm';
  rageAt = 0;
  extraTick = 0;

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
    this.attackInterval = def.attackSpeed;
    this.walkInterval = def.walkSpeed;
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
