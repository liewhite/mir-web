/**
 * 怪物行为。按数据表的 Race（服务端怪物类型）决定：怎么出招、会不会藏起来、不能移动、召唤小怪、复活等。
 * 出处：ref/Mir176 GameSrv WorldServer.MonGen.cs CreateMonster（Race → 类）与 M2Server/Monster/Monsters/*.cs；
 * 攻击特效照原版客户端 AxeMon.pas（TGasKuDeGi / TSculptureMon / TDualAxeOma / TArcherMon）。
 */
import { type Dir, type Pos, chebyshev, dir16, dirTo, opposite, step } from '../core/geom';
import type { MonsterDef } from '../data/defs';
import type { Actor, Monster } from './entities';
import { afterArmor, afterMagicArmor, hits, random } from './rules';
import { monsterSound } from './sounds';
import type { World } from './world';

// ---------------- 行为描述 ----------------

/** 出招方式。 */
export type Strike =
  /** 贴身物理攻击（AtMonster） */
  | { readonly kind: 'melee' }
  /** 贴身，伤害一半物理一半魔法（沃玛教主、祖玛教主 HitMagAttackTarget） */
  | { readonly kind: 'split'; readonly physical: number }
  /** 贴身魔法攻击（火焰沃玛 MagCowMonster） */
  | { readonly kind: 'magic' }
  /** 贴身喷气：魔法伤害，有机会麻痹（洞蛆、楔蛾、粪虫 GasAttackMonster） */
  | { readonly kind: 'gas' }
  /** 朝前方 2 格喷毒：魔法伤害，有机会中绿毒（蜘蛛 SpitSpider） */
  | { readonly kind: 'spit' }
  /** 远程投掷（掷斧骷髅、弓箭手 DualAxeMonster） */
  | { readonly kind: 'missile'; readonly range: number; readonly fly: Missile | null }
  /** 朝目标方向放一道 8 格电光，线上的都挨打；目标太近会后退（僵尸 LightingZombi） */
  | { readonly kind: 'lightning' }
  /** 打范围内所有敌人（赤月恶魔 BigHeart；触龙神 CentipedeKing 附带中毒、麻痹） */
  | { readonly kind: 'area'; readonly range: number; readonly poison: boolean }
  /** 贴身自爆（爆裂蜘蛛 ExplosionSpider），活 60 秒也会自爆 */
  | { readonly kind: 'explode' }
  /** 不攻击，只放小怪（角蝇巢 BeeQueen、幻影蜘蛛 SpiderHouse） */
  | { readonly kind: 'spawn'; readonly child: string; readonly below: boolean };

export interface Missile {
  readonly lib: string;
  readonly base: number;
  /** 16 方向之间的帧间隔（飞斧 10，箭 1） */
  readonly stride: number;
  readonly frames: number;
}

/** 平时藏在哪。 */
export type Cover =
  | { readonly kind: 'none' }
  /** 埋在地下，敌人走近（< comeOut 格）钻出来；目标离开 reach 格就钻回去（食人花 StickMonster） */
  | { readonly kind: 'burrow'; readonly comeOut: number; readonly reach: number }
  /** 埋在地下，敌人到 3 格内钻出来，之后一直在外面（僵尸 DigOutZombi） */
  | { readonly kind: 'digOut' }
  /** 触龙神：敌人 < 4 格钻出，钻出 10 秒后附近没人就钻回去，钻出时回满血 */
  | { readonly kind: 'centipede' }
  /** 石像，敌人到 2 格内醒来；wakeAll 时附近 7 格的石像一起醒（祖玛雕像、祖玛教主） */
  | { readonly kind: 'statue'; readonly wakeAll: boolean };

export type Extra =
  /** 死后几秒原地复活，血上限和经验减半（僵尸 ZilKinZombi） */
  | { readonly kind: 'revive' }
  /** 血每掉一档（20%）召一批祖玛怪（祖玛教主） */
  | { readonly kind: 'summonZuma' }
  /** 沃玛教主：被围住时瞬移到目标身后；血越少越狂暴（攻速、移速变快） */
  | { readonly kind: 'cowKing' };

export interface Behavior {
  readonly strike: Strike;
  readonly cover: Cover;
  /** 原地不动（StickMode） */
  readonly rooted: boolean;
  /** 不主动打人（鸡、鹿、羊） */
  readonly passive: boolean;
  readonly extras: readonly Extra[];
}

const AXE: Missile = { lib: 'mon3', base: 447, stride: 10, frames: 3 };
const THORN: Missile = { lib: 'mon3', base: 2967, stride: 10, frames: 3 };
const ARROW: Missile = { lib: 'effect', base: 272, stride: 1, frames: 1 };

const ZUMA = ['祖玛卫士', '祖玛雕像', '祖玛弓箭手', '楔蛾'] as const;

function behave(strike: Strike, more: Partial<Omit<Behavior, 'strike'>> = {}): Behavior {
  return { strike, cover: { kind: 'none' }, rooted: false, passive: false, extras: [], ...more };
}

const MELEE: Strike = { kind: 'melee' };

/** 数据表 Race → 行为（WorldServer.MonGen.cs CreateMonster）。没列出的按普通近战。 */
export function behaviorOf(def: MonsterDef): Behavior {
  switch (def.race) {
    case 50: case 51: case 52:
      return behave(MELEE, { passive: true });
    case 82: case 119:
      return behave({ kind: 'spit' });
    case 85:
      return behave(MELEE, { cover: { kind: 'burrow', comeOut: 4, reach: 4 }, rooted: true });
    case 87:
      return behave({ kind: 'missile', range: 7, fly: def.raceImg === 15 ? AXE : def.raceImg === 22 ? THORN : null });
    case 104: case 112:
      return behave({ kind: 'missile', range: 7, fly: def.raceImg === 45 ? ARROW : null });
    case 90: case 105: case 106:
      return behave({ kind: 'gas' });
    case 91:
      return behave({ kind: 'magic' });
    case 92:
      return behave({ kind: 'split', physical: 0.5 }, { extras: [{ kind: 'cowKing' }] });
    case 94:
      return behave({ kind: 'lightning' });
    case 95:
      return behave(MELEE, { cover: { kind: 'digOut' } });
    case 96:
      return behave(MELEE, { extras: [{ kind: 'revive' }] });
    case 101:
      return behave(MELEE, { cover: { kind: 'statue', wakeAll: true } });
    case 102:
      return behave({ kind: 'split', physical: 0 }, { cover: { kind: 'statue', wakeAll: false }, extras: [{ kind: 'summonZuma' }] });
    case 103:
      return behave({ kind: 'spawn', child: '蝙蝠', below: false }, { rooted: true });
    case 107:
      return behave({ kind: 'area', range: 6, poison: true }, { cover: { kind: 'centipede' }, rooted: true });
    case 115:
      return behave({ kind: 'area', range: 16, poison: false }, { rooted: true });
    case 116:
      return behave({ kind: 'spawn', child: '爆裂蜘蛛', below: true }, { rooted: true });
    case 117:
      return behave({ kind: 'explode' });
    default:
      return behave(MELEE);
  }
}

const cache = new WeakMap<MonsterDef, Behavior>();
export function behavior(m: Monster): Behavior {
  let b = cache.get(m.def);
  if (!b) {
    b = behaviorOf(m.def);
    cache.set(m.def, b);
  }
  return b;
}

export function isGuard(d: MonsterDef): boolean {
  return d.name.includes('卫士') || d.name.includes('护卫') || d.name.includes('守卫');
}

/** 不朝向任何方向的怪（原版客户端 TKillingHerb / TBeeQueen 家族只有一个方向）。 */
export function fixedDir(m: Monster): boolean {
  return [33, 34, 35, 43].includes(m.def.raceImg);
}

// ---------------- 出生 ----------------

/** 刚刷出来：按行为设好藏身状态、复活次数等。 */
export function initMonster(w: World, m: Monster): void {
  const b = behavior(m);
  if (fixedDir(m)) m.dir = 0;
  switch (b.cover.kind) {
    case 'burrow': case 'digOut': case 'centipede':
      m.cover = 'buried';
      break;
    case 'statue':
      m.cover = 'stone';
      if (m.def.raceImg === 49) m.dir = 5;
      break;
    case 'none':
      m.cover = 'open';
  }
  if (b.extras.some((e) => e.kind === 'revive') && random(3) === 0) m.revivesLeft = random(3) + 1;
  m.bornAt = w.now;
}

// ---------------- 每次思考 ----------------

export function thinkMonster(w: World, m: Monster): void {
  if (w.now < m.nextThink || w.now < m.busyUntil) return;
  if (m.dead) {
    tryRevive(w, m);
    return;
  }
  m.nextThink = w.now + 100 + random(100);
  if (m.action.kind !== 'stand' && w.now >= m.action.startedAt + m.action.duration) {
    w.setAction(m, 'stand', 0);
  }
  const b = behavior(m);
  if (!updateCover(w, m, b)) return;
  for (const e of b.extras) extraTick(w, m, e);
  if (b.strike.kind === 'explode' && w.now - m.bornAt > 60_000) {
    explode(w, m);
    return;
  }
  if (b.strike.kind === 'spawn') {
    spawnTick(w, m, b.strike);
    return;
  }

  const guard = isGuard(m.def);
  const master = m.master ? w.actors.get(m.master) : undefined;
  let target = m.target ? w.actors.get(m.target) : undefined;
  if (target && (target.dead || !w.isEnemy(m, target) || chebyshev(m, target) > 14 || target.invisibleUntil > w.now)) {
    target = undefined;
    m.target = 0;
  }
  if (!target && (!b.passive || master)) {
    const range = guard ? 6 : master ? 8 : b.strike.kind === 'area' ? b.strike.range : 7;
    target = w.nearestEnemy(m, range);
    if (target) m.target = target.id;
  }
  if (target) {
    if (!attack(w, m, b, target) && !guard && !b.rooted) stepToward(w, m, target);
    return;
  }
  if (master && master.map === m.map) {
    if (chebyshev(m, master) > 2) stepToward(w, m, master);
    return;
  }
  if (master || guard || b.rooted) return;
  // 闲逛
  if (random(100) < 4 && w.move(m, random(8) as Dir, false)) {
    m.busyUntil = w.now + m.def.walkSpeed + random(1000);
  }
}

/** 先处理藏身状态。返回 false 表示这回合还藏着、不做别的。 */
function updateCover(w: World, m: Monster, b: Behavior): boolean {
  const c = b.cover;
  const near = (r: number, strict = false): Actor | undefined => {
    const e = w.nearestEnemy(m, r);
    return e && (!strict || chebyshev(m, e) < r) ? e : undefined;
  };
  if (m.cover === 'stone' && c.kind === 'statue') {
    if (!near(2)) return false;
    wake(w, m);
    if (c.wakeAll) {
      for (const a of w.actors.values()) {
        if (a.kind === 'monster' && a.cover === 'stone' && chebyshev(a, m) <= 7 && behavior(a).cover.kind === 'statue') wake(w, a);
      }
    }
    return false;
  }
  if (m.cover === 'buried') {
    if (c.kind === 'centipede' && w.now - m.coverAt < 10_000) return false;
    // 食人花、触龙神：距离 < 4 格钻出；僵尸：≤ 3 格
    const e = c.kind === 'burrow' ? near(c.comeOut, true) : c.kind === 'centipede' ? near(4, true) : near(3);
    if (!e) return false;
    surface(w, m);
    if (c.kind === 'centipede') m.hp = m.maxHp;
    return false;
  }
  if (c.kind === 'burrow') {
    const t = m.target ? w.actors.get(m.target) : undefined;
    if (!t || t.dead || chebyshev(m, t) > c.reach) {
      const e = w.nearestEnemy(m, c.reach);
      if (!e) {
        burrow(w, m);
        return false;
      }
      m.target = e.id;
    }
  }
  if (c.kind === 'centipede') {
    if (w.now - m.coverAt < 3000) return false;
    if (!w.nearestEnemy(m, 5) && w.now - m.coverAt > 10_000) {
      burrow(w, m);
      return false;
    }
  }
  return true;
}

function digAnim(w: World, m: Monster, kind: 'digup' | 'digdown'): number {
  const t = w.actTable(m);
  const info = t ? (kind === 'digdown' || !herbLike(m) ? t.ActDeath : t.ActWalk) : null;
  const dur = info ? Math.max(300, info[1] * info[3]) : 600;
  w.setAction(m, kind, dur);
  m.busyUntil = w.now + dur;
  return dur;
}

/** 原版 TKillingHerb 家族：钻出用走路帧、钻回用 ActDeath。 */
export function herbLike(m: Monster): boolean {
  return [13, 33, 34, 35].includes(m.def.raceImg);
}

function surface(w: World, m: Monster): void {
  m.cover = 'open';
  m.coverAt = w.now;
  digAnim(w, m, 'digup');
}

function burrow(w: World, m: Monster): void {
  m.target = 0;
  m.coverAt = w.now;
  digAnim(w, m, 'digdown');
  w.after(m.busyUntil - w.now, () => {
    if (!m.dead) m.cover = 'buried';
  });
}

function wake(w: World, m: Monster): void {
  m.cover = 'open';
  m.coverAt = w.now;
  digAnim(w, m, 'digup');
}

// ---------------- 出招 ----------------

function power(m: Monster): number {
  return m.dc[0] + random(Math.max(0, m.dc[1] - m.dc[0]) + 1);
}

function defenseOf(t: Actor): { ac: readonly [number, number]; mac: readonly [number, number]; agility: number } | null {
  if (t.kind === 'player') return { ac: t.stats.ac, mac: t.stats.mac, agility: t.stats.agility };
  if (t.kind === 'monster') return { ac: t.ac, mac: t.mac, agility: t.agility };
  return null;
}

function damage(w: World, m: Monster, t: Actor, amount: number): void {
  if (amount > 0) w.applyDamage(t, amount, m.id);
  else w.events.push({ type: 'miss', target: t.id });
}

function physical(w: World, m: Monster, t: Actor, pw: number, checkHit = true): void {
  const d = defenseOf(t);
  if (!d) return;
  if (checkHit && !hits(m.hit, d.agility)) {
    w.events.push({ type: 'miss', target: t.id });
    return;
  }
  damage(w, m, t, afterArmor(pw, d.ac));
}

function magical(w: World, m: Monster, t: Actor, pw: number): void {
  const d = defenseOf(t);
  if (d) damage(w, m, t, afterMagicArmor(pw, d.mac));
}

/** 攻击动作：按动作表放动画，播声音和特效。返回动画时长。 */
function swing(w: World, m: Monster, t: Actor | null): number {
  if (t && !fixedDir(m)) m.dir = dirTo(m, t);
  const tab = w.actTable(m);
  const info = tab ? (m.def.raceImg === 33 ? tab.ActCritical : tab.ActAttack) : null;
  const anim = info ? info[1] * info[3] : 600;
  w.setAction(m, 'attack', anim);
  m.busyUntil = w.now + anim;
  m.lastAttack = w.now;
  w.sound(monsterSound(m.def.appr, 2), m.pos);
  attackFx(w, m, t, info ? info[1] : 6, info ? info[3] : 100);
  return anim;
}

/** 攻击时身上的特效（原版 TGasKuDeGi.LoadSurface 等：图库、起始帧、方向间隔 10）。 */
const ATTACK_FX: Record<number, { lib: string; base: number; dir16?: boolean; extra?: number; frames?: number }> = {
  16: { lib: 'mon3', base: 1444 }, // 洞蛆毒气（晚一帧开始）
  20: { lib: 'mon4', base: 1800, extra: 1 }, // 火焰沃玛
  21: { lib: 'mon4', base: 1900 }, // 沃玛教主
  40: { lib: 'mon5', base: 350, dir16: true }, // 僵尸电光
  49: { lib: 'mon7', base: 1680, frames: 9 }, // 祖玛教主
  52: { lib: 'mon4', base: 3590 }, // 楔蛾
  53: { lib: 'mon3', base: 3590 }, // 粪虫
};

function attackFx(w: World, m: Monster, t: Actor | null, frames: number, ftime: number): void {
  const f = ATTACK_FX[m.def.raceImg];
  if (!f) return;
  const dir = f.dir16 && t ? dir16(m, t) : f.dir16 ? m.dir * 2 : m.dir;
  const n = f.frames ?? frames + (f.extra ?? 0);
  w.effects.push({
    lib: f.lib, start: f.base + dir * 10, frames: n, frameTime: ftime, at: { actor: m.id },
    blend: true, startedAt: w.now, duration: n * ftime,
  });
}

function ready(w: World, m: Monster): boolean {
  return w.now - m.lastAttack >= m.attackInterval;
}

/** 能打就打（含冷却中原地等）；返回 false 表示够不着，要走近。 */
function attack(w: World, m: Monster, b: Behavior, t: Actor): boolean {
  const d = chebyshev(m, t);
  const s = b.strike;
  switch (s.kind) {
    case 'melee': case 'magic': case 'split': case 'gas': case 'explode': {
      if (d > 1) return false;
      if (!fixedDir(m)) m.dir = dirTo(m, t);
      if (!ready(w, m)) return true;
      if (s.kind === 'explode') {
        explode(w, m);
        return true;
      }
      swing(w, m, t);
      const pw = power(m);
      if (s.kind === 'melee') physical(w, m, t, pw);
      else if (s.kind === 'magic') magical(w, m, t, pw);
      else if (s.kind === 'split') {
        const ph = Math.round(pw * s.physical);
        if (ph > 0) physical(w, m, t, ph, false);
        magical(w, m, t, pw - ph);
      } else gas(w, m, t, pw);
      return true;
    }
    case 'spit': {
      const dir = dirTo(m, t);
      const inLine = d <= 2 && (step(m, dir, d).x === t.x && step(m, dir, d).y === t.y);
      if (!inLine) return false;
      m.dir = dir;
      if (!ready(w, m)) return true;
      swing(w, m, t);
      const pw = power(m);
      for (const k of [1, 2]) {
        const c = step(m, dir, k);
        const a = w.occupant(c.x, c.y);
        const def = a && defenseOf(a);
        if (!a || !def || !w.isEnemy(m, a) || !hits(m.hit, def.agility)) continue;
        magical(w, m, a, pw);
        if (!a.dead && random(20) === 0) poisonGreen(w, a, m, 30_000, 1);
      }
      return true;
    }
    case 'missile': {
      if (d < 1 || d > s.range) return false;
      if (!ready(w, m)) return true;
      swing(w, m, t);
      const delay = d * 50 + 300;
      if (s.fly) {
        w.effects.push({
          lib: s.fly.lib, start: s.fly.base + dir16(m, t) * s.fly.stride, frames: s.fly.frames, frameTime: 60,
          at: { actor: t.id }, from: m.pos, blend: false, startedAt: w.now, duration: delay,
        });
      }
      const pw = power(m);
      w.after(delay, () => {
        if (!t.dead && !m.dead) physical(w, m, t, pw, false);
      });
      return true;
    }
    case 'lightning': {
      if (d <= 2 && random(3) === 0 && stepAway(w, m, t)) return true;
      if (d >= 6) return false;
      if (!ready(w, m)) return true;
      swing(w, m, t);
      const dir = dirTo(m, t);
      const pw = power(m);
      const hitIds = new Set<number>();
      for (let k = 1; k <= 8; k++) {
        const c = step(m, dir, k);
        const a = w.occupant(c.x, c.y);
        if (a && !hitIds.has(a.id) && w.isEnemy(m, a)) {
          hitIds.add(a.id);
          magical(w, m, a, pw);
        }
      }
      return true;
    }
    case 'area': {
      if (d >= s.range) return true; // 不动的怪，等人走近
      if (!ready(w, m)) return true;
      swing(w, m, t);
      const pw = power(m);
      const delay = s.poison ? 600 : 200;
      for (const a of [...w.actors.values()]) {
        if (!w.isEnemy(m, a) || chebyshev(m, a) >= s.range) continue;
        w.after(delay, () => {
          if (a.dead || m.dead) return;
          magical(w, m, a, pw);
          if (s.poison && !a.dead && random(4) === 0) {
            if (random(3) !== 0) poisonGreen(w, a, m, 60_000, 3);
            else paralyze(w, a, 5000);
          }
        });
      }
      return true;
    }
    case 'spawn':
      return true;
  }
}

function gas(w: World, m: Monster, t: Actor, pw: number): void {
  const d = defenseOf(t);
  if (!d) return;
  if (!hits(m.hit, d.agility)) {
    w.events.push({ type: 'miss', target: t.id });
    return;
  }
  magical(w, m, t, pw);
  if (!t.dead && random(20) === 0) paralyze(w, t, 5000);
}

function poisonGreen(w: World, t: Actor, m: Monster, ms: number, pw: number): void {
  t.poison = { kind: 'green', until: w.now + ms, power: pw, nextTick: w.now + 2000, by: m.id };
  if (t.kind === 'player') w.events.push({ type: 'msg', text: '你中毒了', color: 'bad' });
}

/** 麻痹（原版 STONE 状态）：这段时间不能动。 */
function paralyze(w: World, t: Actor, ms: number): void {
  t.paralyzedUntil = Math.max(t.paralyzedUntil, w.now + ms);
  t.busyUntil = Math.max(t.busyUntil, t.paralyzedUntil);
  if (t.kind === 'player') w.events.push({ type: 'msg', text: `你被麻痹了（${ms / 1000} 秒）`, color: 'bad' });
}

function explode(w: World, m: Monster): void {
  const pw = power(m);
  for (const a of [...w.actors.values()]) {
    if (!w.isEnemy(m, a) || chebyshev(m, a) > 1) continue;
    const d = defenseOf(a);
    if (!d) continue;
    const dmg = afterArmor(Math.floor(pw / 2), d.ac) + afterMagicArmor(Math.floor(pw / 2), d.mac);
    damage(w, m, a, dmg);
  }
  w.killWithoutReward(m);
}

function spawnTick(w: World, m: Monster, s: Extract<Strike, { kind: 'spawn' }>): void {
  m.minions = m.minions.filter((id) => {
    const a = w.actors.get(id);
    return a !== undefined && !a.dead;
  });
  if (!ready(w, m)) return;
  m.lastAttack = w.now;
  const t = w.nearestEnemy(m, 9);
  if (!t || m.minions.length >= 15) return;
  swing(w, m, null);
  const def = w.catalog.monstersByName.get(s.child);
  if (!def) return;
  w.after(500, () => {
    if (m.dead) return;
    const at = s.below ? { x: m.x, y: m.y + 1 } : m.pos;
    const c = w.spawnMonster(def, -1, at, 1);
    if (c) {
      c.target = t.id;
      m.minions.push(c.id);
    }
  });
}

// ---------------- 附加本领 ----------------

function extraTick(w: World, m: Monster, e: Extra): void {
  switch (e.kind) {
    case 'revive':
      return;
    case 'summonZuma': {
      if (w.now - m.extraTick < 1000) return;
      m.extraTick = w.now;
      const level = Math.floor((m.hp * 5) / m.maxHp);
      if (m.hp >= m.maxHp) m.danger = 5;
      if (m.danger > level && m.danger > 0 && m.target) {
        m.danger--;
        m.minions = m.minions.filter((id) => !(w.actors.get(id)?.dead ?? true));
        const at = step(m, m.dir);
        const n = random(6) + 6;
        for (let i = 0; i < n && m.minions.length < 30; i++) {
          const def = w.catalog.monstersByName.get(ZUMA[random(ZUMA.length)]!);
          const c = def && w.spawnMonster(def, -1, at, 2);
          if (c) {
            c.cover = 'open';
            c.target = m.target;
            m.minions.push(c.id);
          }
        }
      }
      return;
    }
    case 'cowKing':
      cowKing(w, m);
  }
}

/** 沃玛教主 CowKingMonster.Run：每 30 秒检查一次。 */
function cowKing(w: World, m: Monster): void {
  if (w.now - m.extraTick < 30_000) return;
  m.extraTick = w.now;
  const t = m.target ? w.actors.get(m.target) : undefined;
  if (t && !t.dead) {
    let blocked = 0;
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      if ((dx || dy) && !w.canEnter(m.x + dx, m.y + dy)) blocked++;
    }
    if (blocked >= 5) {
      const back = step(t, opposite(t.dir));
      const to = w.canEnter(back.x, back.y) ? back : w.freeCellNear(t, 3);
      if (to) {
        w.teleportActor(m, to);
        return;
      }
    }
  }
  const before = m.danger;
  m.danger = 7 - Math.floor(m.hp / Math.max(1, Math.floor(m.maxHp / 7)));
  if (m.danger >= 2 && m.danger !== before) {
    m.rage = 'ready';
    m.rageAt = w.now;
  }
  if (m.rage === 'ready') {
    if (w.now - m.rageAt < 8000) m.attackInterval = 10_000;
    else {
      m.rage = 'crazy';
      m.rageAt = w.now;
    }
  }
  if (m.rage === 'crazy') {
    if (w.now - m.rageAt < 8000) {
      m.attackInterval = 500;
      m.walkInterval = 400;
    } else {
      m.rage = 'calm';
      m.attackInterval = m.def.attackSpeed;
      m.walkInterval = m.def.walkSpeed;
    }
  }
}

/** 死了的僵尸过几秒爬起来（ZilKinZombi.Run）。 */
function tryRevive(w: World, m: Monster): void {
  if (m.revivesLeft <= 0 || m.reviveAt === 0 || w.now < m.reviveAt) return;
  if (!w.canEnter(m.x, m.y)) {
    m.reviveAt = w.now + 1000;
    return;
  }
  m.revivesLeft--;
  m.reviveAt = 0;
  m.maxHp = Math.max(1, m.maxHp >> 1);
  m.hp = m.maxHp;
  m.expScale /= 2;
  w.reviveMonster(m);
  digAnim(w, m, 'digup');
}

/** 怪死时：会复活的定好复活时间，尸体留到复活。 */
export function onMonsterDeath(w: World, m: Monster): void {
  if (m.revivesLeft > 0) {
    m.reviveAt = w.now + (random(20) + 4) * 1000;
    m.removeAt = Math.max(m.removeAt, m.reviveAt + 1000);
  }
}

// ---------------- 移动 ----------------

export function stepToward(w: World, m: Monster, t: Pos): void {
  if (w.move(m, dirTo(m, t), false)) {
    m.busyUntil = w.now + m.walkInterval;
    return;
  }
  const path = w.findPathFor(m, t);
  if (path?.[0] !== undefined && w.move(m, path[0], false)) m.busyUntil = w.now + m.walkInterval;
  else m.busyUntil = w.now + 500;
}

function stepAway(w: World, m: Monster, t: Pos): boolean {
  const away = opposite(dirTo(m, t));
  for (const d of [away, ((away + 1) % 8) as Dir, ((away + 7) % 8) as Dir]) {
    if (w.move(m, d, false)) {
      m.busyUntil = w.now + m.walkInterval;
      return true;
    }
  }
  return false;
}
