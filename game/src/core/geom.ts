/** 地图坐标与八方向。方向编号同原版：0 上，顺时针到 7 左上。 */
export type Dir = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7;

export interface Pos {
  readonly x: number;
  readonly y: number;
}

const DX = [0, 1, 1, 1, 0, -1, -1, -1] as const;
const DY = [-1, -1, 0, 1, 1, 1, 0, -1] as const;

export function step(p: Pos, dir: Dir, n = 1): Pos {
  return { x: p.x + DX[dir] * n, y: p.y + DY[dir] * n };
}

export function dirTo(from: Pos, to: Pos): Dir {
  const dx = Math.sign(to.x - from.x);
  const dy = Math.sign(to.y - from.y);
  for (let d = 0; d < 8; d++) if (DX[d] === dx && DY[d] === dy) return d as Dir;
  return 4;
}

/** 按屏幕角度取方向（用于鼠标指向：格子是 48x32，按像素算角度更准）。 */
export function dirFromVector(px: number, py: number): Dir {
  const a = Math.atan2(px, -py); // 0 = 上，顺时针为正
  const d = Math.round(a / (Math.PI / 4));
  return (((d % 8) + 8) % 8) as Dir;
}

/** 切比雪夫距离：八方向走几步。 */
export function chebyshev(a: Pos, b: Pos): number {
  return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
}

export function samePos(a: Pos, b: Pos): boolean {
  return a.x === b.x && a.y === b.y;
}

export const ALL_DIRS: readonly Dir[] = [0, 1, 2, 3, 4, 5, 6, 7];

export function opposite(d: Dir): Dir {
  return ((d + 4) % 8) as Dir;
}
