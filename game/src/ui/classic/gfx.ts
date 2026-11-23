/** 原版界面的绘图小工具：按图库帧号画图（不带偏移，界面图都按左上角摆），画 12 像素宋体字。 */
import type { Sprite, SpriteStore } from '../../assets/sprites';

export const FONT = '12px "SimSun","宋体","Songti SC","STSong","PingFang SC",serif';
export const MAIN = 'prguse'; // 原版 g_WMainImages

export class Gfx {
  constructor(readonly ctx: CanvasRenderingContext2D, readonly sprites: SpriteStore) {}

  sprite(lib: string, idx: number): Sprite | null | undefined {
    return this.sprites.get(lib, idx);
  }

  size(lib: string, idx: number): { w: number; h: number } | null {
    const s = this.sprites.get(lib, idx);
    return s ? { w: s.w, h: s.h } : null;
  }

  /** 按左上角画（界面图）。 */
  img(lib: string, idx: number, x: number, y: number): Sprite | null | undefined {
    const s = this.sprites.get(lib, idx);
    if (s) this.ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, Math.round(x), Math.round(y), s.w, s.h);
    return s;
  }

  /** 画图的一部分（血球、经验条按比例裁剪）。 */
  imgPart(lib: string, idx: number, x: number, y: number, rx: number, ry: number, rw: number, rh: number): void {
    const s = this.sprites.get(lib, idx);
    if (!s || rw <= 0 || rh <= 0) return;
    this.ctx.drawImage(s.img, s.sx + rx, s.sy + ry, rw, rh, Math.round(x), Math.round(y), rw, rh);
  }

  /** 居中画在一个矩形里（物品图标）。 */
  imgCenter(lib: string, idx: number, x: number, y: number, w: number, h: number): void {
    const s = this.sprites.get(lib, idx);
    if (s) this.ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, Math.round(x + (w - s.w) / 2), Math.round(y + (h - s.h) / 2), s.w, s.h);
  }

  text(s: string, x: number, y: number, color = '#fff', bold = false): number {
    const ctx = this.ctx;
    ctx.font = FONT;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';
    if (bold) {
      ctx.fillStyle = '#000';
      for (const [dx, dy] of [[-1, 0], [1, 0], [0, -1], [0, 1]] as const) ctx.fillText(s, x + dx, y + dy);
    }
    ctx.fillStyle = color;
    ctx.fillText(s, x, y);
    return ctx.measureText(s).width;
  }

  width(s: string): number {
    this.ctx.font = FONT;
    return this.ctx.measureText(s).width;
  }

  rect(x: number, y: number, w: number, h: number, color: string): void {
    this.ctx.fillStyle = color;
    this.ctx.fillRect(x, y, w, h);
  }
}

/** 金币显示：1,234,567 */
export function goldStr(n: number): string {
  return n.toLocaleString('en-US');
}

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export function inside(r: Rect, x: number, y: number): boolean {
  return x >= r.x && y >= r.y && x < r.x + r.w && y < r.y + r.h;
}
