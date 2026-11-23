/**
 * 数量框（#1159）：卖、存、取一叠东西时问要几个，默认全部。原版不叠物品，没有这个窗，画成和传送窗同色调的面板。
 * −10 −1 +1 +10 调数，「1 个」「一半」「全部」一键设；点数字可以直接输入（电脑上方便）；最下面取消、确定。
 */
import type { Gfx, Rect } from './gfx';
import type { Button, Pt } from './layout';

export interface QuantityHost {
  readonly g: Gfx;
  readonly W: number;
  readonly H: number;
  /** 放大倍数（手机上原版底栏缩得很小，按触屏操作件的倍数放大） */
  readonly scale: number;
  dialogOrigin(w: number, h: number): Pt;
  area(r: Rect): void;
  button(b: Button): void;
  isPressed(r: Rect): boolean;
  /** 让玩家直接输入一个数（返回 null = 取消） */
  typeNumber(title: string, value: number): number | null;
}

export interface QuantityAsk {
  /** 标题，如「卖出 金创药(小量)」 */
  title: string;
  /** 这一叠一共几个 */
  max: number;
  /** 选 n 个时附带的说明（如「得到 120 金币」） */
  note?: (n: number) => string;
  ok: (n: number) => void;
}

const W = 252;
const PAD = 10;
const BTN_H = 28;
const GAP = 6;
const H = 62 + (BTN_H + GAP) * 3 + PAD - GAP;

export class QuantityPanel {
  private ask: QuantityAsk | null = null;
  private value = 1;

  constructor(private readonly h: QuantityHost) {}

  get open(): boolean {
    return this.ask !== null;
  }

  show(ask: QuantityAsk): void {
    this.ask = ask;
    this.value = ask.max;
  }

  close(): void {
    this.ask = null;
  }

  private set(n: number): void {
    if (this.ask) this.value = Math.max(1, Math.min(this.ask.max, Math.floor(n)));
  }

  draw(): void {
    const ask = this.ask;
    if (!ask) return;
    const { g } = this.h;
    const s = Math.min(this.h.scale, (this.h.W - 8) / W, (this.h.H - 8) / H);
    const o = this.h.dialogOrigin(W * s, H * s);
    const toUi = (r: Rect): Rect => ({ x: o.x + r.x * s, y: o.y + r.y * s, w: r.w * s, h: r.h * s });
    this.h.area(toUi({ x: 0, y: 0, w: W, h: H }));
    const ctx = g.ctx;
    ctx.save();
    ctx.translate(o.x, o.y);
    ctx.scale(s, s);
    g.frame(0, 0, W, H, 'rgba(16,11,6,.96)', '#8a6a3a');
    g.text(ask.title, PAD, 10, '#ffe08a', true);
    // 数量（点它直接输入）
    const field: Rect = { x: PAD, y: 32, w: 110, h: 22 };
    g.frame(field.x, field.y, field.w, field.h, '#000', '#c9a060');
    g.text(`${this.value} / ${ask.max}`, field.x + 6, field.y + 5, '#fff', true);
    this.h.button({
      ...toUi(field), img: -1,
      onClick: () => {
        const n = this.h.typeNumber(`${ask.title}：输入数量（1–${ask.max}）`, this.value);
        if (n !== null && Number.isFinite(n)) this.set(n);
      },
    });
    const note = ask.note?.(this.value);
    if (note) g.text(note, field.x + field.w + 8, field.y + 5, '#ffd96b');
    const row = (y: number, items: { label: string; run: () => void; color?: string }[]): void => {
      const bw = (W - PAD * 2 - GAP * (items.length - 1)) / items.length;
      items.forEach((b, i) => this.flat({ x: PAD + i * (bw + GAP), y, w: bw, h: BTN_H }, toUi, b.label, b.run, b.color));
    };
    row(62, [
      { label: '−10', run: () => this.set(this.value - 10) },
      { label: '−1', run: () => this.set(this.value - 1) },
      { label: '+1', run: () => this.set(this.value + 1) },
      { label: '+10', run: () => this.set(this.value + 10) },
    ]);
    row(62 + BTN_H + GAP, [
      { label: '1 个', run: () => this.set(1) },
      { label: '一半', run: () => this.set(Math.ceil(ask.max / 2)) },
      { label: '全部', run: () => this.set(ask.max) },
    ]);
    row(62 + (BTN_H + GAP) * 2, [
      { label: '取消', run: () => this.close() },
      {
        label: '确定', color: '#9cff8a',
        run: () => {
          const n = this.value;
          this.close();
          ask.ok(n);
        },
      },
    ]);
    ctx.restore();
  }

  /** r：面板内坐标（画）；点击区换算成界面坐标。 */
  private flat(r: Rect, toUi: (r: Rect) => Rect, label: string, onClick: () => void, color = '#ffe08a'): void {
    const { g } = this.h;
    const hit = toUi(r);
    g.frame(r.x, r.y, r.w, r.h, this.h.isPressed(hit) ? '#7a5a2a' : '#3a2a16', '#c9a060');
    g.text(label, r.x + (r.w - g.width(label)) / 2, r.y + (r.h - 14) / 2 + 1, color, true);
    this.h.button({ ...hit, img: -1, onClick });
  }
}
