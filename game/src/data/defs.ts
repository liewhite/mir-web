import { assetUrl } from '../assets/sprites';

/** game.json 的类型（tools/build_data.py 生成）。 */

export type Range = readonly [number, number];

export interface MapDef {
  id: string;
  /** 素材名：assets/map/<asset>.bin、assets/ground/<asset>/；内容相同的地图共用一份 */
  asset: string;
  title: string;
  flags: string;
  width: number;
  height: number;
}

export interface RouteDef {
  from: string;
  x: number;
  y: number;
  to: string;
  tx: number;
  ty: number;
}

export interface SpawnDef {
  map: string;
  x: number;
  y: number;
  monster: string;
  radius: number;
  count: number;
  minutes: number;
}

export interface GuardDef {
  monster: string;
  map: string;
  x: number;
  y: number;
  direction: number;
}

export interface MerchantDef {
  map: string;
  x: number;
  y: number;
  name: string;
  appearance: number;
  service: 'shop' | 'storage' | string;
  item_modes: number[];
  goods: { name: string; stock: number; minutes: number }[];
}

/** 物品。字段沿用 1.76 StdItems 的含义：mode=StdMode, shape=Shape, look=图标号。 */
export interface ItemDef {
  id: number;
  name: string;
  mode: number;
  shape: number;
  weight: number;
  ani: number;
  source: number;
  look: number;
  dura: number;
  ac: Range;
  mac: Range;
  dc: Range;
  mc: Range;
  sc: Range;
  need: number;
  needLevel: number;
  price: number;
}

export interface MonsterDef {
  name: string;
  race: number;
  raceImg: number;
  appr: number;
  lib: string;
  offset: number;
  act: string | null;
  level: number;
  undead: number;
  exp: number;
  hp: number;
  mp: number;
  ac: Range;
  mac: Range;
  dc: Range;
  mc: number;
  sc: number;
  speed: number;
  hit: number;
  walkSpeed: number;
  walkStep: number;
  walkWait: number;
  attackSpeed: number;
  coolEye: number;
  viewRange: number;
}

export interface DropDef {
  item: string;
  numerator: number;
  denominator: number;
  amount: number;
}

export interface SkillDef {
  id: number;
  name: string;
  /** 0 战士 1 法师 2 道士 */
  job: number;
  effectType: number;
  effect: number;
  spell: number;
  power: Range;
  defSpell: number;
  defPower: Range;
  needLevel: readonly [number, number, number];
  train: readonly [number, number, number];
  /** 毫秒 */
  delay: number;
}

/** 原版动作表一项：起始帧、帧数、每方向跳过帧、每帧毫秒、移动用 tick。 */
export type ActionInfo = readonly [start: number, frame: number, skip: number, ftime: number, usetick: number];

export interface MonsterActionTable {
  ActStand: ActionInfo;
  ActWalk: ActionInfo;
  ActAttack: ActionInfo;
  ActCritical: ActionInfo;
  ActStruck: ActionInfo;
  ActDie: ActionInfo;
  ActDeath: ActionInfo;
}

/** 传送菜单的一个目的地（tools/build_data.py teleports）：group 是分组（城市、各地区）。 */
export interface TeleportDef {
  group: string;
  name: string;
  map: string;
  x: number;
  y: number;
}

export interface GameData {
  maps: MapDef[];
  routes: RouteDef[];
  spawns: SpawnDef[];
  guards: GuardDef[];
  merchants: MerchantDef[];
  minimaps: Record<string, number>;
  items: ItemDef[];
  monsters: MonsterDef[];
  drops: Record<string, DropDef[]>;
  displayNames: Record<string, string>;
  skills: SkillDef[];
  exp: Record<string, number>;
  monsterActions: Record<string, MonsterActionTable>;
  teleports: TeleportDef[];
}

/** 按名字/编号查的索引。 */
export class Catalog {
  readonly itemsByName = new Map<string, ItemDef>();
  readonly itemsById = new Map<number, ItemDef>();
  readonly monstersByName = new Map<string, MonsterDef>();
  readonly skillsById = new Map<number, SkillDef>();
  readonly skillsByName = new Map<string, SkillDef>();
  readonly mapsById = new Map<string, MapDef>();

  constructor(readonly data: GameData) {
    for (const it of data.items) {
      this.itemsByName.set(it.name, it);
      this.itemsById.set(it.id, it);
    }
    for (const m of data.monsters) this.monstersByName.set(m.name, m);
    for (const s of data.skills) {
      this.skillsById.set(s.id, s);
      this.skillsByName.set(s.name, s);
    }
    for (const m of data.maps) this.mapsById.set(m.id, m);
  }

  item(name: string): ItemDef {
    const it = this.itemsByName.get(name);
    if (!it) throw new Error(`没有物品 ${name}`);
    return it;
  }

  map(id: string): MapDef {
    const m = this.mapsById.get(id);
    if (!m) throw new Error(`没有地图 ${id}`);
    return m;
  }

  /** 升到下一级要的经验；满级返回 Infinity。 */
  expToNext(level: number): number {
    return this.data.exp[String(level)] ?? Infinity;
  }

  displayName(monster: string): string {
    return this.data.displayNames[monster] ?? monster;
  }
}

export async function loadCatalog(): Promise<Catalog> {
  const r = await fetch(assetUrl('assets/data/game.json'));
  if (!r.ok) throw new Error(`game.json ${r.status}`);
  return new Catalog((await r.json()) as GameData);
}
