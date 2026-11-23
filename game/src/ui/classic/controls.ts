/**
 * 触屏操作件（用户 #1140）：左边摇杆、右边「攻击」键和 F1-F8 技能环、锁定怪物的信息框。
 * 两套布局共用：手机精简布局（touch.ts）放在屏幕底边；原版底栏布局（layout.ts）叠在底栏上方。
 * 布局只给锚点（摇杆中心、攻击键中心、信息框顶边）；尺寸按 h.ctlScale 放大，保证手指点得到。
 */
import type { Rect } from './gfx';
import type { Pt, UiHost } from './layout';
import { manaCost } from '../../world/combat';
import { RATES } from '../../world/rates';
import { STICK_R } from '../../game/joystick';

const ATTACK_R = 38;
const SKILL_R = 22;
/** 技能键离攻击键中心的距离：F1-F4 内圈，F5-F8 外圈 */
const RINGS = [92, 152];
const ANGLES = [180, 210, 240, 270];
const TARGET_W = 210;

export interface ControlAnchors {
  /** 摇杆没按着时的中心 */
  stick: Pt;
  /** 攻击键中心 */
  attack: Pt;
  /** 锁定怪物信息框的顶边 */
  targetTop: number;
}

export interface SkillButton {
  id: number;
  key: number;
  cx: number;
  cy: number;
  r: Rect;
}

export class TouchControls {
  constructor(private readonly h: UiHost) {}

  private get s(): number {
    return this.h.ctlScale;
  }
  get stickR(): number {
    return STICK_R * this.s;
  }
  get attackR(): number {
    return ATTACK_R * this.s;
  }

  draw(a: ControlAnchors): void {
    this.drawTarget(a.targetTop);
    this.drawStick(a.stick);
    this.drawAttack(a.attack);
    this.drawSkills(a.attack);
  }

  /** F1-F8 按键位摆；没设快捷键的主动技能顺次补进空位（只是显示，不改存档里的快捷键）。 */
  skillButtons(attack: Pt): SkillButton[] {
    const all = this.h.ctl.activeSkills();
    const byKey = new Map(all.filter((s) => s.key > 0 && s.key <= 8).map((s) => [s.key, s]));
    const loose = all.filter((s) => !(s.key > 0 && s.key <= 8));
    const out: SkillButton[] = [];
    const sr = SKILL_R * this.s;
    for (let k = 1; k <= 8; k++) {
      const s = byKey.get(k) ?? loose.shift();
      if (!s) continue;
      const ring = RINGS[k <= 4 ? 0 : 1]! * this.s;
      const ang = (ANGLES[(k - 1) % 4]! * Math.PI) / 180;
      const cx = attack.x + ring * Math.cos(ang);
      const cy = attack.y + ring * Math.sin(ang);
      out.push({ id: s.id, key: k, cx, cy, r: { x: cx - sr, y: cy - sr, w: sr * 2, h: sr * 2 } });
    }
    return out;
  }

  /** 锁定的怪：名字、等级、血条、攻防、经验（倍率后）。右上角 × 取消锁定。 */
  private drawTarget(top: number): void {
    const { g, ctl, catalog } = this.h;
    const t = ctl.focused();
    if (!t || t.kind !== 'monster') return;
    const x = Math.round((this.h.W - TARGET_W) / 2);
    const y = top;
    const hh = 70;
    this.h.area({ x, y, w: TARGET_W, h: hh });
    g.frame(x, y, TARGET_W, hh, 'rgba(10,7,3,.8)', t.master ? '#5a8a5a' : '#8a3a2a');
    const tag = t.master ? '（宝宝）' : t.def.undead ? '（不死系）' : '';
    g.text(`${catalog.displayName(t.def.name)}  ${t.def.level} 级${tag}`, x + 6, y + 5, t.master ? '#9cff8a' : '#ffe08a', true);
    const ratio = Math.max(0, Math.min(1, t.hp / t.maxHp));
    g.rect(x + 6, y + 22, TARGET_W - 12, 10, '#300');
    g.rect(x + 6, y + 22, Math.round((TARGET_W - 12) * ratio), 10, t.master ? '#2ecc71' : '#d33');
    const hp = `${t.hp}/${t.maxHp}`;
    g.text(hp, x + (TARGET_W - g.width(hp)) / 2, y + 21, '#fff', true);
    g.text(`攻击 ${t.dc[0]}-${t.dc[1]}  防御 ${t.ac[0]}-${t.ac[1]}  魔防 ${t.mac[0]}-${t.mac[1]}`, x + 6, y + 37, '#ddd');
    g.text(`经验 ${t.def.exp * RATES.exp}`, x + 6, y + 52, '#c0c0c0');
    const close = { x: x + TARGET_W - 30, y, w: 30, h: 22 };
    g.text('×', close.x + 11, close.y + 4, '#f88', true);
    this.h.button({ ...close, img: -1, onClick: () => ctl.unlock() });
  }

  private drawStick(home: Pt): void {
    const { g } = this.h;
    const js = this.h.joystick;
    js.radius = this.stickR;
    const base = js.active ? js.base : home;
    const knob = js.active ? js.knob : base;
    g.circle(base.x, base.y, this.stickR, js.active ? 'rgba(0,0,0,.4)' : 'rgba(0,0,0,.35)', 'rgba(216,162,74,.85)');
    g.circle(knob.x, knob.y, 22 * this.s, js.active ? 'rgba(216,162,74,.8)' : 'rgba(216,162,74,.55)', '#d8a24a');
    if (!js.active) {
      const t = '摇杆';
      g.text(t, base.x - g.width(t) / 2, base.y - 6, '#fff', true);
    }
  }

  private drawAttack(c: Pt): void {
    const { g } = this.h;
    const ar = this.attackR;
    const r = { x: c.x - ar, y: c.y - ar, w: ar * 2, h: ar * 2 };
    g.circle(c.x, c.y, ar, this.h.isPressed(r) ? 'rgba(140,40,20,.85)' : 'rgba(60,20,10,.7)', '#d8a24a');
    const t = '攻击';
    g.text(t, c.x - g.width(t) / 2, c.y - 6, '#ffe08a', true);
    this.h.area(r);
    this.h.button({ ...r, img: -1, onClick: () => this.h.ctl.attackNearest() });
  }

  private drawSkills(attack: Pt): void {
    const { g, p, catalog, world } = this.h;
    const sr = SKILL_R * this.s;
    for (const { id, key, cx, cy, r } of this.skillButtons(attack)) {
      const def = catalog.skillsById.get(id);
      const ls = p.skills.find((s) => s.id === id);
      if (!def || !ls) continue;
      g.circle(cx, cy, sr, this.h.isPressed(r) ? 'rgba(90,70,30,.9)' : 'rgba(0,0,0,.6)', '#8a6a3a');
      g.imgCenter('magicon', def.effect * 2, r.x, r.y, r.w, r.h);
      const cd = (p.cooldown.get(id) ?? 0) - world.now;
      if (cd > 0) {
        g.circle(cx, cy, sr, 'rgba(0,0,0,.6)');
        const t = (cd / 1000).toFixed(cd < 10_000 ? 1 : 0);
        g.text(t, cx - g.width(t) / 2, cy - 6, '#fff', true);
      } else if (p.mp < manaCost(def, ls.level)) g.circle(cx, cy, sr, 'rgba(0,0,80,.45)');
      g.text(`F${key}`, r.x - 2, r.y - 4, '#ff0', true);
      this.h.area(r);
      this.h.button({ ...r, img: -1, onClick: () => this.h.ctl.castSkillAuto(id) });
    }
  }
}
