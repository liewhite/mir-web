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
import { type SkillButton, TouchControls } from './controls';

const SLOT = 42;
const GAP = 4;
const CHAT_KEEP_MS = 12_000;
const CHAT_MAX = 5;
/** 左上状态块高度 */
const STATUS_H = 96;

export class TouchLayout implements Layout {
  readonly kind = 'touch' as const;
  readonly joystick = true;
  readonly itemMenu = true;
  readonly settingsRowH = 26;

  private readonly ctl: TouchControls;

  constructor(private readonly h: UiHost) {
    this.ctl = new TouchControls(h);
  }

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
  /** 横屏（宽大于高）：窗口左右并排；竖屏：窗口上下叠放 */
  private get wide(): boolean {
    return this.h.W > this.h.H;
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
    const r = this.ctl.stickR;
    return { x: this.L + r + 30, y: this.B - r - 24 };
  }

  /**
   * 快捷栏 6 格：优先放在最底下摇杆和攻击键之间（和技能键不打架时）；
   * 放不下（竖屏窄、技能多）就贴在摇杆上方，技能键伸过来时再往上让开。
   */
  beltRects(): Rect[] {
    const total = 6 * SLOT + 5 * GAP;
    const row = (x0: number, y: number): Rect[] => Array.from({ length: 6 }, (_, i) => ({ x: x0 + i * (SLOT + GAP), y, w: SLOT, h: SLOT }));
    const skills = this.skillButtons().map((b) => b.r);
    const hits = (rs: Rect[]): boolean => rs.some((r) => skills.some((k) => overlap(r, k)));
    const left = this.stickHome().x + this.ctl.stickR + 12;
    const right = this.attackCenter().x - this.ctl.attackR - 12;
    if (right - left >= total) {
      const bottom = row(Math.round(left + (right - left - total) / 2), this.B - SLOT - 6);
      if (!hits(bottom)) return bottom;
      // 靠左贴着摇杆再试一次
      const tight = row(left, this.B - SLOT - 6);
      if (!hits(tight)) return tight;
    }
    const x0 = this.L + 8;
    const tops = [this.stickHome().y - this.ctl.stickR, ...skills.filter((k) => k.x < x0 + total).map((k) => k.y)];
    return row(x0, Math.min(...tops) - 10 - SLOT);
  }

  // ---------------- HUD ----------------

  drawHud(now: number): void {
    this.drawStatus();
    this.drawChat();
    this.drawBelt();
    this.drawMenuButtons();
    this.ctl.draw({ stick: this.stickHome(), attack: this.attackCenter(), targetTop: this.wide ? this.T + 50 : this.T + 172 });
    void now;
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

  private drawBelt(): void {
    const { g, p, catalog } = this.h;
    this.beltRects().forEach((r, i) => {
      g.frame(r.x, r.y, r.w, r.h, 'rgba(0,0,0,.55)', '#8a6a3a');
      this.h.area(r);
      const it = p.belt[i];
      if (it && !this.h.beltInHand(i)) g.item(catalog.item(it.name).look, it.count, r.x, r.y, r.w, r.h);
      g.text(String(i + 1), r.x + 3, r.y + 2, '#ffe08a', true);
    });
  }

  /** 包裹、状态、技能、设置、小地图、传送。 */
  private drawMenuButtons(): void {
    const { g } = this.h;
    const items: { icon: number | string; onClick: () => void }[] = [
      { icon: 9, onClick: () => this.h.toggleBag() },
      { icon: 8, onClick: () => this.h.toggleState(0) },
      { icon: 10, onClick: () => this.h.toggleState(3) },
      { icon: 11, onClick: () => this.h.toggleSettings() },
      { icon: '地图', onClick: () => this.h.toggleMiniMap() },
      { icon: '传送', onClick: () => this.h.toggleTeleport() },
    ];
    const size = 40;
    const step = size + 4;
    // 横屏：顶边一行，在状态窗（右上 232 宽）左边；竖屏：右边一列，在小地图下面
    const at = (i: number): Rect =>
      this.wide
        ? { x: this.R - 236 - (items.length - i) * step, y: this.T + 4, w: size, h: size }
        : { x: this.R - size - 4, y: this.T + 126 + i * step, w: size, h: size };
    items.forEach((it, i) => {
      const r = at(i);
      const pressed = this.h.isPressed(r);
      g.frame(r.x, r.y, r.w, r.h, pressed ? 'rgba(90,70,30,.85)' : 'rgba(0,0,0,.55)', '#8a6a3a');
      if (typeof it.icon === 'number') g.imgScaled(MAIN, it.icon, r.x + size / 2, r.y + size / 2, 1.4);
      else g.text(it.icon, r.x + (size - g.width(it.icon)) / 2, r.y + 14, '#ffe08a', true);
      this.h.area(r);
      this.h.button({ ...r, img: -1, onClick: it.onClick });
    });
  }

  private attackCenter(): Pt {
    const ar = this.ctl.attackR;
    return { x: this.R - ar - 24, y: this.B - ar - 24 };
  }

  private skillButtons(): SkillButton[] {
    return this.ctl.skillButtons(this.attackCenter());
  }
}

function overlap(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}
