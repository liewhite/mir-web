/**
 * 界面布局：常驻的 HUD（底栏或触屏按钮）画在哪、各窗口摆在哪。
 * 窗口内容（包裹、状态、商店……）由 ClassicUi 画，布局只给位置；两套实现：
 * - BoardLayout：原版 800x600 底栏（电脑、平板），照 FState.pas DBottom。
 * - TouchLayout（touch.ts）：手机精简布局。
 */
import type { Catalog } from '../../data/defs';
import type { Player } from '../../world/entities';
import type { World } from '../../world/world';
import type { Controller } from '../../game/controller';
import type { LayoutKind } from '../viewport';
import type { Joystick } from '../../game/joystick';
import { type Gfx, MAIN, type Rect } from './gfx';

export interface Pt {
  x: number;
  y: number;
}

export interface Insets {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

export interface Button extends Rect {
  /** 原版按钮图号（按下时画）；-1 表示自己画的按钮 */
  img: number;
  /** 平时也画（不是底图的一部分） */
  always?: boolean;
  onClick: () => void;
}

export type ChatColor = 'system' | 'good' | 'bad' | 'item';
export const CHAT_COLOR: Record<ChatColor, [string, string]> = {
  system: ['#fff', '#000'], good: ['#9cff8a', '#000'], bad: ['#fff', '#c00'], item: ['#ff0', '#000'],
};
export interface ChatLine {
  text: string;
  color: ChatColor;
  /** 收到的时刻（performance.now） */
  at: number;
}

/** 布局能用到的界面能力（ClassicUi 实现）。 */
export interface UiHost {
  readonly g: Gfx;
  readonly world: World;
  readonly ctl: Controller;
  readonly catalog: Catalog;
  readonly p: Player;
  readonly W: number;
  readonly H: number;
  readonly safe: Insets;
  readonly chat: readonly ChatLine[];
  readonly stateOpen: boolean;
  readonly joystick: Joystick;
  /** 登记按钮（原版图按钮按下时会画出来） */
  button(b: Button): void;
  /** 这个按钮正被按着（自己画的按钮用来画按下效果） */
  isPressed(r: Rect): boolean;
  /** 登记界面占用区域（点在里面不传给地图） */
  area(r: Rect): void;
  /** 快捷栏第 i 格的物品正拿在手上（不画） */
  beltInHand(i: number): boolean;
  toggleBag(): void;
  toggleState(page: number): void;
  toggleSettings(): void;
  toggleMiniMap(): void;
  logout(): void;
  message(text: string): void;
}

export interface Layout {
  readonly kind: LayoutKind;
  /** 物品格点一下：false = 原版拿起/放下（双击使用）；true = 选中并弹出操作菜单（手机） */
  readonly itemMenu: boolean;
  /** 设置窗每行高度（手机上加大好点） */
  readonly settingsRowH: number;
  drawHud(now: number): void;
  /** 快捷栏 6 格 */
  beltRects(): Rect[];
  bagOrigin(merchantOpen: boolean): Pt;
  stateOrigin(bagOpen: boolean): Pt;
  merchantOrigin(): Pt;
  /** 小地图右上角 */
  miniMapRight(): Pt;
  /** 居中的对话框（设置、技能快捷键） */
  dialogOrigin(w: number, h: number): Pt;
}

export const BOARD_W = 800;
export const BOARD_H = 251;
const CHAT_LINES = 9;

/** 原版底栏布局。 */
export class BoardLayout implements Layout {
  readonly kind = 'board' as const;
  readonly itemMenu = false;
  readonly settingsRowH = 19;

  constructor(private readonly h: UiHost, private readonly touchSkills: boolean) {}

  private boardX(): number {
    return Math.round((this.h.W - BOARD_W) / 2);
  }

  beltRects(): Rect[] {
    const bx = this.boardX();
    const by = this.h.H - BOARD_H;
    return Array.from({ length: 6 }, (_, i) => ({ x: bx + 285 + 43 * i, y: by + 59, w: 32, h: 29 }));
  }

  bagOrigin(merchantOpen: boolean): Pt {
    return { x: (merchantOpen ? 475 : 0) + this.h.safe.left, y: 0 };
  }

  stateOrigin(): Pt {
    return { x: this.h.W - 232 - this.h.safe.right, y: 0 };
  }

  merchantOrigin(): Pt {
    return { x: this.h.safe.left, y: 0 };
  }

  miniMapRight(): Pt {
    return { x: this.h.W - this.h.safe.right, y: this.h.safe.top };
  }

  dialogOrigin(w: number, hh: number): Pt {
    return { x: Math.round((this.h.W - w) / 2), y: Math.max(0, Math.round((this.h.H - BOARD_H - hh) / 2)) };
  }

  drawHud(now: number): void {
    this.drawBottom();
    if (this.touchSkills) this.drawTouchSkills();
    void now;
  }

  private drawBottom(): void {
    const { g, p, catalog, world } = this.h;
    const H = this.h.H;
    const bx = this.boardX();
    const by = H - BOARD_H;
    this.h.area({ x: bx, y: by + 40, w: BOARD_W, h: BOARD_H - 40 });
    this.h.area({ x: bx, y: by, w: 190, h: 40 });
    this.h.area({ x: bx + 610, y: by, w: 190, h: 40 });
    // 下半部分不透明（原版 Draw(..., FALSE)），上半部分透明叠在地图上
    g.rect(bx + 208, by + 120, 387, 124, '#000');
    g.img(MAIN, 1, bx, by);
    g.img(MAIN, 12, bx + 748, by + 79); // 白天
    const s = p.stats;
    if (p.job === 0 && p.level < 28) {
      const full = g.size(MAIN, 5);
      if (full) {
        g.imgPart(MAIN, 5, bx + 38, by + 90, 0, 0, full.w - 2, full.h);
        const top = Math.round((full.h / s.maxHp) * (s.maxHp - p.hp));
        g.imgPart(MAIN, 6, bx + 38, by + 90 + top, 0, top, full.w - 2, full.h - top);
      }
    } else drawOrb(g, p, bx + 40, by + 91);
    // 血蓝数值（原版没有，为了看得清楚）
    const hpText = `${p.hp}/${s.maxHp}`;
    const mpText = `${p.mp}/${s.maxMp}`;
    g.text(hpText, bx + 86 - g.width(hpText) / 2, by + 186, '#fff', true);
    g.text(mpText, bx + 86 - g.width(mpText) / 2, by + 200, '#9fc6ff', true);
    g.text(String(p.level), bx + 660, H - 104, '#fff', true);
    const bar = g.size(MAIN, 7);
    if (bar) {
      const need = catalog.expToNext(p.level);
      const er = need === Infinity ? 1 : p.exp / need;
      g.imgPart(MAIN, 7, bx + 666, H - 73, 0, 0, Math.round(bar.w * Math.min(1, er)), bar.h);
      const wr = world.bagWeight(p) / Math.max(1, s.maxBagWeight);
      g.imgPart(MAIN, 7, bx + 666, H - 40, 0, 0, Math.round(bar.w * Math.min(1, wr)), bar.h);
    }
    // 聊天/系统消息
    this.h.chat.slice(-CHAT_LINES).forEach((l, i) => {
      const [fg, bg] = CHAT_COLOR[l.color];
      g.rect(bx + 208, H - 130 + i * 12, g.width(l.text), 12, bg);
      g.text(l.text, bx + 208, H - 130 + i * 12, fg);
    });
    // 功能按钮：状态、包裹、技能、设置
    this.h.button({ img: 8, x: bx + 643, y: by + 61, w: 24, h: 24, onClick: () => this.h.toggleState(0) });
    this.h.button({ img: 9, x: bx + 682, y: by + 41, w: 24, h: 22, onClick: () => this.h.toggleBag() });
    this.h.button({ img: 10, x: bx + 722, y: by + 21, w: 24, h: 23, onClick: () => this.h.toggleState(3) });
    this.h.button({ img: 11, x: bx + 764, y: by + 11, w: 24, h: 23, onClick: () => this.h.toggleSettings() });
    // 快捷栏
    this.beltRects().forEach((r, i) => {
      const it = p.belt[i];
      if (it && !this.h.beltInHand(i)) g.imgCenter('items', catalog.item(it.name).look, r.x, r.y, r.w, r.h);
      g.text(String(i + 1), r.x + 13, r.y + 19, '#fff', true);
    });
    // 底栏小按钮：小地图、交易、行会、组队、能力、退出（后几个单机用不上，只画样子）
    this.h.button({ img: 130, x: bx + 219, y: by + 104, w: 28, h: 13, always: true, onClick: () => this.h.toggleMiniMap() });
    for (const [img, dx] of [[132, 249], [134, 279], [128, 309]] as const) {
      this.h.button({ img, x: bx + dx, y: by + 104, w: 28, h: 13, always: true, onClick: () => this.h.message('单机版没有这个功能') });
    }
    this.h.button({ img: 136, x: bx + 530, y: by + 104, w: 28, h: 13, always: true, onClick: () => this.h.logout() });
    this.h.button({ img: 138, x: bx + 560, y: by + 104, w: 28, h: 13, always: true, onClick: () => this.h.logout() });
  }

  /** 平板（触屏但屏幕大，用原版底栏）：右侧一列技能按钮，原版没有，触屏上没有 F1-F8。 */
  private drawTouchSkills(): void {
    const { g, p, catalog, world } = this.h;
    const keyed = p.skills.filter((s) => s.key > 0).sort((a, b) => a.key - b.key);
    // 状态窗开着时挤到它左边，免得被盖住
    const x = (this.h.stateOpen ? this.stateOrigin().x : this.h.W - this.h.safe.right) - 44;
    let y = this.h.H - BOARD_H - 44;
    for (const ls of keyed) {
      const def = catalog.skillsById.get(ls.id)!;
      const r = { x, y, w: 36, h: 36 };
      g.rect(r.x - 2, r.y - 2, r.w + 4, r.h + 4, 'rgba(0,0,0,.55)');
      g.img('magicon', def.effect * 2, r.x + 2, r.y + 1);
      g.text(`F${ls.key}`, r.x, r.y + 24, '#ff0', true);
      const cd = (p.cooldown.get(ls.id) ?? 0) - world.now;
      if (cd > 0) g.rect(r.x, r.y, r.w, Math.round(r.h * Math.min(1, cd / Math.max(1, def.delay))), 'rgba(0,0,0,.6)');
      this.h.area(r);
      const key = ls.key;
      this.h.button({ ...r, img: -1, onClick: () => this.h.ctl.castByKeyAuto(key) });
      y -= 42;
    }
  }
}

/** 原版血蓝球（左半血、右半蓝，按比例从上往下空）。 */
export function drawOrb(g: Gfx, p: Player, x: number, y: number): void {
  const d = g.size(MAIN, 4);
  if (!d) return;
  const s = p.stats;
  const half = Math.floor(d.w / 2);
  const top = Math.round((d.h / s.maxHp) * (s.maxHp - p.hp));
  g.imgPart(MAIN, 4, x, y + top, 0, top, half - 1, d.h - top);
  const mtop = Math.round((d.h / Math.max(1, s.maxMp)) * (s.maxMp - p.mp));
  g.imgPart(MAIN, 4, x + half + 1, y + mtop, half + 1, mtop, d.w - half - 2, d.h - mtop);
}
