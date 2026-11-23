/** 图集按需加载：assets/img/<lib>/<block>.json + <block>_<page>.png（tools/atlas.py 生成）。 */

export interface Sprite {
  readonly img: HTMLImageElement;
  readonly sx: number;
  readonly sy: number;
  readonly w: number;
  readonly h: number;
  /** 绘制偏移：图左上角 = 锚点 + (ox, oy) */
  readonly ox: number;
  readonly oy: number;
}

type FrameMeta = readonly [page: number, ax: number, ay: number, w: number, h: number, ox: number, oy: number] | 0;

interface BlockJson {
  pages: number;
  frames: FrameMeta[];
}

type Block =
  | { state: 'loading'; promise: Promise<void> }
  | { state: 'ready'; sprites: (Sprite | null)[] }
  | { state: 'missing' };

export function blockSize(lib: string): number {
  const base = lib.replace(/\d+$/, '');
  return base === 'hum' || base === 'weapon' || base === 'hair' ? 600 : 200;
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.decoding = 'async';
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error(`图片加载失败 ${src}`));
    img.src = src;
  });
}

async function fetchJsonRetry<T>(url: string, tries = 4): Promise<T> {
  let last: unknown;
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetch(url);
      if (r.status === 404) throw Object.assign(new Error('404'), { notFound: true });
      if (!r.ok) throw new Error(`${url} ${r.status}`);
      return (await r.json()) as T;
    } catch (e) {
      if ((e as { notFound?: boolean }).notFound) throw e;
      last = e;
      await new Promise((res) => setTimeout(res, 800 * (i + 1)));
    }
  }
  throw last;
}

export class SpriteStore {
  private readonly blocks = new Map<string, Block>();
  /** 有新块加载完成时调用（渲染器据此知道该重画）。 */
  onLoaded: () => void = () => {};

  constructor(private readonly base = 'assets/img') {}

  /** 取一帧；没加载就开始加载并返回 undefined；帧不存在返回 null。 */
  get(lib: string, index: number): Sprite | null | undefined {
    if (index < 0) return null;
    const size = blockSize(lib);
    const b = Math.floor(index / size);
    const block = this.block(lib, b);
    if (block.state === 'ready') return block.sprites[index - b * size] ?? null;
    if (block.state === 'missing') return null;
    return undefined;
  }

  /** 预加载一组帧所在的块。 */
  async preload(lib: string, indices: Iterable<number>): Promise<void> {
    const size = blockSize(lib);
    const blocks = new Set<number>();
    for (const i of indices) blocks.add(Math.floor(i / size));
    await Promise.all([...blocks].map((b) => this.whenReady(lib, b)));
  }

  private async whenReady(lib: string, b: number): Promise<void> {
    const block = this.block(lib, b);
    if (block.state === 'loading') await block.promise;
  }

  private block(lib: string, b: number): Block {
    const key = `${lib}/${b}`;
    const cur = this.blocks.get(key);
    if (cur) return cur;
    const promise = this.loadBlock(lib, b).then(
      (sprites) => {
        this.blocks.set(key, { state: 'ready', sprites });
        this.onLoaded();
      },
      (e) => {
        console.warn('图集块加载失败', key, e);
        this.blocks.set(key, { state: 'missing' });
      },
    );
    const loading: Block = { state: 'loading', promise };
    this.blocks.set(key, loading);
    return loading;
  }

  private async loadBlock(lib: string, b: number): Promise<(Sprite | null)[]> {
    const meta = await fetchJsonRetry<BlockJson>(`${this.base}/${lib}/${b}.json`);
    const pages: HTMLImageElement[] = [];
    for (let p = 0; p < meta.pages; p++) {
      pages.push(await loadImage(`${this.base}/${lib}/${b}_${p}.png`));
    }
    return meta.frames.map((f) => {
      if (f === 0) return null;
      const [page, sx, sy, w, h, ox, oy] = f;
      const img = pages[page];
      return img ? { img, sx, sy, w, h, ox, oy } : null;
    });
  }
}
