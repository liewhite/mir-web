/**
 * 手机精简布局（用户 #1140 定）：原版底栏太高、按钮太小，手机上换成
 * - 左上：原版血蓝球 + 血/蓝/等级/经验文字，下面是最近几条消息（一会儿就淡掉）；
 * - 左下：虚拟摇杆（左半屏任意位置按下都行，松手回到这里）；
 * - 上方中间：锁定的怪物信息（名字、等级、血、攻防、经验）；
 * - 下方：6 格快捷栏（竖屏时在摇杆上方）；
 * - 右下：大「攻击」键（打锁定的怪，没有就打最近的），F1-F4 内圈、F5-F8 外圈技能键；
 * - 上方：包裹、状态、技能、设置、小地图按钮。
 * 原版窗口（包裹、状态、商店、设置）照样用原版贴图，由 ClassicUi 按这里给的位置画。
 */
import { MAIN, type Rect } from './gfx';
import { CHAT_COLOR, type Layout, type Pt, type UiHost, drawOrb } from './layout';
import { manaCost } from '../../world/combat';
import { RATES } from '../../world/rates';
import { STICK_R } from '../../game/joystick';

const SLOT = 42;
const GAP = 4;
const ATTACK_R = 38;
const SKILL_R = 22;
/** 技能键离攻击键中心的距离：F1-F4 内圈，F5-F8 外圈 */
const RINGS = [92, 152];
const ANGLES = [180, 210, 240, 270];
const CHAT_KEEP_MS = 12_000;
const CHAT_MAX = 5;
/** 横屏和竖屏的分界（界面像素宽） */
const WIDE = 700;
/** 左上状态块高度 */
const STATUS_H = 96;
const TARGET_W = 210;

export class TouchLayout implements Layout {
  readonly kind = 'touch' as const;
  readonly itemMenu = true;
  readonly settingsRowH = 26;

  constructor(private readonly h: UiHost) {}

  private get L(): number {
    return this.h.safe.left;
  }
  private get R(): number {
    return this.h.W - this.h.safe.right;
  }
  private get T(): number {
    return this.h.safe.top;
  }
  private get B(): number {
    return this.h.H - this.h.safe.bottom;
  }
  private get wide(): boolean {
    return this.h.W >= WIDE;
  }

  // ---------------- 窗口位置 ----------------

  bagOrigin(merchantOpen: boolean): Pt {
    if (this.wide) return { x: merchantOpen ? this.R - 336 : this.L, y: this.T };
    return { x: Math.max(0, Math.round((this.h.W - 336) / 2)), y: this.T + (merchantOpen ? 381 : 0) };
  }

  stateOrigin(bagOpen: boolean): Pt {
    if (this.wide) return { x: this.R - 232, y: this.T };
    return { x: Math.round((this.h.W - 232) / 2), y: this.T + (bagOpen ? 270 : 0) };
  }

  merchantOrigin(): Pt {
    return { x: this.wide ? this.L : Math.max(0, Math.round((this.h.W - 416) / 2)), y: this.T };
  }

  miniMapRight(): Pt {
    return { x: this.R, y: this.T };
  }

  dialogOrigin(w: number, hh: number): Pt {
    return { x: Math.max(0, Math.round((this.h.W - w) / 2)), y: Math.max(this.T, Math.round((this.h.H - hh) / 2)) };
  }

  /** 摇杆没按着时的位置（左下） */
  private stickHome(): Pt {
    return { x: this.L + STICK_R + 30, y: this.B - STICK_R - 24 };
  }

  beltRects(): Rect[] {
    const total = 6 * SLOT + 5 * GAP;
    if (!this.wide) {
      // 竖屏：在摇杆和技能外圈上方一行
      const y = this.attackCenter().y - RINGS[1]! - SKILL_R - 12 - SLOT;
      return Array.from({ length: 6 }, (_, i) => ({ x: this.L + 8 + i * (SLOT + GAP), y, w: SLOT, h: SLOT }));
    }
    // 横屏：摇杆和技能键之间的底边
    const left = this.stickHome().x + STICK_R + 16;
    const right = this.attackCenter().x - RINGS[1]! - SKILL_R - 6;
    const x0 = Math.round(left + Math.max(0, (right - left - total) / 2));
    const y = this.B - SLOT - 6;
    return Array.from({ length: 6 }, (_, i) => ({ x: x0 + i * (SLOT + GAP), y, w: SLOT, h: SLOT }));
  }

  // ---------------- HUD ----------------

  drawHud(now: number): void {
    this.drawStatus();
    this.drawChat();
    this.drawTarget();
    this.drawStick();
    this.drawBelt();
    this.drawMenuButtons();
    this.drawAttack();
    this.drawSkills(now);
  }

  private drawStatus(): void {
    const { g, p, catalog } = this.h;
    const x = this.L + 4;
    const y = this.T + 4;
    this.h.area({ x: this.L, y: this.T, w: 200, h: STATUS_H });
    g.circle(x + 47, y + 46, 49, 'rgba(0,0,0,.55)');
    drawOrb(g, p, x + 1, y + 1);
    const s = p.stats;
    const need = catalog.expToNext(p.level);
    const exp = need === Infinity ? '满级' : `${((100 * p.exp) / need).toFixed(1)}%`;
    const tx = x + 98;
    g.text(`血 ${p.hp}/${s.maxHp}`, tx, y + 24, '#ff8a8a', true);
    g.text(`魔 ${p.mp}/${s.maxMp}`, tx, y + 40, '#9fc6ff', true);
    g.text(`${p.level} 级  ${exp}`, tx, y + 56, '#fff', true);
  }

  private drawChat(): void {
    const { g } = this.h;
    const t = performance.now();
    const lines = this.h.chat.slice(-CHAT_MAX).filter((l) => t - l.at < CHAT_KEEP_MS);
    const maxW = this.wide ? Math.min(this.h.W * 0.42, 360) : this.R - this.L - 236;
    lines.forEach((l, i) => {
      const [fg, bg] = CHAT_COLOR[l.color];
      let text = l.text;
      while (text.length > 4 && g.width(text) > maxW) text = text.slice(0, -2);
      if (text !== l.text) text += '…';
      const y = this.T + STATUS_H + 4 + i * 14;
      g.rect(this.L + 4, y, g.width(text) + 4, 14, bg === '#000' ? 'rgba(0,0,0,.5)' : bg);
      g.text(text, this.L + 6, y + 1, fg);
    });
  }

  /** 锁定的怪：名字、等级、血条、攻防、经验（倍率后）。右上角 × 取消锁定。 */
  private drawTarget(): void {
    const { g, ctl, catalog } = this.h;
    const t = ctl.focused();
    if (!t || t.kind !== 'monster') return;
    const x = Math.round((this.h.W - TARGET_W) / 2);
    const y = this.wide ? this.T + 50 : this.T + 172;
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

  private drawStick(): void {
    const { g } = this.h;
    const js = this.h.joystick;
    const base = js.active ? js.base : this.stickHome();
    const knob = js.active ? js.knob : base;
    g.circle(base.x, base.y, STICK_R, js.active ? 'rgba(0,0,0,.35)' : 'rgba(0,0,0,.2)', 'rgba(216,162,74,.6)');
    g.circle(knob.x, knob.y, 22, js.active ? 'rgba(216,162,74,.75)' : 'rgba(216,162,74,.35)', '#d8a24a');
  }

  private drawBelt(): void {
    const { g, p, catalog } = this.h;
    this.beltRects().forEach((r, i) => {
      g.frame(r.x, r.y, r.w, r.h, 'rgba(0,0,0,.55)', '#8a6a3a');
      this.h.area(r);
      const it = p.belt[i];
      if (it && !this.h.beltInHand(i)) g.imgCenter('items', catalog.item(it.name).look, r.x, r.y, r.w, r.h);
      g.text(String(i + 1), r.x + 3, r.y + 2, '#ffe08a', true);
    });
  }

  /** 包裹、状态、技能、设置、小地图。 */
  private drawMenuButtons(): void {
    const { g } = this.h;
    const items: { icon: number | string; onClick: () => void }[] = [
      { icon: 9, onClick: () => this.h.toggleBag() },
      { icon: 8, onClick: () => this.h.toggleState(0) },
      { icon: 10, onClick: () => this.h.toggleState(3) },
      { icon: 11, onClick: () => this.h.toggleSettings() },
      { icon: '地图', onClick: () => this.h.toggleMiniMap() },
    ];
    const size = 40;
    const step = size + 4;
    // 横屏：放在状态窗（右上 232 宽）左边；竖屏：放在小地图下面
    const right = this.wide ? this.R - 236 : this.R;
    const y = this.wide ? this.T + 4 : this.T + 126;
    items.forEach((it, i) => {
      const r = { x: right - (items.length - i) * step, y, w: size, h: size };
      const pressed = this.h.isPressed(r);
      g.frame(r.x, r.y, r.w, r.h, pressed ? 'rgba(90,70,30,.85)' : 'rgba(0,0,0,.55)', '#8a6a3a');
      if (typeof it.icon === 'number') g.imgScaled(MAIN, it.icon, r.x + size / 2, r.y + size / 2, 1.4);
      else g.text(it.icon, r.x + (size - g.width(it.icon)) / 2, r.y + 14, '#ffe08a', true);
      this.h.area(r);
      this.h.button({ ...r, img: -1, onClick: it.onClick });
    });
  }

  private attackCenter(): Pt {
    return { x: this.R - ATTACK_R - 24, y: this.B - ATTACK_R - 24 };
  }

  private drawAttack(): void {
    const { g } = this.h;
    const c = this.attackCenter();
    const r = { x: c.x - ATTACK_R, y: c.y - ATTACK_R, w: ATTACK_R * 2, h: ATTACK_R * 2 };
    g.circle(c.x, c.y, ATTACK_R, this.h.isPressed(r) ? 'rgba(140,40,20,.85)' : 'rgba(60,20,10,.7)', '#d8a24a');
    const t = '攻击';
    g.text(t, c.x - g.width(t) / 2, c.y - 6, '#ffe08a', true);
    this.h.area(r);
    this.h.button({ ...r, img: -1, onClick: () => this.h.ctl.attackNearest() });
  }

  /** F1-F8 按键位摆；没设快捷键的主动技能顺次补进空位（只是显示，不改存档里的快捷键）。 */
  private slots(): { id: number; key: number }[] {
    const all = this.h.ctl.activeSkills();
    const byKey = new Map(all.filter((s) => s.key > 0 && s.key <= 8).map((s) => [s.key, s]));
    const loose = all.filter((s) => !(s.key > 0 && s.key <= 8));
    const out: { id: number; key: number }[] = [];
    for (let k = 1; k <= 8; k++) {
      const s = byKey.get(k) ?? loose.shift();
      if (s) out.push({ id: s.id, key: k });
    }
    return out;
  }

  private drawSkills(now: number): void {
    void now;
    const { g, p, catalog, world } = this.h;
    const c = this.attackCenter();
    for (const slot of this.slots()) {
      const def = catalog.skillsById.get(slot.id);
      const ls = p.skills.find((s) => s.id === slot.id);
      if (!def || !ls) continue;
      const ring = RINGS[slot.key <= 4 ? 0 : 1]!;
      const a = (ANGLES[(slot.key - 1) % 4]! * Math.PI) / 180;
      const cx = c.x + ring * Math.cos(a);
      const cy = c.y + ring * Math.sin(a);
      const r = { x: cx - SKILL_R, y: cy - SKILL_R, w: SKILL_R * 2, h: SKILL_R * 2 };
      g.circle(cx, cy, SKILL_R, this.h.isPressed(r) ? 'rgba(90,70,30,.9)' : 'rgba(0,0,0,.6)', '#8a6a3a');
      g.imgCenter('magicon', def.effect * 2, r.x, r.y, r.w, r.h);
      const cd = (p.cooldown.get(slot.id) ?? 0) - world.now;
      if (cd > 0) {
        g.circle(cx, cy, SKILL_R, 'rgba(0,0,0,.6)');
        const t = (cd / 1000).toFixed(cd < 10_000 ? 1 : 0);
        g.text(t, cx - g.width(t) / 2, cy - 6, '#fff', true);
      } else if (p.mp < manaCost(def, ls.level)) g.circle(cx, cy, SKILL_R, 'rgba(0,0,80,.45)');
      g.text(`F${slot.key}`, r.x - 2, r.y - 4, '#ff0', true);
      this.h.area(r);
      const id = slot.id;
      this.h.button({ ...r, img: -1, onClick: () => this.h.ctl.castSkillAuto(id) });
    }
  }
}
