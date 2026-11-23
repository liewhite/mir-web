/**
 * 传送窗口（用户 #1139：任意地方直接传到城中心、打怪地图入口）。原版没有，画成和手机设置窗同色调的面板。
 * 两层：先选分组（城市、骷髅洞、沃玛、祖玛寺庙……），再选地图；点了就传，窗口关掉。
 * 目的地列表来自 game.json teleports（tools/build_data.py）。
 */
import type { TeleportDef } from '../../data/defs';
import type { Gfx, Rect } from './gfx';
import type { Button, Pt } from './layout';

/** 窗口要用到的界面能力（ClassicUi 提供）。 */
export interface TeleportHost {
  readonly g: Gfx;
  /** 界面宽高：窄屏（竖屏手机）改成两列 */
  readonly W: number;
  readonly H: number;
  /** 放大倍数：原版底栏在手机上缩得很小，窗口按触屏操作件的倍数放大，手指才点得到 */
  readonly scale: number;
  readonly teleports: readonly TeleportDef[];
  /** 当前所在地图（高亮用） */
  readonly mapId: string;
  dialogOrigin(w: number, h: number): Pt;
  area(r: Rect): void;
  button(b: Button): void;
  isPressed(r: Rect): boolean;
  /** 传过去；返回不能传的原因 */
  go(t: TeleportDef): string | null;
  message(text: string): void;
  close(): void;
}

const BTN_W = 124;
const BTN_H = 34;
const GAP = 6;
const PAD = 10;
const HEAD = 40;

export class TeleportPanel {
  /** 选中的分组；null = 在选分组 */
  private group: string | null = null;

  constructor(private readonly h: TeleportHost) {}

  /** 打开时回到分组页。 */
  reset(): void {
    this.group = null;
  }

  draw(): void {
    const { g } = this.h;
    const groups = [...new Set(this.h.teleports.map((t) => t.group))];
    const here = new Set(this.h.teleports.filter((t) => t.map === this.h.mapId).map((t) => t.group));
    const entries: { label: string; active: boolean; onClick: () => void }[] = this.group === null
      ? groups.map((name) => ({ label: name, active: here.has(name), onClick: () => (this.group = name) }))
      : this.h.teleports.filter((t) => t.group === this.group).map((t) => ({
        label: t.name, active: t.map === this.h.mapId, onClick: () => this.go(t),
      }));
    const width = (cols: number): number => PAD * 2 + cols * BTN_W + (cols - 1) * GAP;
    const want = this.h.scale;
    const cols = (this.h.W - 8) / want >= width(3) ? 3 : 2;
    const rows = Math.ceil(entries.length / cols);
    // 面板内坐标（未放大）；画的时候整体放大 s 倍
    const w = width(cols);
    const hh = HEAD + rows * (BTN_H + GAP) + PAD;
    const s = Math.min(want, (this.h.W - 8) / w, (this.h.H - 8) / hh);
    const o = this.h.dialogOrigin(w * s, hh * s);
    const toUi = (r: Rect): Rect => ({ x: o.x + r.x * s, y: o.y + r.y * s, w: r.w * s, h: r.h * s });
    this.h.area(toUi({ x: 0, y: 0, w, h: hh }));
    const ctx = g.ctx;
    ctx.save();
    ctx.translate(o.x, o.y);
    ctx.scale(s, s);
    g.frame(0, 0, w, hh, 'rgba(16,11,6,.96)', '#8a6a3a');
    g.text(this.group === null ? '传送：选地区' : `传送：${this.group}`, PAD + 4, 13, '#ffe08a', true);
    const buttons: { r: Rect; label: string; onClick: () => void; color?: string }[] = [];
    const close = { x: w - 50, y: 4, w: 46, h: 30 };
    buttons.push({ r: close, label: '关闭', onClick: () => this.h.close() });
    if (this.group !== null) buttons.push({ r: { x: close.x - 52, y: 4, w: 46, h: 30 }, label: '返回', onClick: () => (this.group = null) });
    entries.forEach((e, i) => {
      const r = { x: PAD + (i % cols) * (BTN_W + GAP), y: HEAD + Math.floor(i / cols) * (BTN_H + GAP), w: BTN_W, h: BTN_H };
      buttons.push({ r, label: e.label, onClick: e.onClick, color: e.active ? '#9cff8a' : '#fff' });
    });
    for (const b of buttons) this.flatButton(b.r, toUi(b.r), b.label, b.onClick, b.color);
    ctx.restore();
  }

  private go(t: TeleportDef): void {
    const why = this.h.go(t);
    if (why) {
      this.h.message(why);
      return;
    }
    this.h.message(`传送到 ${t.group === '城市' ? t.name : `${t.group} · ${t.name}`}`);
    this.h.close();
  }

  /** r：面板内坐标（画）；hit：界面坐标（点击区）。 */
  private flatButton(r: Rect, hit: Rect, label: string, onClick: () => void, color = '#ffe08a'): void {
    const { g } = this.h;
    g.frame(r.x, r.y, r.w, r.h, this.h.isPressed(hit) ? '#7a5a2a' : '#3a2a16', '#c9a060');
    let text = label;
    while (text.length > 2 && g.width(text) > r.w - 8) text = text.slice(0, -1);
    g.text(text, r.x + (r.w - g.width(text)) / 2, r.y + (r.h - 14) / 2 + 1, color, true);
    this.h.button({ ...hit, img: -1, onClick });
  }
}
