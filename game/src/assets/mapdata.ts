import { assetUrl } from './sprites';

/** 地图格子数据：assets/map/<id>.bin（tools/build_assets.py write_map_bin）。行优先 i = y*w + x。 */

export const UNITX = 48;
export const UNITY = 32;
export const GROUND_BLOCK = 16;

export class MapData {
  constructor(
    readonly id: string,
    readonly width: number,
    readonly height: number,
    /** 1 = 不可走，2 = 不可飞（挡魔法） */
    readonly flags: Uint8Array,
    /** 前景图号（从 1 起，0 无） */
    readonly front: Uint16Array,
    /** Objects 图库编号：0 → objects，n → objects{n+1} */
    readonly area: Uint8Array,
    readonly ani: Uint8Array,
    readonly aniTick: Uint8Array,
    readonly doorIndex: Uint8Array,
    readonly doorOffset: Uint8Array,
    readonly light: Uint8Array,
  ) {}

  inside(x: number, y: number): boolean {
    return x >= 0 && y >= 0 && x < this.width && y < this.height;
  }

  /** 地形可走（不看人物占位）。 */
  walkable(x: number, y: number): boolean {
    return this.inside(x, y) && (this.flags[y * this.width + x]! & 1) === 0;
  }

  /** 魔法、弓箭能否穿过。 */
  flyable(x: number, y: number): boolean {
    return this.inside(x, y) && (this.flags[y * this.width + x]! & 2) === 0;
  }

  static parse(id: string, buf: ArrayBuffer): MapData {
    const dv = new DataView(buf);
    const magic = String.fromCharCode(dv.getUint8(0), dv.getUint8(1), dv.getUint8(2), dv.getUint8(3));
    if (magic !== 'M176') throw new Error(`地图 ${id} 格式不对`);
    const w = dv.getUint16(4, true);
    const h = dv.getUint16(6, true);
    const n = w * h;
    let off = 8;
    const u8 = (): Uint8Array => {
      const a = new Uint8Array(buf, off, n);
      off += n;
      return a;
    };
    const flags = u8();
    const front = new Uint16Array(n);
    for (let i = 0; i < n; i++) front[i] = dv.getUint16(off + i * 2, true);
    off += n * 2;
    return new MapData(id, w, h, flags, front, u8(), u8(), u8(), u8(), u8(), u8());
  }
}

export async function loadMap(id: string): Promise<MapData> {
  for (let i = 0; ; i++) {
    try {
      const r = await fetch(assetUrl(`assets/map/${id}.bin`));
      if (!r.ok) throw new Error(`地图 ${id}: ${r.status}`);
      return MapData.parse(id, await r.arrayBuffer());
    } catch (e) {
      if (i >= 4) throw e;
      await new Promise((res) => setTimeout(res, 1000 * (i + 1)));
    }
  }
}
