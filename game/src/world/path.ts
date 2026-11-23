/** 八方向 A* 寻路（点地面/小地图走路、怪物追人绕障碍都用它）。 */
import { ALL_DIRS, type Dir, type Pos, dirTo, step } from '../core/geom';

export interface Passable {
  /** 能否站上去（终点允许被占时由调用方处理） */
  canEnter(x: number, y: number): boolean;
}

interface Node {
  x: number;
  y: number;
  g: number;
  f: number;
  parent: Node | null;
}

/** 返回从 from 到 to 的方向序列；找不到返回 null。maxNodes 限制搜索量。 */
export function findPath(map: Passable, from: Pos, to: Pos, maxNodes = 4000, stopAdjacent = false): Dir[] | null {
  const key = (x: number, y: number): number => (y << 16) | (x & 0xffff);
  const h = (x: number, y: number): number => Math.max(Math.abs(x - to.x), Math.abs(y - to.y));
  const open: Node[] = [{ x: from.x, y: from.y, g: 0, f: h(from.x, from.y), parent: null }];
  const best = new Map<number, number>([[key(from.x, from.y), 0]]);
  let visited = 0;
  let closest: Node = open[0]!;
  while (open.length && visited < maxNodes) {
    // 小规模用线性取最小即可
    let bi = 0;
    for (let i = 1; i < open.length; i++) if (open[i]!.f < open[bi]!.f) bi = i;
    const cur = open.splice(bi, 1)[0]!;
    visited++;
    const d = h(cur.x, cur.y);
    if (d === 0 || (stopAdjacent && d === 1)) return unwind(cur);
    if (d < h(closest.x, closest.y)) closest = cur;
    for (const dir of ALL_DIRS) {
      const n = step(cur, dir);
      const isGoal = n.x === to.x && n.y === to.y;
      if (!isGoal && !map.canEnter(n.x, n.y)) continue;
      if (isGoal && !stopAdjacent && !map.canEnter(n.x, n.y)) continue;
      const g = cur.g + 1;
      const k = key(n.x, n.y);
      if ((best.get(k) ?? Infinity) <= g) continue;
      best.set(k, g);
      open.push({ x: n.x, y: n.y, g, f: g + h(n.x, n.y), parent: cur });
    }
  }
  // 到不了：走到最接近的位置
  return closest.parent ? unwind(closest) : null;
}

function unwind(n: Node): Dir[] {
  const dirs: Dir[] = [];
  let cur: Node | null = n;
  while (cur && cur.parent) {
    dirs.push(dirTo(cur.parent, cur));
    cur = cur.parent;
  }
  return dirs.reverse();
}
