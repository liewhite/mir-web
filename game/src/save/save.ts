/** 存档：玩家状态的序列化，和存到哪里（SaveStore 接口，服务端 / 内存两种实现）。 */
import type { Catalog } from '../data/defs';
import { BAG_SIZE, Player } from '../world/entities';
import { EQUIP_SLOTS, type EquipSlot, type ItemInstance, setNextUid } from '../world/items';
import type { Job } from '../world/rules';

export const SAVE_VERSION = 1;

export interface SaveData {
  version: number;
  savedAt: number;
  player: {
    name: string;
    job: Job;
    sex: 0 | 1;
    hair: number;
    level: number;
    exp: number;
    gold: number;
    hp: number;
    mp: number;
    map: string;
    x: number;
    y: number;
    home: { map: string; x: number; y: number };
    bag: (ItemInstance | null)[];
    belt: (ItemInstance | null)[];
    storage: ItemInstance[];
    equip: Partial<Record<EquipSlot, ItemInstance | null>>;
    skills: { id: number; level: number; train: number; key: number }[];
    longHitOn: boolean;
    wideHitOn: boolean;
  };
  settings: Record<string, unknown>;
}

export function toSave(p: Player, settings: Record<string, unknown>): SaveData {
  return {
    version: SAVE_VERSION,
    savedAt: Date.now(),
    player: {
      name: p.name, job: p.job, sex: p.sex, hair: p.hair, level: p.level, exp: p.exp, gold: p.gold,
      hp: p.hp, mp: p.mp, map: p.map, x: p.x, y: p.y, home: p.home,
      bag: p.bag, belt: p.belt, storage: p.storage, equip: p.equip,
      skills: p.skills.map((s) => ({ ...s })), longHitOn: p.longHitOn, wideHitOn: p.wideHitOn,
    },
    settings,
  };
}

/** 读档：丢掉数据表里已不存在的物品，避免旧存档把游戏弄坏。 */
export function fromSave(d: SaveData, catalog: Catalog, id: number, now: number): Player {
  const s = d.player;
  const p = new Player(id, s.name, s.job, s.sex, s.map, s.x, s.y, now);
  const valid = (it: ItemInstance | null | undefined): ItemInstance | null =>
    it && catalog.itemsByName.has(it.name) ? it : null;
  p.hair = s.hair;
  p.level = s.level;
  p.exp = s.exp;
  p.gold = s.gold;
  p.hp = s.hp;
  p.mp = s.mp;
  p.home = s.home;
  p.bag = Array.from({ length: BAG_SIZE }, (_, i) => valid(s.bag[i]));
  p.belt = Array.from({ length: 6 }, (_, i) => valid(s.belt[i]));
  p.storage = s.storage.filter((x) => valid(x)) as ItemInstance[];
  for (const k of EQUIP_SLOTS) p.equip[k] = valid(s.equip[k]);
  p.skills = s.skills.filter((x) => catalog.skillsById.has(x.id)).map((x) => ({ ...x }));
  p.longHitOn = s.longHitOn;
  p.wideHitOn = s.wideHitOn;
  const all = [...p.bag, ...p.belt, ...p.storage, ...Object.values(p.equip)];
  setNextUid(1 + Math.max(0, ...all.map((it) => it?.uid ?? 0)));
  return p;
}

export interface SaveStore {
  load(): Promise<SaveData | null>;
  save(d: SaveData): Promise<void>;
}

/** 服务端存档：GET/PUT /api/save（登录后带 cookie）。 */
export class HttpSaveStore implements SaveStore {
  async load(): Promise<SaveData | null> {
    const r = await fetch('api/save', { credentials: 'same-origin', cache: 'no-store' });
    if (r.status === 404) return null;
    if (r.status === 401) throw new Error('未登录');
    if (!r.ok) throw new Error(`读档失败 ${r.status}`);
    return (await r.json()) as SaveData;
  }

  async save(d: SaveData): Promise<void> {
    const r = await fetch('api/save', {
      method: 'PUT', credentials: 'same-origin', headers: { 'content-type': 'application/json' },
      body: JSON.stringify(d),
    });
    if (!r.ok) throw new Error(`存档失败 ${r.status}`);
  }
}
