/** 把图集里的一帧画进小 canvas，用在 DOM 界面（物品图标、技能图标）。 */
import type { SpriteStore } from '../assets/sprites';

export class IconFactory {
  private pending = new Set<{ c: HTMLCanvasElement; lib: string; idx: number }>();

  constructor(private readonly sprites: SpriteStore) {}

  icon(lib: string, idx: number, size = 34): HTMLCanvasElement {
    const c = document.createElement('canvas');
    c.width = size;
    c.height = size;
    c.className = 'icon';
    if (!this.paint(c, lib, idx)) this.pending.add({ c, lib, idx });
    return c;
  }

  /** 图集块加载完后补画。 */
  refresh(): void {
    for (const p of [...this.pending]) {
      if (!p.c.isConnected || this.paint(p.c, p.lib, p.idx)) this.pending.delete(p);
    }
  }

  private paint(c: HTMLCanvasElement, lib: string, idx: number): boolean {
    const s = this.sprites.get(lib, idx);
    if (s === undefined) return false;
    if (s === null) return true;
    const ctx = c.getContext('2d')!;
    ctx.clearRect(0, 0, c.width, c.height);
    const k = Math.min(1, c.width / s.w, c.height / s.h);
    const w = s.w * k;
    const h = s.h * k;
    ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, (c.width - w) / 2, (c.height - h) / 2, w, h);
    return true;
  }
}
