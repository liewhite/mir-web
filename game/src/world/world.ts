/**
 * 世界：当前地图上的全部角色、地上物品、时间推进和玩家指令。
 * 单机：只模拟玩家所在的地图；换地图时旧地图清空，新地图按刷怪表重新生成。
 */
import { type Dir, type Pos, chebyshev, dirTo, samePos, step } from '../core/geom';
import type { Catalog, DropDef, MonsterActionTable, MonsterDef } from '../data/defs';
import type { MapData } from '../assets/mapdata';
import {
  type Action, type ActKind, type Actor, type GroundItem, type HitStyle, Monster, Npc, Player, isGold,
} from './entities';
import { EQUIP_SLOTS, type EquipSlot, type ItemInstance, dressSex, itemEffects, itemKind, newItem, requirement } from './items';
import { computeStats, random, type Stats } from './rules';
import { findPath } from './path';
import { RATES, dropChance } from './rates';
import { S, monsterSound, struckBody, weaponSwing } from './sounds';

export type GameEvent =
  | { type: 'damage'; target: number; amount: number; source: number }
  | { type: 'miss'; target: number }
  | { type: 'heal'; target: number; amount: number }
  | { type: 'msg'; text: string; color?: 'system' | 'good' | 'bad' | 'item' }
  | { type: 'effect'; effect: VisualEffect }
  | { type: 'levelUp'; level: number }
  | { type: 'mapChange'; map: string; x: number; y: number }
  | { type: 'died' }
  | { type: 'sound'; id: number; at?: Pos };

/** 给渲染器的特效：图库 + 起始帧 + 帧数，放在格子或角色身上，可从 A 飞到 B。 */
export interface VisualEffect {
  readonly lib: string;
  readonly start: number;
  readonly frames: number;
  readonly frameTime: number;
  readonly at: Pos | { actor: number };
  readonly from?: Pos;
  readonly dir?: Dir;
  /** 飞行特效：每个方向的帧组间隔（如火球 10 帧一组） */
  readonly dirStride?: number;
  readonly blend: boolean;
  readonly startedAt: number;
  readonly duration: number;
}

export const WALK_MS = 600;
export const RUN_MS = 600;
export const HIT_ANIM_MS = 510;
export const SPELL_ANIM_MS = 600;
export const SPELL_INTERVAL = 800;
const CORPSE_MS = 10_000;
const ITEM_KEEP_MS = 10 * 60_000;

export class World {
  now = 0;
  map!: MapData;
  readonly actors = new Map<number, Actor>();
  readonly items: GroundItem[] = [];
  events: GameEvent[] = [];
  effects: VisualEffect[] = [];
  /** 刷怪点：下一次补怪时间 */
  private spawnTimers: number[] = [];
  private occupancy = new Int32Array(0);
  private nextId = 100; // 1 留给玩家
  player!: Player;

  constructor(readonly catalog: Catalog) {}

  // ---------------- 地图与占位 ----------------

  /** 进入地图（调用前已加载好 MapData）。 */
  enterMap(map: MapData, pos: Pos): void {
    const p = this.player;
    for (const a of [...this.actors.values()]) {
      if (a !== p && !(a.kind === 'monster' && p.pets.includes(a.id))) this.actors.delete(a.id);
    }
    this.items.length = 0;
    this.effects = [];
    this.map = map;
    this.occupancy = new Int32Array(map.width * map.height);
    const start = this.freeCellNear(pos) ?? pos;
    p.map = map.id;
    p.x = start.x;
    p.y = start.y;
    p.action = { kind: 'stand', startedAt: this.now, duration: 0 };
    this.occupy(p);
    // 宝宝跟过来
    for (const id of p.pets) {
      const pet = this.actors.get(id);
      if (!pet) continue;
      const c = this.freeCellNear(start) ?? start;
      pet.map = map.id;
      pet.x = c.x;
      pet.y = c.y;
      this.occupy(pet);
    }
    p.pets = p.pets.filter((id) => this.actors.has(id));
    // NPC、守卫、刷怪
    for (const m of this.catalog.data.merchants.filter((m) => m.map === map.id)) {
      const npc = new Npc(this.nextId++, m, this.now);
      this.actors.set(npc.id, npc);
      this.occupy(npc);
    }
    for (const g of this.catalog.data.guards.filter((g) => g.map === map.id)) {
      const def = this.catalog.monstersByName.get(g.monster);
      if (def) {
        const m = this.spawnMonster(def, -1, g);
        if (m) m.dir = (g.direction % 8) as Dir;
      }
    }
    const spawns = this.catalog.data.spawns;
    this.spawnTimers = spawns.map(() => Infinity);
    spawns.forEach((s, i) => {
      if (s.map !== map.id) return;
      this.spawnTimers[i] = this.now;
    });
    this.events.push({ type: 'mapChange', map: map.id, x: start.x, y: start.y });
    this.sound(S.spaceIn);
  }

  private idx(x: number, y: number): number {
    return y * this.map.width + x;
  }

  occupant(x: number, y: number): Actor | undefined {
    if (!this.map.inside(x, y)) return undefined;
    const id = this.occupancy[this.idx(x, y)];
    return id ? this.actors.get(id) : undefined;
  }

  private occupy(a: Actor): void {
    this.occupancy[this.idx(a.x, a.y)] = a.id;
  }

  private vacate(a: Actor): void {
    const i = this.idx(a.x, a.y);
    if (this.occupancy[i] === a.id) this.occupancy[i] = 0;
  }

  /** 地形可走且没人站。 */
  canEnter(x: number, y: number): boolean {
    return this.map.walkable(x, y) && !this.occupant(x, y);
  }

  freeCellNear(p: Pos, radius = 6): Pos | null {
    for (let r = 0; r <= radius; r++) {
      for (let dy = -r; dy <= r; dy++) {
        for (let dx = -r; dx <= r; dx++) {
          if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
          if (this.canEnter(p.x + dx, p.y + dy)) return { x: p.x + dx, y: p.y + dy };
        }
      }
    }
    return null;
  }

  // ---------------- 生成 ----------------

  spawnMonster(def: MonsterDef, spawn: number, at: Pos, radius = 0): Monster | null {
    let pos: Pos | null = null;
    for (let t = 0; t < 20 && !pos; t++) {
      const c = { x: at.x + random(radius * 2 + 1) - radius, y: at.y + random(radius * 2 + 1) - radius };
      if (this.canEnter(c.x, c.y)) pos = c;
    }
    pos ??= this.freeCellNear(at, Math.max(3, radius));
    if (!pos) return null;
    const m = new Monster(this.nextId++, def, spawn, this.map.id, pos.x, pos.y, this.now);
    m.action = { kind: 'stand', startedAt: this.now, duration: 0 };
    m.nextThink = this.now + random(2000);
    this.actors.set(m.id, m);
    this.occupy(m);
    return m;
  }

  private runSpawns(): void {
    const spawns = this.catalog.data.spawns;
    spawns.forEach((s, i) => {
      if ((this.spawnTimers[i] ?? Infinity) > this.now) return;
      const def = this.catalog.monstersByName.get(s.monster);
      if (!def) {
        this.spawnTimers[i] = Infinity;
        return;
      }
      let alive = 0;
      for (const a of this.actors.values()) if (a.kind === 'monster' && a.spawn === i && !a.dead) alive++;
      // 一次补齐，大刷怪点分几批
      for (let k = alive; k < s.count && k < alive + 40; k++) this.spawnMonster(def, i, s, s.radius);
      this.spawnTimers[i] = this.now + Math.max(1, s.minutes) * 60_000;
    });
  }

  // ---------------- 时间推进 ----------------

  private timers: { at: number; fn: () => void }[] = [];
  private stepFoot = 0;

  sound(id: number, at?: Pos): void {
    this.events.push({ type: 'sound', id, at });
  }

  /** 玩家武器外观（用于挥刀、打中的声音） */
  weaponShape(p: Player): number | null {
    return p.equip.weapon ? this.catalog.item(p.equip.weapon.name).shape : null;
  }

  /** 按游戏时间延后执行（飞行技能命中、药效分跳等）。 */
  after(ms: number, fn: () => void): void {
    this.timers.push({ at: this.now + ms, fn });
  }

  tick(dt: number): void {
    this.now += dt;
    if (this.timers.length) {
      const due = this.timers.filter((t) => t.at <= this.now);
      this.timers = this.timers.filter((t) => t.at > this.now);
      for (const t of due) t.fn();
    }
    this.runSpawns();
    for (const a of [...this.actors.values()]) {
      if (a.kind === 'monster') this.thinkMonster(a);
      this.tickStatus(a);
    }
    this.regen();
    this.effects = this.effects.filter((e) => this.now - e.startedAt < e.duration);
    // 尸体和过期物品
    for (const a of [...this.actors.values()]) {
      if (a.kind === 'monster' && a.dead && this.now >= a.removeAt) this.actors.delete(a.id);
    }
    for (let i = this.items.length - 1; i >= 0; i--) {
      if (this.now - this.items[i]!.droppedAt > ITEM_KEEP_MS) this.items.splice(i, 1);
    }
  }

  private tickStatus(a: Actor): void {
    const p = a.poison;
    if (!p || a.dead) return;
    if (this.now >= p.until) {
      a.poison = null;
      return;
    }
    if (p.kind === 'green' && this.now >= p.nextTick) {
      p.nextTick = this.now + 2000;
      this.applyDamage(a, Math.max(1, p.power), p.by, false);
    }
  }

  private regen(): void {
    const p = this.player;
    if (p.dead || this.now - p.lastRegen < 2000) return;
    p.lastRegen = this.now;
    const s = p.stats;
    const hp = Math.min(s.maxHp - p.hp, Math.max(1, Math.floor(s.maxHp / 75)) + s.hpRegen);
    const mp = Math.min(s.maxMp - p.mp, Math.max(1, Math.floor(s.maxMp / 75)) + s.mpRegen);
    if (hp > 0) p.hp += hp;
    if (mp > 0) p.mp += mp;
  }

  // ---------------- 动作 ----------------

  private setAction(a: Actor, kind: ActKind, duration: number, extra: Partial<Action> = {}): void {
    a.action = { kind, startedAt: this.now, duration, ...extra };
  }

  /** 走/跑一步。成功返回 true。 */
  move(a: Actor, dir: Dir, run: boolean): boolean {
    if (a.dead) return false;
    const steps = run ? 2 : 1;
    let to: Pos = a.pos;
    for (let i = 0; i < steps; i++) {
      const n = step(to, dir);
      if (!this.canEnter(n.x, n.y)) return false;
      to = n;
    }
    const from = a.pos;
    this.vacate(a);
    a.x = to.x;
    a.y = to.y;
    a.dir = dir;
    this.occupy(a);
    const dur = a.kind === 'player' ? (run ? RUN_MS : WALK_MS) : this.monsterMoveMs(a as Monster);
    this.setAction(a, run ? 'run' : 'walk', dur, { from });
    a.busyUntil = this.now + dur;
    if (a.kind === 'player') {
      const cave = this.map.id.startsWith('D');
      const pair = run ? (cave ? S.runCave : S.runGround) : cave ? S.walkCave : S.walkGround;
      this.sound(pair[this.stepFoot++ % 2]!);
      this.afterPlayerMove(a);
    }
    return true;
  }

  turn(a: Actor, dir: Dir): void {
    a.dir = dir;
  }

  private monsterMoveMs(m: Monster): number {
    const t = this.actTable(m);
    const anim = t ? t.ActWalk[1] * t.ActWalk[3] : 600;
    return Math.max(300, Math.min(anim, m.def.walkSpeed));
  }

  actTable(m: Monster): MonsterActionTable | undefined {
    return m.def.act ? this.catalog.data.monsterActions[m.def.act] : undefined;
  }

  private afterPlayerMove(p: Player): void {
    // 踩到传送点
    const r = this.catalog.data.routes.find((r) => r.from === this.map.id && r.x === p.x && r.y === p.y);
    if (r) this.pendingTeleport = { map: r.to, x: r.tx, y: r.ty };
  }

  /** 由游戏控制器在加载完新地图后执行。 */
  pendingTeleport: { map: string; x: number; y: number } | null = null;

  // ---------------- 战斗 ----------------

  isEnemy(a: Actor, b: Actor): boolean {
    if (a.dead || b.dead || a === b || b.kind === 'npc' || a.kind === 'npc') return false;
    const side = (x: Player | Monster): 'p' | 'm' | 'g' =>
      x.kind === 'player' ? 'p' : x.master ? 'p' : isGuard(x.def) ? 'g' : 'm';
    const sa = side(a);
    const sb = side(b);
    if (sa === sb) return false;
    if ((sa === 'g' && sb === 'p') || (sa === 'p' && sb === 'g')) return false;
    return true;
  }

  /** 伤害结算：魔法盾减伤、死亡处理、经验。 */
  applyDamage(target: Actor, amount: number, sourceId: number, interrupt = true): void {
    if (target.dead || target.kind === 'npc') return;
    let dmg = amount;
    if (target.shieldUntil > this.now && target.kind === 'player') {
      // 魔法盾：按等级吸收（1.76：减伤 (等级+2)/(等级+3)×... 简化为 等级档位）
      const absorb = [0.3, 0.4, 0.5, 0.6][target.shieldLevel] ?? 0.3;
      dmg = Math.max(1, Math.round(dmg * (1 - absorb)));
    }
    target.hp -= dmg;
    target.lastHitBy = sourceId;
    const src = this.actors.get(sourceId);
    if (target.kind === 'monster') {
      this.sound(struckBody(src?.kind === 'player' ? this.weaponShape(src) : null), target.pos);
      if (target.hp > 0) this.sound(monsterSound(target.def.appr, 4), target.pos);
    } else if (target.kind === 'player' && target.hp > 0) {
      this.sound(target.sex === 1 ? S.womStruck : S.manStruck);
    }
    this.events.push({ type: 'damage', target: target.id, amount: dmg, source: sourceId });
    if (target.hp <= 0) {
      target.hp = 0;
      this.kill(target, sourceId);
      return;
    }
    if (interrupt && target.kind === 'monster') {
      const t = this.actTable(target);
      const dur = t ? Math.max(200, t.ActStruck[1] * t.ActStruck[3]) : 200;
      if (target.action.kind === 'stand' || target.action.kind === 'struck') {
        this.setAction(target, 'struck', dur);
        target.busyUntil = Math.max(target.busyUntil, this.now + dur);
      }
      if (!target.target) target.target = sourceId;
    } else if (target.kind === 'player' && target.action.kind === 'stand') {
      this.setAction(target, 'struck', 210);
    }
  }

  private kill(target: Actor, killerId: number): void {
    target.dead = true;
    target.deadAt = this.now;
    target.poison = null;
    this.setAction(target, 'die', 1200);
    if (target.kind === 'monster') this.sound(monsterSound(target.def.appr, 5), target.pos);
    else if (target.kind === 'player') this.sound(target.sex === 1 ? S.womDie : S.manDie);
    if (target.kind === 'monster') {
      this.vacate(target);
      target.removeAt = this.now + CORPSE_MS;
      const killer = this.actors.get(killerId);
      const owner = killer?.kind === 'player' ? killer : killer?.kind === 'monster' && killer.master
        ? this.actors.get(killer.master) : undefined;
      if (owner?.kind === 'player' && !target.master) {
        this.gainExp(owner, target.def.exp * RATES.exp);
        this.dropLoot(target);
      }
      if (target.master) {
        const m = this.actors.get(target.master);
        if (m?.kind === 'player') m.pets = m.pets.filter((id) => id !== target.id);
      }
    } else if (target.kind === 'player') {
      this.events.push({ type: 'died' });
      this.events.push({ type: 'msg', text: '你死了。点「回城复活」回到城里。', color: 'bad' });
    }
  }

  revive(): void {
    const p = this.player;
    if (!p.dead) return;
    p.dead = false;
    p.hp = Math.max(1, Math.floor(p.stats.maxHp / 3));
    p.mp = Math.floor(p.stats.maxMp / 3);
    this.vacate(p);
    this.pendingTeleport = { ...p.home };
  }

  gainExp(p: Player, exp: number): void {
    if (exp <= 0) return;
    p.exp += exp;
    this.events.push({ type: 'msg', text: `获得经验 ${exp}`, color: 'good' });
    let need = this.catalog.expToNext(p.level);
    while (p.exp >= need) {
      p.exp -= need;
      p.level++;
      this.recalc(p);
      p.hp = p.stats.maxHp;
      p.mp = p.stats.maxMp;
      this.events.push({ type: 'levelUp', level: p.level });
      this.events.push({ type: 'msg', text: `升级了！现在 ${p.level} 级`, color: 'good' });
      need = this.catalog.expToNext(p.level);
    }
  }

  /** 按爆率表掉落（概率按 rates.ts 换算；金币数量 amount/2 + Random(amount)）。 */
  private dropLoot(m: Monster): void {
    const table: DropDef[] = this.catalog.data.drops[m.def.name] ?? [];
    for (const d of table) {
      const def0 = d.item === '金币' ? null : this.catalog.itemsByName.get(d.item) ?? null;
      if (Math.random() >= dropChance(d, def0)) continue;
      if (d.item === '金币') {
        const gold = Math.floor(d.amount / 2) + random(d.amount);
        if (gold > 0) this.dropAt(m.pos, { gold });
        continue;
      }
      const def = this.catalog.itemsByName.get(d.item);
      if (!def) continue;
      for (let k = 0; k < Math.max(1, d.amount); k++) this.dropAt(m.pos, newItem(def));
    }
  }

  dropAt(p: Pos, item: GroundItem['item']): void {
    // 找附近没有物品的格子
    for (let r = 0; r < 4; r++) {
      for (let dy = -r; dy <= r; dy++) {
        for (let dx = -r; dx <= r; dx++) {
          const x = p.x + dx;
          const y = p.y + dy;
          if (!this.map.walkable(x, y) || this.items.some((i) => i.x === x && i.y === y)) continue;
          this.items.push({ id: this.nextId++, map: this.map.id, x, y, item, droppedAt: this.now });
          return;
        }
      }
    }
    this.items.push({ id: this.nextId++, map: this.map.id, x: p.x, y: p.y, item, droppedAt: this.now });
  }

  // ---------------- 玩家：属性 ----------------

  recalc(p: Player): void {
    const effects = EQUIP_SLOTS.flatMap((s) => {
      const it = p.equip[s];
      if (!it || it.dura <= 0 && itemKind(this.catalog.item(it.name)).kind !== 'amulet') return [];
      const def = this.catalog.item(it.name);
      return itemKind(def).kind === 'amulet' ? [] : [itemEffects(def)];
    });
    const lv = (id: number): number => p.skills.find((s) => s.id === id)?.level ?? -1;
    p.stats = computeStats(p.job, p.level, effects, { oneSword: lv(3), spiritSword: lv(4), powerHit: lv(7) });
    p.hp = Math.min(p.hp, p.stats.maxHp);
    p.mp = Math.min(p.mp, p.stats.maxMp);
  }

  get stats(): Stats {
    return this.player.stats;
  }

  // ---------------- 物品 ----------------

  bagWeight(p: Player): number {
    return [...p.bag, ...p.belt].reduce((s, it) => s + (it ? this.catalog.item(it.name).weight : 0), 0);
  }

  wearWeight(p: Player): number {
    return (['dress', 'helmet', 'necklace', 'braceletL', 'braceletR', 'ringL', 'ringR'] as EquipSlot[])
      .reduce((s, k) => s + (p.equip[k] ? this.catalog.item(p.equip[k]!.name).weight : 0), 0);
  }

  /** 放进包裹；满了返回 false。药品优先放快捷栏空位。 */
  addToBag(p: Player, it: ItemInstance): boolean {
    const kind = itemKind(this.catalog.item(it.name)).kind;
    if (kind === 'potion' || kind === 'scroll') {
      const b = p.belt.indexOf(null);
      if (b >= 0) {
        p.belt[b] = it;
        return true;
      }
    }
    const i = p.bag.indexOf(null);
    if (i < 0) return false;
    p.bag[i] = it;
    return true;
  }

  pickUp(p: Player): boolean {
    const here = this.items.filter((i) => i.x === p.x && i.y === p.y);
    let any = false;
    for (const g of here) {
      this.sound(isGold(g.item) ? S.money : S.itemClick);
      if (isGold(g.item)) {
        p.gold += g.item.gold;
        this.events.push({ type: 'msg', text: `捡到金币 ${g.item.gold}`, color: 'item' });
      } else {
        const def = this.catalog.item(g.item.name);
        if (this.bagWeight(p) + def.weight > p.stats.maxBagWeight) {
          this.events.push({ type: 'msg', text: '包裹太重了，拿不动', color: 'bad' });
          continue;
        }
        if (!this.addToBag(p, g.item)) {
          this.events.push({ type: 'msg', text: '包裹满了', color: 'bad' });
          continue;
        }
        this.events.push({ type: 'msg', text: `捡到 ${g.item.name}`, color: 'item' });
      }
      this.items.splice(this.items.indexOf(g), 1);
      any = true;
    }
    return any;
  }

  findOwned(p: Player, uid: number): { where: 'bag' | 'belt'; index: number; item: ItemInstance } | null {
    let i = p.bag.findIndex((x) => x?.uid === uid);
    if (i >= 0) return { where: 'bag', index: i, item: p.bag[i]! };
    i = p.belt.findIndex((x) => x?.uid === uid);
    if (i >= 0) return { where: 'belt', index: i, item: p.belt[i]! };
    return null;
  }

  removeOwned(p: Player, uid: number): ItemInstance | null {
    const f = this.findOwned(p, uid);
    if (!f) return null;
    (f.where === 'bag' ? p.bag : p.belt)[f.index] = null;
    return f.item;
  }

  /** 双击包裹里的物品：喝药、读书、用卷轴、穿装备、拆药包。返回提示文字（失败时）。 */
  useItem(p: Player, uid: number): string | null {
    if (p.dead) return '你已经死了';
    const f = this.findOwned(p, uid);
    if (!f) return null;
    const def = this.catalog.item(f.item.name);
    const k = itemKind(def);
    switch (k.kind) {
      case 'potion': {
        this.removeOwned(p, uid);
        this.sound(S.eatDrug);
        if (k.instant) {
          const hp = Math.min(k.hp, p.stats.maxHp - p.hp);
          const mp = Math.min(k.mp, p.stats.maxMp - p.mp);
          p.hp += hp;
          p.mp += mp;
          if (hp) this.events.push({ type: 'heal', target: p.id, amount: hp });
        } else {
          this.queueRecover(p, k.hp, k.mp);
        }
        return null;
      }
      case 'bundle': {
        // 包的 Shape = 里面那种药的 AniCount
        const one = this.catalog.data.items.find((i) => i.mode !== 31 && i.ani === k.of);
        if (!one) return '这个包拆不开';
        const free = p.bag.filter((x) => x === null).length - 1 + p.belt.filter((x) => x === null).length;
        if (free < 6) return '包裹空位不够，拆包需要 6 个空位';
        this.removeOwned(p, uid);
        for (let i = 0; i < 6; i++) this.addToBag(p, newItem(one));
        return null;
      }
      case 'scroll':
        if (k.effect === 'town' || k.effect === 'escape') {
          this.removeOwned(p, uid);
          this.pendingTeleport = { ...p.home };
          return null;
        }
        if (k.effect === 'random') {
          this.removeOwned(p, uid);
          for (let t = 0; t < 50; t++) {
            const x = random(this.map.width);
            const y = random(this.map.height);
            if (this.canEnter(x, y)) {
              this.pendingTeleport = { map: this.map.id, x, y };
              break;
            }
          }
          return null;
        }
        return '这个暂时用不了';
      case 'book': {
        const skill = this.catalog.skillsByName.get(def.name);
        if (!skill) return '没有这个技能';
        if (skill.job !== p.job) return '你的职业学不了这个技能';
        if (p.level < k.level) return `需要 ${k.level} 级才能学`;
        if (p.skills.some((s) => s.id === skill.id)) return '已经学会了';
        this.removeOwned(p, uid);
        const key = [1, 2, 3, 4, 5, 6, 7, 8].find((n) => !p.skills.some((s) => s.key === n)) ?? 0;
        p.skills.push({ id: skill.id, level: 0, train: 0, key: isPassive(skill.id) ? 0 : key });
        this.recalc(p);
        this.events.push({ type: 'msg', text: `学会了 ${skill.name}`, color: 'good' });
        return null;
      }
      case 'equip':
      case 'amulet':
        return this.equip(p, uid);
      default:
        return '这个东西不能直接使用';
    }
  }

  /** 普通药：分几次慢慢回（1.76 是约 1 秒一跳）。 */
  private queueRecover(p: Player, hp: number, mp: number): void {
    const n = 5;
    for (let i = 0; i < n; i++) {
      this.after(i * 400, () => {
        if (p.dead) return;
        const h = Math.min(Math.ceil(hp / n), p.stats.maxHp - p.hp);
        const m = Math.min(Math.ceil(mp / n), p.stats.maxMp - p.mp);
        p.hp += Math.max(0, h);
        p.mp += Math.max(0, m);
      });
    }
  }

  canWear(p: Player, def: ReturnType<Catalog['item']>): string | null {
    const r = requirement(def);
    const have = r.stat === 'level' ? p.level : r.stat === 'dc' ? p.stats.dc[1] : r.stat === 'mc' ? p.stats.mc[1] : p.stats.sc[1];
    const label = { level: '等级', dc: '攻击上限', mc: '魔法上限', sc: '道术上限' }[r.stat];
    if (have < r.value) return `需要${label} ${r.value}（你现在 ${have}）`;
    const sex = dressSex(def);
    if (sex !== null && sex !== p.sex) return '性别不符';
    return null;
  }

  /** 穿戴；prefer 指定左右手镯/戒指位。 */
  equip(p: Player, uid: number, prefer?: EquipSlot): string | null {
    const f = this.findOwned(p, uid);
    if (!f) return null;
    const def = this.catalog.item(f.item.name);
    const k = itemKind(def);
    const slots: readonly EquipSlot[] = k.kind === 'equip' ? k.slots : k.kind === 'amulet' ? ['braceletL'] : [];
    if (!slots.length) return '这个不能穿戴';
    const why = this.canWear(p, def);
    if (why) return why;
    if (slots[0] === 'weapon') {
      if (def.weight > p.stats.maxHandWeight) return `武器太重（${def.weight}），你的腕力 ${p.stats.maxHandWeight}`;
    } else {
      const cur = slots.map((s) => p.equip[s]);
      const freeWeight = this.wearWeight(p) - (cur[0] ? this.catalog.item(cur[0].name).weight : 0);
      if (freeWeight + def.weight > p.stats.maxWearWeight) return `穿不动了（负重上限 ${p.stats.maxWearWeight}）`;
    }
    if (prefer && !slots.includes(prefer)) return '不能放在这个位置';
    const slot = prefer ?? slots.find((s) => !p.equip[s]) ?? slots[0]!;
    this.removeOwned(p, uid);
    const old = p.equip[slot];
    p.equip[slot] = f.item;
    if (old) {
      if (f.where === 'bag') p.bag[f.index] = old;
      else this.addToBag(p, old);
    }
    this.recalc(p);
    return null;
  }

  unequip(p: Player, slot: EquipSlot): string | null {
    const it = p.equip[slot];
    if (!it) return null;
    const i = p.bag.indexOf(null);
    if (i < 0) return '包裹满了';
    p.bag[i] = it;
    p.equip[slot] = null;
    this.recalc(p);
    return null;
  }

  // ---------------- 玩家：攻击 ----------------

  /** 朝某方向普通攻击（含战士剑术）。 */
  playerAttack(p: Player, dir: Dir): void {
    if (p.dead || this.now < p.busyUntil) return;
    p.dir = dir;
    const front = step(p, dir);
    const target = this.occupant(front.x, front.y);
    let style: HitStyle = 'normal';
    const has = (id: number): boolean => p.skills.some((s) => s.id === id);
    if (p.fireReady && target && this.isEnemy(p, target)) style = 'fire';
    else if (p.powerHitReady && has(7)) style = 'power';
    else if (p.wideHitOn && has(25) && p.mp >= 3) style = 'wide';
    else if (p.longHitOn && has(12)) style = 'long';
    this.setAction(p, style === 'normal' ? 'attack' : 'heavy', HIT_ANIM_MS, { hit: style });
    p.busyUntil = this.now + Math.max(HIT_ANIM_MS, p.stats.hitInterval);
    this.sound(weaponSwing(this.weaponShape(p)));
    const styleSound = { normal: 0, power: p.sex === 1 ? S.yedoWoman : S.yedoMan, long: 0, wide: S.wideHit, fire: S.fireHit }[style];
    if (styleSound) this.sound(styleSound);
    if (style === 'long' && target) this.sound(S.longHit);
    resolvePlayerHit(this, p, dir, style);
  }

  // ---------------- 怪物 AI ----------------

  private thinkMonster(m: Monster): void {
    if (m.dead || this.now < m.nextThink || this.now < m.busyUntil) return;
    m.nextThink = this.now + 100 + random(100);
    if (m.action.kind !== 'stand' && this.now >= m.action.startedAt + m.action.duration) {
      m.action = { kind: 'stand', startedAt: this.now, duration: 0 };
    }
    const guard = isGuard(m.def);
    const master = m.master ? this.actors.get(m.master) : undefined;
    let target = m.target ? this.actors.get(m.target) : undefined;
    if (target && (target.dead || !this.isEnemy(m, target) || chebyshev(m, target) > 14 || target.invisibleUntil > this.now)) {
      target = undefined;
      m.target = 0;
    }
    if (!target) {
      const passive = isPassive_(m.def);
      if (!passive || master) {
        const range = guard ? 6 : master ? 8 : 7;
        target = this.nearestEnemy(m, range);
        if (target) m.target = target.id;
      }
    }
    if (target) {
      const d = chebyshev(m, target);
      if (isRanged(m.def) && d <= 7 && d >= 1) {
        this.monsterAttack(m, target, true);
      } else if (d <= 1) {
        this.monsterAttack(m, target);
      } else if (!guard) {
        this.monsterStepToward(m, target);
      }
      return;
    }
    if (master && master.map === m.map) {
      if (chebyshev(m, master) > 2) this.monsterStepToward(m, master);
      return;
    }
    if (master && master.dead) return;
    // 闲逛
    if (!guard && random(100) < 4) {
      const dir = random(8) as Dir;
      this.move(m, dir, false);
      m.busyUntil = this.now + m.def.walkSpeed + random(1000);
    }
  }

  /** range 格以内最近的敌人（怪看不见隐身的，除非它能看穿隐身）。 */
  nearestEnemy(m: Actor, range: number): Actor | undefined {
    let best: Actor | undefined;
    let bd = Infinity;
    const seesInvisible = m.kind !== 'monster' || m.def.coolEye;
    for (const a of this.actors.values()) {
      if (!this.isEnemy(m, a) || (a.invisibleUntil > this.now && !seesInvisible)) continue;
      const d = chebyshev(m, a);
      if (d <= range && d < bd) {
        best = a;
        bd = d;
      }
    }
    return best;
  }

  private monsterStepToward(m: Monster, t: Pos): void {
    const dir = dirTo(m, t);
    if (this.move(m, dir, false)) {
      m.busyUntil = this.now + m.def.walkSpeed;
      return;
    }
    // 绕一下
    const path = findPath(this, m, t, 200, true);
    if (path?.[0] !== undefined && this.move(m, path[0], false)) {
      m.busyUntil = this.now + m.def.walkSpeed;
    } else {
      m.busyUntil = this.now + 500;
    }
  }

  private monsterAttack(m: Monster, t: Actor, ranged = false): void {
    m.dir = dirTo(m, t);
    if (this.now - m.lastAttack < m.def.attackSpeed) return;
    m.lastAttack = this.now;
    const tab = this.actTable(m);
    const anim = tab ? tab.ActAttack[1] * tab.ActAttack[3] : 600;
    this.setAction(m, 'attack', anim);
    m.busyUntil = this.now + anim;
    this.sound(monsterSound(m.def.appr, 2), m.pos);
    if (ranged) {
      // 掷斧骷髅、弓箭手：远程（DualAxeMonster.FlyAxeAttack），伤害按距离延迟到达
      const d = chebyshev(m, t);
      const delay = d * 50 + 300;
      if (m.def.raceImg === 15) {
        const a16 = Math.atan2((t.x - m.x) * 48, -(t.y - m.y) * 32);
        const dir16 = ((Math.round(a16 / (Math.PI / 8)) % 16) + 16) % 16;
        this.effects.push({ lib: 'mon3', start: 447 + dir16 * 10, frames: 3, frameTime: 60, at: { actor: t.id }, from: m.pos, blend: false, startedAt: this.now, duration: delay });
      }
      const power = m.dc[0] + random(m.dc[1] - m.dc[0] + 1);
      this.after(delay, () => {
        if (t.dead || m.dead) return;
        const ac = t.kind === 'player' ? t.stats.ac : t.kind === 'monster' ? t.ac : null;
        if (!ac) return;
        const dmg = Math.max(0, power - (ac[0] + random(Math.abs(ac[1] - ac[0]) + 1)));
        if (dmg > 0) this.applyDamage(t, dmg, m.id);
        else this.events.push({ type: 'miss', target: t.id });
      });
      return;
    }
    const power = m.dc[0] + random(m.dc[1] - m.dc[0] + 1);
    const defense = t.kind === 'player' ? t.stats : t.kind === 'monster' ? { ac: t.ac, agility: t.agility } : null;
    if (!defense) return;
    if (m.hit < random(defense.agility)) {
      this.events.push({ type: 'miss', target: t.id });
      return;
    }
    const armor = defense.ac[0] + random(Math.abs(defense.ac[1] - defense.ac[0]) + 1);
    const dmg = Math.max(0, power - armor);
    if (dmg > 0) this.applyDamage(t, dmg, m.id);
    else this.events.push({ type: 'miss', target: t.id });
  }

  // ---------------- 寻路 ----------------

  pathTo(from: Pos, to: Pos, adjacent = false): Dir[] | null {
    if (samePos(from, to)) return [];
    return findPath(this, from, to, 3000, adjacent);
  }
}

/** 鸡、鹿等不主动攻击（Race 51/52 动物）。 */
function isPassive_(d: MonsterDef): boolean {
  return d.race === 51 || d.race === 52 || d.race === 50;
}

/** 远程怪：掷斧骷髅(87)、弓箭手(104)、弓箭守卫(112)。 */
function isRanged(d: MonsterDef): boolean {
  return d.race === 87 || d.race === 104 || d.race === 112;
}

export function isGuard(d: MonsterDef): boolean {
  return d.name.includes('卫士') || d.name.includes('护卫') || d.name.includes('守卫');
}

/** 被动技能：学会就生效，不占快捷键。 */
export function isPassive(skillId: number): boolean {
  return skillId === 3 || skillId === 4 || skillId === 7 || skillId === 12;
}

// 由 combat.ts 实现，避免 world.ts 过长
import { resolvePlayerHit } from './combat';
export type { Action };
