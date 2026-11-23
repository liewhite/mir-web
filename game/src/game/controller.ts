/**
 * 玩家意图 → 世界指令。鼠标/触屏只设置「想做什么」，这里每帧在玩家空闲时推进一步。
 * 自动喝药、自动捡物品、自动还手、自动烈火也在这里（按设置开关）。
 */
import { type Dir, type Pos, chebyshev, dirTo, samePos, step } from '../core/geom';
import { loadMap } from '../assets/mapdata';
import type { Actor, GroundItem, Player } from '../world/entities';
import { isGold } from '../world/entities';
import { itemKind } from '../world/items';
import { castSpell, skillOf } from '../world/combat';
import { isPassive, type World } from '../world/world';
import type { Settings } from './settings';

export type Intent =
  | { kind: 'none' }
  /** 按住鼠标/手指：朝这个格子方向一直走（跑） */
  | { kind: 'steer'; target: Pos; run: boolean }
  /** 手机摇杆：朝这个方向一直走（跑） */
  | { kind: 'stick'; dx: number; dy: number; run: boolean }
  /** 走到某格（寻路） */
  | { kind: 'goto'; target: Pos; path: Dir[]; run: boolean }
  | { kind: 'attack'; target: number; once: boolean }
  | { kind: 'talk'; npc: number }
  | { kind: 'pickup'; item: number };

export class Controller {
  intent: Intent = { kind: 'none' };
  /** 最近选中的目标（点过的怪），技能默认打它 */
  focus = 0;
  private lastPotion = 0;
  private loadingMap = false;
  /** 打开 NPC 对话的回调（UI 实现） */
  onTalk: (npcId: number) => void = () => {};
  onMessage: (text: string, color?: 'system' | 'good' | 'bad' | 'item') => void = () => {};
  /** 设置改了（存档、刷新显示） */
  onSettingsChanged: () => void = () => {};

  constructor(readonly world: World, public settings: Settings) {}

  get me(): Player {
    return this.world.player;
  }

  // ---------------- 输入入口 ----------------

  steer(target: Pos, run: boolean): void {
    this.intent = { kind: 'steer', target, run };
  }

  private repaths = 0;

  goTo(target: Pos, run = true): void {
    this.repaths = 0;
    const path = this.world.pathTo(this.me, target) ?? [];
    this.intent = { kind: 'goto', target, path, run };
  }

  attack(target: Actor): void {
    if (target.kind === 'npc') {
      this.intent = { kind: 'talk', npc: target.id };
      return;
    }
    if (!this.world.isEnemy(this.me, target)) return;
    this.focus = target.id;
    this.intent = { kind: 'attack', target: target.id, once: !this.settings.stickyAttack };
  }

  pickup(item: GroundItem): void {
    this.intent = { kind: 'pickup', item: item.id };
  }

  stop(): void {
    if (this.intent.kind === 'steer' || this.intent.kind === 'stick') this.intent = { kind: 'none' };
  }

  /** 摇杆推向某个方向（dx、dy 各是 -1/0/1）。 */
  stick(dx: number, dy: number, run: boolean): void {
    this.intent = { kind: 'stick', dx, dy, run };
  }

  /**
   * 手机点角色：点 NPC 走过去对话；点怪只锁定为目标（攻击键、技能打它），
   * 正在打别的怪时改打它。
   */
  lock(target: Actor): void {
    if (target.kind === 'npc') {
      this.intent = { kind: 'talk', npc: target.id };
      return;
    }
    if (target.kind !== 'monster') return;
    this.focus = target.id;
    if (this.intent.kind === 'attack' && this.world.isEnemy(this.me, target)) this.attack(target);
  }

  /** 取消锁定（也不再打它）。 */
  unlock(): void {
    if (this.intent.kind === 'attack' && this.intent.target === this.focus) this.intent = { kind: 'none' };
    this.focus = 0;
  }

  /** 锁定的目标（还活着、在这张图上）。 */
  focused(): Actor | undefined {
    const a = this.world.actors.get(this.focus);
    return a && !a.dead && a.map === this.me.map ? a : undefined;
  }

  /** 放技能：目标优先用鼠标下的怪，其次当前攻击目标，否则鼠标所在格。 */
  cast(skillId: number, hover: Actor | undefined, cell: Pos): void {
    const w = this.world;
    const cur = w.actors.get(this.intent.kind === 'attack' ? this.intent.target : this.focus);
    const target = hover && !hover.dead ? hover : cur && !cur.dead ? cur : undefined;
    if (target && w.isEnemy(this.me, target)) this.focus = target.id;
    const r = castSpell(w, this.me, skillId, { pos: target ? target.pos : cell, actor: target });
    if (!r.ok && r.why) this.onMessage(r.why, 'bad');
    if (r.ok && target && w.isEnemy(this.me, target) && this.me.job !== 0) {
      // 法师/道士放完技能，把它当作当前目标（方便连按）
      this.intent = { kind: 'none' };
    }
  }

  castByKey(key: number, hover: Actor | undefined, cell: Pos): void {
    const s = this.me.skills.find((x) => x.key === key);
    if (s) this.cast(s.id, hover, cell);
  }

  /** 触屏放技能：没有鼠标指着的怪，就打当前目标，再没有就打最近的怪。 */
  castByKeyAuto(key: number): void {
    const s = this.me.skills.find((x) => x.key === key);
    if (s) this.castSkillAuto(s.id);
  }

  castSkillAuto(skillId: number): void {
    const t = this.autoTarget();
    this.cast(skillId, t, t ? t.pos : this.me.pos);
  }

  /** 触屏攻击键：打当前目标，没有就打身边最近的怪。 */
  attackNearest(): void {
    const t = this.autoTarget();
    if (t) this.attack(t);
    else this.onMessage('附近没有怪', 'bad');
  }

  /** 当前目标（还活着、不太远），否则 10 格内最近的敌人。 */
  private autoTarget(): Actor | undefined {
    const w = this.world;
    const cur = w.actors.get(this.intent.kind === 'attack' ? this.intent.target : this.focus);
    if (cur && w.isEnemy(this.me, cur) && chebyshev(this.me, cur) <= 12) return cur;
    return w.nearestEnemy(this.me, 10);
  }

  // ---------------- 每帧 ----------------

  update(): void {
    const w = this.world;
    const p = this.me;
    if (w.pendingTeleport && !this.loadingMap) void this.teleport();
    if (p.dead || this.loadingMap) return;
    this.autoPotion();
    if (w.now < p.busyUntil) return;

    switch (this.intent.kind) {
      case 'steer':
        this.stepToward(this.intent.target, this.intent.run);
        return;
      case 'stick': {
        const it = this.intent;
        this.stepToward({ x: p.x + it.dx * 4, y: p.y + it.dy * 4 }, it.run);
        return;
      }
      case 'goto':
        this.followPath();
        return;
      case 'attack':
        this.doAttack();
        return;
      case 'talk': {
        const npc = w.actors.get(this.intent.npc);
        if (!npc) {
          this.intent = { kind: 'none' };
          return;
        }
        if (chebyshev(p, npc) <= 3) {
          this.intent = { kind: 'none' };
          this.onTalk(npc.id);
        } else this.approach(npc.pos);
        return;
      }
      case 'pickup': {
        const id = this.intent.item;
        const it = w.items.find((i) => i.id === id);
        if (!it) {
          this.intent = { kind: 'none' };
          return;
        }
        if (samePos(p, it)) {
          w.pickUp(p);
          this.intent = { kind: 'none' };
        } else this.approach(it, false);
        return;
      }
      case 'none':
        this.idle();
    }
  }

  private async teleport(): Promise<void> {
    const w = this.world;
    const t = w.pendingTeleport!;
    this.loadingMap = true;
    try {
      const map = t.map === w.map.id ? w.map : await loadMap(w.catalog.map(t.map));
      w.pendingTeleport = null;
      w.enterMap(map, t);
      if (this.isTown(t.map)) this.me.home = { map: t.map, x: this.me.x, y: this.me.y };
      this.intent = { kind: 'none' };
    } catch (e) {
      this.onMessage(`地图加载失败：${(e as Error).message}`, 'bad');
      w.pendingTeleport = null;
    } finally {
      this.loadingMap = false;
    }
  }

  private isTown(_map: string): boolean {
    return false; // 回城点固定为比奇城（出生点），以后加盟重再按地图判断
  }

  private stepToward(target: Pos, run: boolean): void {
    const p = this.me;
    if (samePos(p, target)) return;
    const dir = dirTo(p, target);
    const far = chebyshev(p, target) >= 2;
    if (run && far && this.world.move(p, dir, true)) return;
    if (this.world.move(p, dir, false)) return;
    // 正前方走不了：试左右偏一格
    for (const d of [1, 7, 2, 6]) {
      if (this.world.move(p, ((dir + d) % 8) as Dir, false)) return;
    }
    this.world.turn(p, dir);
  }

  private followPath(): void {
    const it = this.intent;
    if (it.kind !== 'goto') return;
    const p = this.me;
    const dir = it.path[0];
    if (samePos(p, it.target)) {
      this.intent = { kind: 'none' };
      return;
    }
    if (dir === undefined) {
      // 远路一次寻不完：走到半路再接着寻
      it.path = this.world.pathTo(p, it.target) ?? [];
      if (!it.path.length || ++this.repaths > 40) {
        this.intent = { kind: 'none' };
        this.repaths = 0;
      }
      return;
    }
    // 能跑两格就跑
    const next2 = it.path[1];
    if (it.run && next2 === dir && this.world.move(p, dir, true)) {
      it.path.splice(0, 2);
      return;
    }
    if (this.world.move(p, dir, false)) {
      it.path.shift();
      return;
    }
    // 被挡：重新寻路
    it.path = this.world.pathTo(p, it.target) ?? [];
    if (!it.path.length) this.intent = { kind: 'none' };
  }

  /** 走到目标旁边（或站上去）。 */
  private approach(target: Pos, adjacent = true): void {
    const path = this.world.pathTo(this.me, target, adjacent);
    const d = path?.[0];
    if (d === undefined || !this.world.move(this.me, d, false)) {
      this.world.turn(this.me, dirTo(this.me, target));
      this.me.busyUntil = this.world.now + 200;
    }
  }

  private doAttack(): void {
    const it = this.intent;
    if (it.kind !== 'attack') return;
    const w = this.world;
    const p = this.me;
    const t = w.actors.get(it.target);
    if (!t || t.dead || !w.isEnemy(p, t)) {
      this.intent = { kind: 'none' };
      return;
    }
    const d = chebyshev(p, t);
    const longOk = p.longHitOn && !!skillOf(p, 12) && d === 2 && inLine(p, t) && this.clearBetween(p, t);
    if (d === 1 || longOk) {
      this.maybeFire();
      w.playerAttack(p, dirTo(p, t));
      if (it.once) this.intent = { kind: 'none' };
      return;
    }
    this.approach(t.pos);
  }

  private clearBetween(a: Pos, b: Pos): boolean {
    const mid = step(a, dirTo(a, b));
    return !this.world.occupant(mid.x, mid.y);
  }

  private maybeFire(): void {
    const p = this.me;
    if (!this.settings.autoFireSword || p.fireReady || !skillOf(p, 26)) return;
    if ((p.cooldown.get(26) ?? 0) > this.world.now) return;
    const r = castSpell(this.world, p, 26, { pos: p.pos });
    void r;
  }

  private idle(): void {
    const w = this.world;
    const p = this.me;
    // 被打自动还手
    if (this.settings.autoFightBack && p.lastHitBy) {
      const a = w.actors.get(p.lastHitBy);
      p.lastHitBy = 0;
      if (a && !a.dead && w.isEnemy(p, a) && chebyshev(p, a) <= 3) {
        this.intent = { kind: 'attack', target: a.id, once: false };
        return;
      }
    }
    // 自动捡物品
    if (this.settings.autoPickup) {
      const it = this.nearestLoot(6);
      if (it) this.intent = { kind: 'pickup', item: it.id };
    }
  }

  private nearestLoot(radius: number): GroundItem | undefined {
    const w = this.world;
    const p = this.me;
    let best: GroundItem | undefined;
    let bd = Infinity;
    for (const it of w.items) {
      const d = chebyshev(p, it);
      if (d > radius || d >= bd) continue;
      if (!isGold(it.item)) {
        const def = w.catalog.item(it.item.name);
        if (this.settings.pickupValuableOnly) {
          const k = itemKind(def).kind;
          if (k !== 'equip' && k !== 'book' && k !== 'amulet') continue;
        }
        if (w.roomFor(p, it.item.name, it.item.count)) continue;
      }
      // 站着的格子被占就捡不了
      const occ = w.occupant(it.x, it.y);
      if (occ && occ !== p) continue;
      best = it;
      bd = d;
    }
    return best;
  }

  private autoPotion(): void {
    const w = this.world;
    const p = this.me;
    if (w.now - this.lastPotion < 1500) return;
    const s = this.settings;
    const want = (cur: number, max: number, pct: number): boolean => pct > 0 && cur < (max * pct) / 100;
    const pick = (field: 'hp' | 'mp'): number | undefined => {
      const all = [...p.belt, ...p.bag];
      let best: { uid: number; v: number } | undefined;
      for (const it of all) {
        if (!it) continue;
        const k = itemKind(w.catalog.item(it.name));
        if (k.kind !== 'potion') continue;
        const v = field === 'hp' ? k.hp : k.mp;
        // 优先用普通药（慢回），太阳水留着
        if (v > 0 && (!best || (k.instant ? v * 0.5 : v) > best.v)) best = { uid: it.uid, v: k.instant ? v * 0.5 : v };
      }
      return best?.uid;
    };
    let used = false;
    if (want(p.hp, p.stats.maxHp, s.autoHpPercent)) {
      const uid = pick('hp');
      if (uid !== undefined) {
        w.useItem(p, uid);
        used = true;
      }
    }
    if (want(p.mp, p.stats.maxMp, s.autoMpPercent)) {
      const uid = pick('mp');
      if (uid !== undefined) {
        w.useItem(p, uid);
        used = true;
      }
    }
    if (used) this.lastPotion = w.now;
  }

  /** 技能栏：主动技能按快捷键排序。 */
  activeSkills(): { id: number; key: number }[] {
    return this.me.skills.filter((s) => !isPassive(s.id)).map((s) => ({ id: s.id, key: s.key }));
  }
}

function inLine(a: Pos, b: Pos): boolean {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  return dx === 0 || dy === 0 || Math.abs(dx) === Math.abs(dy);
}
