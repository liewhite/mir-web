/**
 * Canvas 2D 渲染：地面（预渲染大块）→ 地上 48x32 物件 → 按行画高物件、地上物品、角色 → 特效 → 名字/血条/飘字。
 * 坐标和画法照原版 PlayScn.pas：格子 48x32，角色锚点是所在格的左上角，图片按自带偏移画。
 */
import type { Pos } from '../core/geom';
import type { Catalog } from '../data/defs';
import { GROUND_BLOCK, type MapData, UNITX, UNITY } from '../assets/mapdata';
import { type Sprite, type SpriteStore, assetUrl } from '../assets/sprites';
import { type Actor, type GroundItem, type Monster, type Player, isGold } from '../world/entities';
import { itemKind } from '../world/items';
import type { VisualEffect, World } from '../world/world';
import { HUMAN_FRAMES, humanFrame, monsterFrame, weaponInFront } from './frames';

const LONG_ROWS = 22; // 高物件可能从屏幕下方很远的格子往上画
const WIDE_COLS = 10;

interface Floater {
  actor: number;
  text: string;
  color: string;
  born: number;
}

export interface ViewOptions {
  showHpBars: boolean;
  showItemNames: boolean;
  showMonsterNames: boolean;
}

/** 屏幕上一个可点中的角色及其包围盒。 */
interface Hit {
  actor: Actor;
  x: number;
  y: number;
  w: number;
  h: number;
  row: number;
}

export class Renderer {
  private readonly ctx: CanvasRenderingContext2D;
  private readonly ground = new Map<string, HTMLImageElement | 'loading' | 'none'>();
  private floaters: Floater[] = [];
  private hits: Hit[] = [];
  camX = 0;
  camY = 0;
  /** 逻辑像素尺寸（不含缩放） */
  width = 800;
  height = 600;
  zoom = 1;
  hoverActor = 0;
  /** 锁定的目标（手机）：脚下画一个圈 */
  targetActor = 0;
  hoverItem: GroundItem | null = null;
  view: ViewOptions = { showHpBars: true, showItemNames: true, showMonsterNames: true };

  get context(): CanvasRenderingContext2D {
    return this.ctx;
  }

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly sprites: SpriteStore,
    private readonly world: World,
    private readonly catalog: Catalog,
  ) {
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('浏览器不支持 Canvas');
    this.ctx = ctx;
  }

  private dpr = 1;
  /** 人物在画面纵向的位置：原版底栏高，人物略偏上 */
  centerY = 0.47;

  /** 画布铺满窗口；zoom 是地图的缩放（CSS 像素 / 逻辑像素），由 ui/viewport 按设备定。 */
  resize(cssW: number, cssH: number, zoom: number): void {
    this.dpr = Math.min(2, window.devicePixelRatio || 1);
    this.zoom = zoom;
    this.width = Math.round(cssW / this.zoom);
    this.height = Math.round(cssH / this.zoom);
    this.canvas.width = Math.round(cssW * this.dpr);
    this.canvas.height = Math.round(cssH * this.dpr);
    this.canvas.style.width = `${cssW}px`;
    this.canvas.style.height = `${cssH}px`;
    this.scaleTo(this.zoom);
  }

  /** 把画布坐标切到「1 单位 = scale 个 CSS 像素」：地图用 zoom，界面用自己的缩放。 */
  scaleTo(scale: number): void {
    this.ctx.setTransform(this.dpr * scale, 0, 0, this.dpr * scale, 0, 0);
    this.ctx.imageSmoothingEnabled = false;
  }

  /** 屏幕（CSS 像素）→ 逻辑像素。 */
  toLogical(cssX: number, cssY: number): Pos {
    return { x: cssX / this.zoom, y: cssY / this.zoom };
  }

  /** 逻辑像素 → 地图格子。 */
  cellAt(lx: number, ly: number): Pos {
    return { x: Math.floor((lx + this.camX) / UNITX), y: Math.floor((ly + this.camY) / UNITY) };
  }

  /**
   * 逻辑像素下的角色（取最靠前的一个）。slop > 0 时（触屏）正中没点到任何角色，
   * 就取点击框外 slop 以内离得最近的一个；exclude 是不参与放宽的角色（自己）。
   */
  actorAt(lx: number, ly: number, slop = 0, exclude = 0): Actor | undefined {
    let best: Hit | undefined;
    for (const h of this.hits) {
      if (lx >= h.x && lx < h.x + h.w && ly >= h.y && ly < h.y + h.h && (!best || h.row >= best.row)) best = h;
    }
    if (best || slop <= 0) return best?.actor;
    let bd = slop;
    for (const h of this.hits) {
      if (h.actor.id === exclude) continue;
      const dx = Math.max(h.x - lx, 0, lx - (h.x + h.w));
      const dy = Math.max(h.y - ly, 0, ly - (h.y + h.h));
      const d = Math.hypot(dx, dy);
      if (d <= bd) {
        best = h;
        bd = d;
      }
    }
    return best?.actor;
  }

  /** 角色在世界中的像素位置（含走路插值）。 */
  actorPixel(a: Actor, now: number): Pos {
    const act = a.action;
    if ((act.kind === 'walk' || act.kind === 'run') && act.from) {
      const t = Math.min(1, (now - act.startedAt) / act.duration);
      return {
        x: (act.from.x + (a.x - act.from.x) * t) * UNITX,
        y: (act.from.y + (a.y - act.from.y) * t) * UNITY,
      };
    }
    return { x: a.x * UNITX, y: a.y * UNITY };
  }

  /** 进游戏前把玩家周围一屏的地面和物件先下载好，避免一进来画面缺块。 */
  async preloadAround(center: Pos, onProgress: (done: number, total: number) => void): Promise<void> {
    const map = this.world.map;
    const rx = Math.ceil(this.width / UNITX / 2) + 2;
    const ry = Math.ceil(this.height / UNITY / 2) + 2;
    const objects = new Map<string, Set<number>>();
    for (let y = center.y - ry; y <= center.y + ry + LONG_ROWS; y++) {
      for (let x = center.x - rx - WIDE_COLS; x <= center.x + rx; x++) {
        if (!map.inside(x, y)) continue;
        const i = y * map.width + x;
        const f = map.front[i]!;
        if (!f) continue;
        const area = map.area[i]!;
        const lib = area === 0 ? 'objects' : `objects${area + 1}`;
        const set = objects.get(lib) ?? new Set<number>();
        const ani = map.ani[i]! & 0x7f;
        for (let k = 0; k < Math.max(1, ani); k++) set.add(f - 1 + k);
        objects.set(lib, set);
      }
    }
    const jobs: Promise<unknown>[] = [];
    for (const [lib, set] of objects) jobs.push(this.sprites.preload(lib, set));
    const bw = GROUND_BLOCK * UNITX;
    const bh = GROUND_BLOCK * UNITY;
    const cx = center.x * UNITX;
    const cy = center.y * UNITY;
    for (let by = Math.floor((cy - this.height) / bh); by <= Math.floor((cy + this.height) / bh); by++) {
      for (let bx = Math.floor((cx - this.width) / bw); bx <= Math.floor((cx + this.width) / bw); bx++) {
        if (bx < 0 || by < 0) continue;
        jobs.push(new Promise<void>((res) => {
          const tick = (): void => {
            const v = this.ground.get(`${map.id}/${bx}_${by}`);
            if (v === undefined) this.groundImage(map.id, bx, by);
            if (v === 'loading' || v === undefined) setTimeout(tick, 50);
            else res();
          };
          tick();
        }));
      }
    }
    let done = 0;
    onProgress(0, jobs.length);
    await Promise.all(jobs.map((j) => j.then(() => onProgress(++done, jobs.length))));
  }

  float(actor: number, text: string, color: string): void {
    this.floaters.push({ actor, text, color, born: performance.now() });
  }

  // ---------------- 主绘制 ----------------

  draw(): void {
    const w = this.world;
    const map = w.map;
    if (!map) return;
    const now = w.now;
    const ctx = this.ctx;
    const me = w.player;
    this.scaleTo(this.zoom);
    const mp = this.actorPixel(me, now);
    this.camX = Math.round(mp.x + UNITX / 2 - this.width / 2);
    this.camY = Math.round(mp.y + UNITY / 2 - this.height * this.centerY);

    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, this.width, this.height);
    this.drawGround(map);

    const left = Math.floor(this.camX / UNITX);
    const top = Math.floor(this.camY / UNITY);
    const right = Math.ceil((this.camX + this.width) / UNITX);
    const bottom = Math.ceil((this.camY + this.height) / UNITY);
    const aniCount = Math.floor(performance.now() / 100);

    // 每行里要画的角色、物品
    const actorsByRow = new Map<number, Actor[]>();
    for (const a of w.actors.values()) {
      if (a.map !== map.id) continue;
      const px = this.actorPixel(a, now);
      const row = Math.round(px.y / UNITY);
      if (row < top - 2 || row > bottom + 4) continue;
      const list = actorsByRow.get(row) ?? [];
      list.push(a);
      actorsByRow.set(row, list);
    }
    const itemsByRow = new Map<number, GroundItem[]>();
    for (const it of w.items) {
      const list = itemsByRow.get(it.y) ?? [];
      list.push(it);
      itemsByRow.set(it.y, list);
    }

    // 第一遍：48x32 的平铺物件（地面装饰）
    for (let y = top - 1; y <= bottom + 1; y++) {
      for (let x = left - 1; x <= right + 1; x++) {
        const s = this.objectSprite(map, x, y, aniCount);
        if (s && !s.blend && s.sprite.w === UNITX && s.sprite.h === UNITY) {
          this.blit(s.sprite, x * UNITX - this.camX, (y + 1) * UNITY - s.sprite.h - this.camY);
        }
      }
    }

    // 第二遍：按行画高物件 + 物品 + 角色
    this.hits = [];
    for (let y = top - 1; y <= bottom + LONG_ROWS; y++) {
      for (let x = left - WIDE_COLS; x <= right + 2; x++) {
        const s = this.objectSprite(map, x, y, aniCount);
        if (!s) continue;
        const sx = x * UNITX - this.camX;
        if (s.blend) {
          ctx.globalCompositeOperation = 'lighter';
          this.blit(s.sprite, sx + s.sprite.ox - 2, y * UNITY - this.camY + s.sprite.oy - 68);
          ctx.globalCompositeOperation = 'source-over';
        } else if (s.sprite.w !== UNITX || s.sprite.h !== UNITY) {
          this.blit(s.sprite, sx, (y + 1) * UNITY - s.sprite.h - this.camY);
        }
      }
      if (y <= bottom + 2) {
        for (const it of itemsByRow.get(y) ?? []) this.drawGroundItem(it);
        const row = actorsByRow.get(y);
        if (row) {
          // 尸体先画
          row.sort((a, b) => Number(b.dead) - Number(a.dead));
          for (const a of row) this.drawActor(a, now, y);
        }
      }
    }

    this.drawEffects(now);
    this.drawOverlays(now);
  }

  private blit(s: Sprite, x: number, y: number): void {
    if (x > this.width || y > this.height || x + s.w < 0 || y + s.h < 0) return;
    this.ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, x, y, s.w, s.h);
  }

  private drawGround(map: MapData): void {
    const bw = GROUND_BLOCK * UNITX;
    const bh = GROUND_BLOCK * UNITY;
    const bx0 = Math.floor(this.camX / bw);
    const by0 = Math.floor(this.camY / bh);
    const bx1 = Math.floor((this.camX + this.width) / bw);
    const by1 = Math.floor((this.camY + this.height) / bh);
    for (let by = by0 - 1; by <= by1 + 1; by++) {
      for (let bx = bx0 - 1; bx <= bx1 + 1; bx++) {
        if (bx < 0 || by < 0 || bx * GROUND_BLOCK >= map.width || by * GROUND_BLOCK >= map.height) continue;
        const img = this.groundImage(map.id, bx, by);
        const visible = bx >= bx0 && bx <= bx1 && by >= by0 && by <= by1;
        // 多画 1 像素，避免缩放后块与块之间露缝
        if (img && visible) this.ctx.drawImage(img, bx * bw - this.camX, by * bh - this.camY, bw + 1, bh + 1);
      }
    }
  }

  private groundImage(map: string, bx: number, by: number): HTMLImageElement | null {
    const key = `${map}/${bx}_${by}`;
    const cur = this.ground.get(key);
    if (cur instanceof HTMLImageElement) return cur;
    if (cur) return null;
    this.ground.set(key, 'loading');
    const img = new Image();
    img.onload = () => this.ground.set(key, img);
    img.onerror = () => this.ground.set(key, 'none');
    img.src = assetUrl(`assets/ground/${map}/${bx}_${by}.webp`);
    return null;
  }

  /** 前景层某格要画的图（含动画、开门）。 */
  private objectSprite(map: MapData, x: number, y: number, aniCount: number): { sprite: Sprite; blend: boolean } | null {
    if (!map.inside(x, y)) return null;
    const i = y * map.width + x;
    let idx = map.front[i]!;
    if (!idx) return null;
    let ani = map.ani[i]!;
    const blend = (ani & 0x80) !== 0;
    ani &= 0x7f;
    if (ani > 0) {
      const tick = map.aniTick[i]!;
      idx += Math.floor((aniCount % (ani + ani * tick)) / (1 + tick));
    }
    const area = map.area[i]!;
    const lib = area === 0 ? 'objects' : `objects${area + 1}`;
    const s = this.sprites.get(lib, idx - 1);
    return s ? { sprite: s, blend } : null;
  }

  private drawGroundItem(it: GroundItem): void {
    const look = isGold(it.item) ? goldLook(it.item.gold) : this.catalog.item(it.item.name).look;
    const s = this.sprites.get('dnitems', look);
    const cx = it.x * UNITX - this.camX + UNITX / 2;
    const cy = it.y * UNITY - this.camY + UNITY / 2;
    if (s) this.blit(s, Math.round(cx - s.w / 2), Math.round(cy - s.h / 2));
  }

  // ---------------- 角色 ----------------

  private drawActor(a: Actor, now: number, row: number): void {
    const px = this.actorPixel(a, now);
    const dx = Math.round(px.x - this.camX);
    const dy = Math.round(px.y - this.camY);
    const ctx = this.ctx;
    const invisible = a.invisibleUntil > now;
    if (invisible) ctx.globalAlpha = a.kind === 'player' && a === this.world.player ? 0.45 : 0;
    let body: Sprite | null | undefined;
    if (a.kind === 'player') body = this.drawHuman(a, now, dx, dy);
    else if (a.kind === 'monster') body = this.drawMonster(a, now, dx, dy);
    else {
      const off = npcOffset(a.def.appearance);
      const f = Math.floor(now / 200) % 4;
      body = this.sprites.get('npc', off + f);
      if (body) this.blit(body, dx + body.ox, dy + body.oy);
    }
    ctx.globalAlpha = 1;
    if (body && !a.dead) {
      this.hits.push({ actor: a, x: dx + body.ox, y: dy + body.oy, w: body.w, h: body.h, row });
    }
    if (a.shieldUntil > now) {
      const s = this.sprites.get('magic', 3890 + (Math.floor(now / 100) % 3));
      if (s) {
        ctx.globalCompositeOperation = 'lighter';
        this.blit(s, dx + s.ox, dy + s.oy);
        ctx.globalCompositeOperation = 'source-over';
      }
    }
  }

  private drawHuman(p: Player, now: number, dx: number, dy: number): Sprite | null | undefined {
    const frame = humanFrame(p.action, p.dir, now, p.dead);
    const dressItem = p.equip.dress ? this.catalog.item(p.equip.dress.name) : null;
    const dress = (dressItem ? dressItem.shape : 0) * 2 + p.sex;
    const body = this.sprites.get('hum', dress * HUMAN_FRAMES + frame) ?? this.sprites.get('hum', p.sex * HUMAN_FRAMES + frame);
    const wItem = p.equip.weapon ? this.catalog.item(p.equip.weapon.name) : null;
    const weapon = wItem ? this.sprites.get('weapon', (wItem.shape * 2 + p.sex) * HUMAN_FRAMES + frame) : null;
    const hair = p.hair > 0 ? this.sprites.get('hair', (p.hair * 2 + p.sex) * HUMAN_FRAMES + frame) : null;
    const front = weaponInFront(p.sex, frame);
    if (weapon && !front) this.blit(weapon, dx + weapon.ox, dy + weapon.oy);
    if (body) this.blit(body, dx + body.ox, dy + body.oy);
    if (hair) this.blit(hair, dx + hair.ox, dy + hair.oy);
    if (weapon && front) this.blit(weapon, dx + weapon.ox, dy + weapon.oy);
    // 剑术刀光（HitEffectBase）
    const a = p.action;
    if (a.kind === 'heavy' && now - a.startedAt < a.duration) {
      const base = a.hit === 'power' ? 800 : a.hit === 'long' ? 1410 : a.hit === 'wide' ? 1700 : a.hit === 'fire' ? 3390 : -1;
      if (base >= 0) {
        const f = Math.min(5, Math.floor(((now - a.startedAt) / a.duration) * 6));
        const s = this.sprites.get('magic', base + p.dir * 10 + f);
        if (s) {
          this.ctx.globalCompositeOperation = 'lighter';
          this.blit(s, dx + s.ox, dy + s.oy);
          this.ctx.globalCompositeOperation = 'source-over';
        }
      }
    }
    return body;
  }

  private drawMonster(m: Monster, now: number, dx: number, dy: number): Sprite | null | undefined {
    const t = this.world.actTable(m);
    if (!t) return null;
    const f = monsterFrame(t, m.action, m.dir, now, m.dead);
    const s = this.sprites.get(m.def.lib, m.def.offset + f);
    if (s) this.blit(s, dx + s.ox, dy + s.oy);
    return s;
  }

  // ---------------- 特效 ----------------

  private drawEffects(now: number): void {
    const ctx = this.ctx;
    for (const e of this.world.effects) {
      const p = this.effectPos(e, now);
      if (!p) continue;
      const f = Math.min(e.frames - 1, Math.floor((now - e.startedAt) / e.frameTime) % e.frames);
      const s = this.sprites.get(e.lib, e.start + f);
      if (!s) continue;
      if (e.blend) ctx.globalCompositeOperation = 'lighter';
      this.blit(s, Math.round(p.x - this.camX + s.ox), Math.round(p.y - this.camY + s.oy));
      ctx.globalCompositeOperation = 'source-over';
    }
  }

  private effectPos(e: VisualEffect, now: number): Pos | null {
    let target: Pos;
    if ('actor' in e.at) {
      const a = this.world.actors.get(e.at.actor);
      if (!a) return null;
      target = this.actorPixel(a, now);
    } else {
      target = { x: e.at.x * UNITX, y: e.at.y * UNITY };
    }
    if (e.from) {
      const t = Math.min(1, (now - e.startedAt) / Math.max(1, e.duration));
      return { x: e.from.x * UNITX + (target.x - e.from.x * UNITX) * t, y: e.from.y * UNITY + (target.y - e.from.y * UNITY) * t };
    }
    return target;
  }

  // ---------------- 名字、血条、飘字 ----------------

  private drawOverlays(now: number): void {
    const ctx = this.ctx;
    const w = this.world;
    ctx.font = '12px "PingFang SC","Microsoft YaHei",sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'bottom';
    for (const a of w.actors.values()) {
      if (a.dead || a.map !== w.map.id || (a.invisibleUntil > now && a !== w.player)) continue;
      const px = this.actorPixel(a, now);
      const x = Math.round(px.x - this.camX + UNITX / 2);
      const y = Math.round(px.y - this.camY);
      if (x < -50 || x > this.width + 50 || y < -50 || y > this.height + 120) continue;
      if (a.id === this.targetActor) {
        ctx.strokeStyle = '#ff5a3a';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.ellipse(x, y + UNITY / 2, 22, 10, 0, 0, Math.PI * 2);
        ctx.stroke();
      }
      const hovered = a.id === this.hoverActor || a.id === this.targetActor;
      if (a.kind === 'npc' || hovered || (a.kind === 'monster' && this.view.showMonsterNames) || a.kind === 'player') {
        const name = a.kind === 'player' ? a.name : a.kind === 'npc' ? a.def.name : w.catalog.displayName(a.def.name);
        const color = a.kind === 'npc' ? '#7fdcff' : a.kind === 'monster' ? (a.master ? '#9cff8a' : '#fff') : '#fff';
        this.text(name, x, y - 66, color);
      }
      if (this.view.showHpBars && a.kind !== 'npc') {
        const max = a.kind === 'player' ? a.stats.maxHp : a.maxHp;
        const ratio = Math.max(0, Math.min(1, a.hp / max));
        ctx.fillStyle = 'rgba(0,0,0,.6)';
        ctx.fillRect(x - 16, y - 62, 32, 4);
        ctx.fillStyle = a.kind === 'player' ? '#2ecc71' : a.master ? '#2ecc71' : '#e74c3c';
        ctx.fillRect(x - 16, y - 62, Math.round(32 * ratio), 4);
      }
    }
    if (this.view.showItemNames) {
      for (const it of w.items) {
        const x = it.x * UNITX - this.camX + UNITX / 2;
        const y = it.y * UNITY - this.camY + 4;
        if (x < -40 || x > this.width + 40 || y < -20 || y > this.height + 20) continue;
        const name = isGold(it.item) ? `金币 ${it.item.gold}` : it.item.name;
        const kind = isGold(it.item) ? 'gold' : itemKind(this.catalog.item(it.item.name)).kind;
        this.text(name, x, y, kind === 'equip' ? '#ffd76b' : kind === 'gold' ? '#ffe08a' : '#ddd');
      }
    }
    // 飘字
    const t = performance.now();
    this.floaters = this.floaters.filter((f) => t - f.born < 1100);
    ctx.font = 'bold 14px sans-serif';
    for (const f of this.floaters) {
      const a = w.actors.get(f.actor);
      if (!a) continue;
      const px = this.actorPixel(a, now);
      const k = (t - f.born) / 1100;
      this.text(f.text, px.x - this.camX + UNITX / 2, px.y - this.camY - 72 - k * 30, f.color);
    }
  }

  private text(s: string, x: number, y: number, color: string): void {
    const ctx = this.ctx;
    ctx.fillStyle = '#000';
    ctx.fillText(s, x + 1, y + 1);
    ctx.fillStyle = color;
    ctx.fillText(s, x, y);
  }
}

/** 金币在地上的图：按数量换图（原版 GetGoldLooks）。 */
function goldLook(n: number): number {
  if (n >= 1000) return 116;
  if (n >= 500) return 115;
  if (n >= 300) return 114;
  if (n >= 100) return 113;
  return 112;
}

/** 原版 GetNpcOffset。 */
export function npcOffset(appr: number): number {
  if (appr <= 22) return appr * 60;
  if (appr === 23) return 1380;
  if (appr === 24 || appr === 25) return (appr - 24) * 60 + 1470;
  if (appr === 27 || appr === 32) return (appr - 26) * 60 + 1620 - 30;
  if (appr === 26 || (appr >= 28 && appr <= 41)) return (appr - 26) * 60 + 1620;
  if (appr === 42 || appr === 43) return 2580;
  if (appr >= 44 && appr <= 47) return 2640;
  if (appr >= 48 && appr <= 50) return (appr - 48) * 60 + 2700;
  if (appr === 51) return 2880;
  if (appr === 52) return 2960;
  return 0;
}
