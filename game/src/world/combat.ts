/**
 * 玩家的攻击与技能结算。公式出处：ref/Mir176 PlayObject.Attack.cs（剑术）、MagicManager.cs（魔法）。
 */
import { type Dir, type Pos, chebyshev, dir16, dirTo, step } from '../core/geom';
import type { SkillDef } from '../data/defs';
import type { Actor, HitStyle, LearnedSkill, Monster, Player } from './entities';
import { itemKind } from './items';
import { trainNeed } from './rates';
import {
  afterArmor, afterMagicArmor, attackPower, hits, magicBase, random, rpow, skillPower, skillPower13,
} from './rules';
import { S, magicSound } from './sounds';
import { SPELL_ANIM_MS, SPELL_INTERVAL, type VisualEffect, type World } from './world';

// ---------------- 技能熟练度 ----------------

export function skillOf(p: Player, id: number): LearnedSkill | undefined {
  return p.skills.find((s) => s.id === id);
}

/** 练技能：熟练度够了且等级够了就升级（最高 3 级）。 */
export function train(w: World, p: Player, s: LearnedSkill, amount: number): void {
  const def = w.catalog.skillsById.get(s.id);
  if (!def || s.level >= 3) return;
  if (p.level < def.needLevel[s.level]!) return;
  s.train += amount;
  if (s.train >= trainNeed(def, s.level)) {
    s.train = 0;
    s.level++;
    w.recalc(p);
    w.events.push({ type: 'msg', text: `${def.name} 升到 ${s.level} 级`, color: 'good' });
  }
}

// ---------------- 普通攻击与剑术 ----------------

function physicalHit(w: World, p: Player, t: Actor, power: number, ignoreArmor: boolean): boolean {
  if (t.kind === 'npc' || !w.isEnemy(p, t)) return false;
  const agility = t.kind === 'monster' ? t.agility : t.stats.agility;
  if (!hits(p.stats.hit, agility)) {
    w.events.push({ type: 'miss', target: t.id });
    return false;
  }
  const ac = t.kind === 'monster' ? t.ac : t.stats.ac;
  const dmg = ignoreArmor ? power : afterArmor(power, ac);
  if (dmg <= 0) {
    w.events.push({ type: 'miss', target: t.id });
    return false;
  }
  w.applyDamage(t, dmg, p.id);
  return true;
}

export function resolvePlayerHit(w: World, p: Player, dir: Dir, style: HitStyle): void {
  let power = attackPower(p.stats.dc[0], p.stats.dc[1] - p.stats.dc[0]);
  // 幸运：有机会直接打出攻击上限；诅咒：有机会只打出下限
  const luck = p.stats.luck;
  if (luck > 0 && random(10 - Math.min(9, luck)) === 0) power = p.stats.dc[1];
  else if (luck < 0 && random(10 - Math.min(9, -luck)) === 0) power = p.stats.dc[0];
  const front = step(p, dir);
  const target = w.occupant(front.x, front.y);
  switch (style) {
    case 'power': {
      // 攻杀：额外 + (5 + 技能等级)（HitPlus）
      const s = skillOf(p, 7)!;
      power += 5 + s.level;
      p.powerHitReady = false;
      break;
    }
    case 'fire': {
      // 烈火：威力 ×(1 + (4 + 等级×4)/10)（HitDouble）
      const s = skillOf(p, 26)!;
      power += Math.round((power / 100) * (4 + s.level * 4) * 10);
      p.fireReady = false;
      if (target) train(w, p, s, 1);
      break;
    }
    case 'wide':
      p.mp -= 3;
      break;
    default:
      break;
  }
  if (target) physicalHit(w, p, target, power, false);

  if (style === 'long') {
    // 刺杀：隔一格的目标无视防御（AttackDirect）
    const s = skillOf(p, 12)!;
    const far = step(p, dir, 2);
    const t2 = w.occupant(far.x, far.y);
    if (t2 && w.isEnemy(p, t2)) {
      physicalHit(w, p, t2, power, true);
      train(w, p, s, 1);
    }
  }
  if (style === 'wide') {
    // 半月：身边一圈（除了正前方）都打到
    const s = skillOf(p, 25)!;
    const pw = Math.round((power * (s.level + 1)) / 4) + 1;
    for (const k of [1, 2, 6, 7, 3, 5, 4]) {
      const c = step(p, ((dir + k) % 8) as Dir);
      const t = w.occupant(c.x, c.y);
      if (t && w.isEnemy(p, t)) physicalHit(w, p, t, pw, false);
    }
    train(w, p, s, 1);
  }

  // 练剑术 & 攻杀计数（PlayObject.Attack.cs:360、943）
  if (target && w.isEnemy(p, target)) {
    for (const id of [3, 4]) {
      const s = skillOf(p, id);
      if (s) train(w, p, s, random(3) + 1);
    }
  }
  const ph = skillOf(p, 7);
  if (ph && p.equip.weapon) {
    if (style === 'power') train(w, p, ph, 1);
    if (p.powerHitCount <= 0) {
      p.powerHitCount = 7 - ph.level;
      p.powerHitPoint = random(p.powerHitCount);
    }
    p.powerHitCount--;
    if (p.powerHitCount === p.powerHitPoint) p.powerHitReady = true;
  }
  if (p.equip.weapon) {
    p.equip.weapon.dura = Math.max(0, p.equip.weapon.dura - (target ? 1 : 0) * (random(3) === 0 ? 1 : 0));
  }
}

// ---------------- 魔法 ----------------

export type SpellTarget = { pos: Pos; actor?: Actor };

export type CastResult = { ok: true } | { ok: false; why: string };

/** 魔法耗蓝（MagicManager：Spell + DefSpell×等级/... 简化为 原版客户端显示的公式 spell + defSpell×level/4 取整）。 */
export function manaCost(def: SkillDef, level: number): number {
  return Math.round(def.spell / 4 * (level + 1) + (def.defSpell / 4) * (level + 1)) || def.spell;
}

function fx(w: World, e: Omit<VisualEffect, 'startedAt' | 'duration' | 'blend'> & { blend?: boolean; duration?: number }): void {
  w.effects.push({
    blend: true,
    ...e,
    startedAt: w.now,
    duration: e.duration ?? e.frames * e.frameTime,
  });
}

/** 施法动画：在人物身上播放的起手特效（EffectBase，10 帧）。 */
function castFx(w: World, p: Player, def: SkillDef): void {
  const base = CAST_BASE[def.effect];
  if (base === undefined) return;
  fx(w, { lib: base.lib, start: base.idx, frames: base.frames ?? 10, frameTime: 60, at: { actor: p.id } });
}

const CAST_BASE: Record<number, { lib: string; idx: number; frames?: number }> = {
  1: { lib: 'magic', idx: 0 }, 2: { lib: 'magic', idx: 200 }, 3: { lib: 'magic', idx: 400 },
  4: { lib: 'magic', idx: 600 }, 6: { lib: 'magic', idx: 900 }, 7: { lib: 'magic', idx: 920 },
  9: { lib: 'magic2', idx: 20, frames: 3 }, 15: { lib: 'magic', idx: 1500 }, 16: { lib: 'magic', idx: 1520 },
  18: { lib: 'magic', idx: 1560 }, 19: { lib: 'magic', idx: 1590 }, 29: { lib: 'magic', idx: 3880 },
  31: { lib: 'magic', idx: 3840 }, 27: { lib: 'magic', idx: 1790 },
};

function hitMagic(w: World, p: Player, t: Actor, power: number): void {
  if (!w.isEnemy(p, t)) return;
  const mac = t.kind === 'monster' ? t.mac : t.kind === 'player' ? t.stats.mac : ([0, 0] as const);
  const dmg = afterMagicArmor(power, mac);
  if (dmg > 0) w.applyDamage(t, dmg, p.id);
  else w.events.push({ type: 'miss', target: t.id });
}

function enemiesAround(w: World, p: Player, c: Pos, r: number): Actor[] {
  const out: Actor[] = [];
  for (const a of w.actors.values()) if (w.isEnemy(p, a) && chebyshev(a, c) <= r) out.push(a);
  return out;
}

/** 用掉一张护身符/一份毒药（放在左手镯位）。 */
function consumeAmulet(p: Player, w: World, poison: 0 | 1 | 2 | 'any'): boolean {
  const it = p.equip.braceletL;
  if (!it) return false;
  const k = itemKind(w.catalog.item(it.name));
  if (k.kind !== 'amulet') return false;
  if (poison !== 'any' && k.poison !== poison) return false;
  it.dura -= 100;
  if (it.dura <= 0) {
    p.equip.braceletL = null;
    w.events.push({ type: 'msg', text: `${it.name} 用完了`, color: 'bad' });
  }
  return true;
}

function amuletPoison(p: Player, w: World): 0 | 1 | 2 | null {
  const it = p.equip.braceletL;
  if (!it) return null;
  const k = itemKind(w.catalog.item(it.name));
  return k.kind === 'amulet' ? k.poison : null;
}

/** 放技能。成功则扣蓝、进入施法动作、加熟练度。 */
export function castSpell(w: World, p: Player, skillId: number, tgt: SpellTarget): CastResult {
  const s = skillOf(p, skillId);
  const def = w.catalog.skillsById.get(skillId);
  if (!s || !def) return { ok: false, why: '还没学会' };
  if (p.dead) return { ok: false, why: '你已经死了' };
  if (w.now < p.busyUntil) return { ok: false, why: '' };
  if ((p.cooldown.get(skillId) ?? 0) > w.now) return { ok: false, why: '' };
  const cost = manaCost(def, s.level);
  if (p.mp < cost) return { ok: false, why: '魔法值不够' };
  const MC = p.stats.mc;
  const SC = p.stats.sc;
  const mcPower = (): number => attackPower(skillPower(magicBase(def.power), s.level, def.defPower) + MC[0], MC[1] - MC[0] + 1);
  const t = tgt.actor && !tgt.actor.dead ? tgt.actor : undefined;
  if (t) p.dir = dirTo(p, t);
  else if (tgt.pos.x !== p.x || tgt.pos.y !== p.y) p.dir = dirTo(p, tgt.pos);

  const need = (cond: boolean, why: string): CastResult | null => (cond ? null : { ok: false, why });
  let r: CastResult | null = null;
  switch (skillId) {
    case 1: // 火球术
    case 5: // 大火球
    case 11: { // 雷电术
      r = need(!!t && w.isEnemy(p, t), '需要选中一个目标');
      if (r) return r;
      if (!w.map.flyable(t!.x, t!.y)) return { ok: false, why: '被挡住了' };
      let power = mcPower();
      if (skillId === 11 && t!.kind === 'monster' && t!.def.undead) power = Math.round(power * 1.5);
      const delay = skillId === 11 ? 100 : 80 * chebyshev(p, t!);
      w.sound(magicSound(skillId, 1));
      w.after(delay, () => {
        w.sound(magicSound(skillId, 2), t!.pos);
        hitMagic(w, p, t!, power);
      });
      if (skillId === 11) {
        fx(w, { lib: 'magic2', start: 10, frames: 5, frameTime: 80, at: { actor: t!.id } });
      } else {
        const base = skillId === 1 ? 0 : 400;
        fx(w, { lib: 'magic', start: base + 10 + dir16(p, t!) * 10, frames: 6, frameTime: 50, at: { actor: t!.id }, from: p.pos, duration: delay });
        w.after(delay, () => fx(w, { lib: 'magic', start: base + 170, frames: 10, frameTime: 50, at: { actor: t!.id } }));
      }
      break;
    }
    case 33: { // 冰咆哮：目标周围 1 格
      const c = t ? t.pos : tgt.pos;
      const power = mcPower();
      for (const e of enemiesAround(w, p, c, 1)) hitMagic(w, p, e, power);
      fx(w, { lib: 'magic', start: 3850, frames: 20, frameTime: 60, at: c });
      break;
    }
    case 8: { // 抗拒火环：推开身边的怪
      for (const e of enemiesAround(w, p, p, 1)) {
        if (e.kind !== 'monster' || e.def.level > p.level) continue;
        const d = dirTo(p, e);
        w.move(e, d, false);
      }
      fx(w, { lib: 'magic', start: 900, frames: 6, frameTime: 80, at: { actor: p.id } });
      break;
    }
    case 9: { // 地狱火：直线 5 格
      const power = mcPower();
      const d = t ? dirTo(p, t) : dirTo(p, tgt.pos);
      for (let k = 1; k <= 5; k++) {
        const c = step(p, d, k);
        const a = w.occupant(c.x, c.y);
        if (a) hitMagic(w, p, a, power);
        fx(w, { lib: 'magic', start: 930, frames: 6, frameTime: 70, at: c });
      }
      break;
    }
    case 31: { // 魔法盾
      p.shieldUntil = w.now + (skillPower(15 + rpow(MC), s.level, def.defPower)) * 1000;
      p.shieldLevel = s.level;
      w.events.push({ type: 'msg', text: '魔法盾开启', color: 'system' });
      break;
    }
    case 20: { // 诱惑之光
      r = need(!!t && t.kind === 'monster' && !t.master, '需要选中一只怪');
      if (r) return r;
      const m = t as Monster;
      const ok = m.def.level < p.level - 1 + s.level && random(4 - s.level + 1) === 0 && p.pets.length < 5 && m.def.race !== 0;
      fx(w, { lib: 'magic', start: 1560 + 10, frames: 10, frameTime: 60, at: { actor: m.id } });
      if (ok) {
        m.master = p.id;
        m.target = 0;
        p.pets.push(m.id);
        w.events.push({ type: 'msg', text: `${m.def.name} 成了你的宝宝`, color: 'good' });
      }
      break;
    }
    case 2: { // 治愈术
      const who = t && !w.isEnemy(p, t) && t.kind !== 'npc' ? t : p;
      const heal = attackPower(skillPower(magicBase(def.power), s.level, def.defPower) + SC[0] * 2, (SC[1] - SC[0]) * 2 + 1);
      const maxHp = who.kind === 'player' ? who.stats.maxHp : who.kind === 'monster' ? who.maxHp : 1;
      const v = Math.min(heal, maxHp - who.hp);
      who.hp += Math.max(0, v);
      w.events.push({ type: 'heal', target: who.id, amount: v });
      fx(w, { lib: 'magic', start: 200, frames: 10, frameTime: 60, at: { actor: who.id } });
      break;
    }
    case 6: { // 施毒术：需要灰/黄药粉
      r = need(!!t && w.isEnemy(p, t), '需要选中一个目标');
      if (r) return r;
      const kind = amuletPoison(p, w);
      if (kind !== 1 && kind !== 2) return { ok: false, why: '左手镯位要装备灰色或黄色药粉' };
      consumeAmulet(p, w, kind);
      const power = skillPower13(kind === 1 ? 40 : 30, s.level, def.defPower) + rpow(SC) * 2;
      t!.poison = { kind: kind === 1 ? 'green' : 'red', until: w.now + power * 1000, power: Math.max(1, Math.round(rpow(SC) / 2) + 1), nextTick: w.now + 2000, by: p.id };
      if (t!.kind === 'monster' && !t!.target) t!.target = p.id;
      fx(w, { lib: 'magic', start: 600 + 10, frames: 10, frameTime: 60, at: { actor: t!.id } });
      break;
    }
    case 13: { // 灵魂火符：需要护身符
      r = need(!!t && w.isEnemy(p, t), '需要选中一个目标');
      if (r) return r;
      if (amuletPoison(p, w) !== 0) return { ok: false, why: '左手镯位要装备护身符' };
      consumeAmulet(p, w, 0);
      const power = attackPower(skillPower(magicBase(def.power), s.level, def.defPower) + SC[0], SC[1] - SC[0] + 1);
      const delay = 80 * chebyshev(p, t!);
      fx(w, { lib: 'magic', start: 1160 + dir16(p, t!) * 10, frames: 3, frameTime: 60, at: { actor: t!.id }, from: p.pos, duration: delay });
      w.sound(magicSound(13, 1));
      w.after(delay, () => {
        w.sound(magicSound(13, 2), t!.pos);
        hitMagic(w, p, t!, power);
        fx(w, { lib: 'magic', start: 1320, frames: 10, frameTime: 50, at: { actor: t!.id } });
      });
      break;
    }
    case 17: // 召唤骷髅
    case 30: { // 召唤神兽
      if (amuletPoison(p, w) !== 0) return { ok: false, why: '左手镯位要装备护身符' };
      const name = skillId === 17 ? '变异骷髅' : '神兽';
      const existing = p.pets.map((id) => w.actors.get(id)).find((a) => a?.kind === 'monster' && a.def.name === name);
      if (existing) return { ok: false, why: `已经有${name}了` };
      const mdef = w.catalog.monstersByName.get(name);
      if (!mdef) return { ok: false, why: '召唤失败' };
      consumeAmulet(p, w, 0);
      const at = step(p, p.dir);
      const m = w.spawnMonster(mdef, -1, at, 1);
      if (m) {
        m.master = p.id;
        m.petLevel = s.level;
        const boost = 1 + s.level * 0.35;
        m.maxHp = Math.round(mdef.hp * boost);
        m.hp = m.maxHp;
        // 神兽技能等级只加血、不加攻击（#1258）
        const dcBoost = name === '神兽' ? 1 : boost;
        m.dc = [Math.round(mdef.dc[0] * dcBoost), Math.round(mdef.dc[1] * dcBoost)];
        p.pets.push(m.id);
        fx(w, { lib: 'magic', start: 1500, frames: 10, frameTime: 60, at: { actor: m.id } });
      }
      break;
    }
    case 18: { // 隐身术
      if (amuletPoison(p, w) !== 0) return { ok: false, why: '左手镯位要装备护身符' };
      consumeAmulet(p, w, 0);
      const sec = skillPower13(30, s.level, def.defPower) + rpow(SC) * 3;
      p.invisibleUntil = w.now + sec * 1000;
      for (const a of w.actors.values()) if (a.kind === 'monster' && a.target === p.id && !a.def.coolEye) a.target = 0;
      w.events.push({ type: 'msg', text: `隐身 ${sec} 秒（打怪或被打会现身）`, color: 'system' });
      break;
    }
    case 26: { // 烈火剑法：蓄力，下一刀生效
      if (p.fireReady) return { ok: false, why: '烈火已经准备好了' };
      p.fireReady = true;
      p.cooldown.set(26, w.now + 10_000);
      w.sound(S.fireHitReady);
      w.events.push({ type: 'msg', text: '烈火剑法准备好了', color: 'system' });
      p.mp -= cost;
      return { ok: true }; // 不进入施法动作
    }
    case 25: // 半月弯刀：开关
      p.wideHitOn = !p.wideHitOn;
      w.events.push({ type: 'msg', text: p.wideHitOn ? '开启半月弯刀' : '关闭半月弯刀', color: 'system' });
      return { ok: true };
    case 12: // 刺杀剑术：开关
      p.longHitOn = !p.longHitOn;
      w.events.push({ type: 'msg', text: p.longHitOn ? '开启刺杀剑术' : '关闭刺杀剑术', color: 'system' });
      return { ok: true };
    default:
      return { ok: false, why: '这个技能还没做' };
  }
  p.mp -= cost;
  w.sound(magicSound(skillId, 0));
  castFx(w, p, def);
  p.action = { kind: 'spell', startedAt: w.now, duration: SPELL_ANIM_MS, spellEffect: def.effect };
  p.busyUntil = w.now + SPELL_INTERVAL;
  if (def.delay) p.cooldown.set(skillId, w.now + Math.max(SPELL_INTERVAL, def.delay));
  train(w, p, s, 1 + random(3));
  if (p.invisibleUntil > w.now && skillId !== 18) p.invisibleUntil = 0;
  return { ok: true };
}
