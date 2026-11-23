/**
 * 原版界面（照 ref/mir2delphi/client/FState.pas 的窗口、图号、坐标）：
 * 底栏 DBottom、状态窗 DStateWin（装备/属性/能力/技能 4 页）、包裹 DItemBag、商人对话 DMerchantDlg、
 * 商品列表 DMenuDlg、出售/保管 DSellDlg、技能快捷键 DKeySelDlg、设置 DConfigDlg（便利功能）。
 * 物品操作和原版一样：点一下拿起、再点放下；双击使用；拿着物品点地面就丢下。
 */
import type { Catalog } from '../../data/defs';
import type { Npc, Player } from '../../world/entities';
import { type EquipSlot, type ItemInstance, type ItemKind, itemKind, newItem } from '../../world/items';
import { manaCost } from '../../world/combat';
import type { World } from '../../world/world';
import type { Controller } from '../../game/controller';
import { SETTING_LABELS } from '../../game/settings';
import { JOB_NAME } from '../../world/rules';
import { type Line, itemLines, statLines } from '../describe';
import { BOARD_JOYSTICK, type LayoutKind } from '../viewport';
import { Gfx, MAIN, type Rect, distTo, goldStr, inside } from './gfx';
import { BoardLayout, type Button, type ChatColor, type ChatLine, type Insets, type Layout, type Pt, type UiHost } from './layout';
import { TouchLayout } from './touch';
import { Joystick } from '../../game/joystick';

const LINE_H = 14; // 商品列表行高
const MAX_MENU = 10;

/** 手上拿着的物品：从哪里拿起来的（放下前物品留在原处，不会丢）。 */
type HandSource = { kind: 'bag'; index: number } | { kind: 'belt'; index: number } | { kind: 'equip'; slot: EquipSlot };
interface Hand {
  src: HandSource;
  item: ItemInstance;
}

type MerchantMode = 'talk' | 'buy' | 'sell' | 'store' | 'getback';

/** 手机上点一下物品（包裹、身上、快捷栏、商品列表）弹出的说明 + 操作菜单 */
interface ItemMenu {
  lines: Line[];
  actions: MenuAction[];
  /** 被点的格子，菜单摆在它旁边 */
  anchor: Rect;
  /** 做完操作后菜单留着（连续买药） */
  keepOpen?: boolean;
}

/** 物品菜单里「使用」按钮叫什么；没法直接用的是 null */
const USE_LABEL: Record<ItemKind['kind'], string | null> = {
  potion: '使用', scroll: '使用', book: '学习', bundle: '拆开', equip: '穿戴', amulet: '装备', material: null, other: null,
};

interface MenuAction {
  label: string;
  run: () => void;
}

const EQUIP_RECTS: [EquipSlot, Rect][] = [
  ['necklace', { x: 168, y: 87, w: 34, h: 31 }],
  ['helmet', { x: 115, y: 93, w: 18, h: 18 }],
  ['braceletR', { x: 42, y: 176, w: 34, h: 31 }],
  ['braceletL', { x: 168, y: 176, w: 34, h: 31 }],
  ['ringR', { x: 42, y: 215, w: 34, h: 31 }],
  ['ringL', { x: 168, y: 215, w: 34, h: 31 }],
  ['weapon', { x: 47, y: 80, w: 47, h: 87 }],
  ['dress', { x: 96, y: 122, w: 53, h: 112 }],
];

export class ClassicUi implements UiHost {
  readonly chat: ChatLine[] = [];
  private hand: Hand | null = null;
  private mouse = { x: -1, y: -1 };
  private pressed: Button | null = null;
  private lastClick = { t: 0, key: '' };

  /** 小地图：0 关，1 半透明，2 不透明（原版 g_nViewMinMapLv） */
  miniMap = 1;
  stateOpen = false;
  statePage = 0;
  private magicPage = 0;
  bagOpen = false;
  settingsOpen = false;
  private keyDlg: { skill: number } | null = null;
  private npc: Npc | null = null;
  private mode: MerchantMode = 'talk';
  private menuTop = 0;
  private menuIndex = -1;
  private sellSpot: Hand | null = null;
  private links: { rect: Rect; cmd: string }[] = [];
  /** 手机左侧虚拟摇杆（输入层改它，触屏布局画它） */
  readonly joystick = new Joystick();
  /** 手机：点了物品弹出的操作菜单 */
  private itemMenu: ItemMenu | null = null;

  /** 每帧算出的界面占用区域（点在里面就不传给地图） */
  private areas: (Rect & { z: number })[] = [];
  private buttons: (Button & { z: number })[] = [];
  /** 登记顺序（后登记的在上层）：被上层窗口盖住的按钮点不到 */
  private z = 0;
  W = 800;
  H = 600;
  /** 安全区（刘海、圆角），界面像素；触屏按钮和贴边的小地图要避开 */
  safe: Insets = { left: 0, right: 0, top: 0, bottom: 0 };
  layout: Layout;
  /** 触屏操作件放大倍数（main 按屏幕设） */
  ctlScale = 1;

  constructor(
    readonly g: Gfx,
    readonly world: World,
    readonly ctl: Controller,
    readonly catalog: Catalog,
    /** 触屏为主的设备（平板用原版底栏时右侧加一列技能键） */
    private readonly touch: boolean,
  ) {
    this.layout = new BoardLayout(this, touch, BOARD_JOYSTICK);
  }

  /** 切换布局（屏幕大小变了时）。 */
  setLayout(kind: LayoutKind): void {
    if (kind === this.layout.kind) return;
    this.layout = kind === 'touch' ? new TouchLayout(this) : new BoardLayout(this, this.touch, BOARD_JOYSTICK);
    this.itemMenu = null;
  }

  get p(): Player {
    return this.world.player;
  }

  message(text: string, color: ChatColor = 'system'): void {
    this.chat.push({ text, color, at: performance.now() });
    if (this.chat.length > 200) this.chat.splice(0, this.chat.length - 200);
  }

  openNpc(npc: Npc): void {
    this.npc = npc;
    this.mode = 'talk';
    this.bagOpen = true;
    this.menuIndex = -1;
    this.menuTop = 0;
  }

  closeAll(): void {
    this.returnSellSpot();
    this.npc = null;
    this.stateOpen = false;
    this.bagOpen = false;
    this.settingsOpen = false;
    this.keyDlg = null;
    this.itemMenu = null;
    this.cancelHand();
  }

  toggleBag(): void {
    this.bagOpen = !this.bagOpen;
    this.itemMenu = null;
  }

  toggleState(page: number): void {
    this.itemMenu = null;
    if (this.stateOpen && this.statePage === page) this.stateOpen = false;
    else {
      this.stateOpen = true;
      this.statePage = page;
    }
  }

  toggleSettings(): void {
    this.settingsOpen = !this.settingsOpen;
  }

  logout(): void {
    this.onLogout();
  }

  cancelHand(): void {
    this.hand = null;
  }

  beltInHand(i: number): boolean {
    return this.isHandFrom('belt', i);
  }

  area(r: Rect): void {
    this.areas.push({ ...r, z: this.z++ });
  }

  /** 登记一个点击区（不画） */
  private hit(b: Button): void {
    this.buttons.push({ ...b, z: this.z++ });
  }

  // ======================= 绘制 =======================

  draw(W: number, H: number, now: number): void {
    this.W = W;
    this.H = H;
    this.areas = [];
    this.buttons = [];
    this.z = 0;
    this.menuRect = null;
    // 小地图先画：触屏技能环伸到右上角时压在小地图上面，点得到
    if (this.miniMap) this.drawMiniMap(now);
    this.layout.drawHud(now);
    if (this.npc) this.drawMerchant();
    if (this.bagOpen) this.drawBag();
    if (this.stateOpen) this.drawState();
    if (this.settingsOpen) this.drawSettings();
    if (this.keyDlg) this.drawKeyDlg();
    if (this.itemMenu) this.drawItemMenu(this.itemMenu);
    else this.drawHover();
    if (this.hand) {
      const def = this.catalog.item(this.hand.item.name);
      this.g.imgCenter('items', def.look, this.mouse.x - 20, this.mouse.y - 16, 40, 32);
    }
  }

  button(b: Button): void {
    this.hit(b);
    if (b.img < 0) return;
    if (b.always || this.isPressed(b)) {
      this.g.img(MAIN, b.always && this.isPressed(b) ? b.img + 1 : b.img, b.x, b.y);
    }
  }

  isPressed(r: Rect): boolean {
    const b = this.pressed;
    return !!b && b.x === r.x && b.y === r.y && b.w === r.w && b.h === r.h;
  }

  /** 退出按钮：存档后回到登录画面 */
  onLogout: () => void = () => {};

  toggleMiniMap(): void {
    this.miniMap = (this.miniMap + 1) % 3;
  }
  // ---------------- 小地图（PlayScn.DrawMiniMap） ----------------

  private miniRect(): { idx: number; rc: Rect; x: number; y: number } | null {
    const idx = this.catalog.data.minimaps[this.world.map.id];
    if (idx === undefined) return null;
    const s = this.g.sprite('mmap', idx - 1);
    if (!s) return null;
    const p = this.p;
    const mx = Math.floor((p.x * 48) / 32);
    const my = p.y;
    const left = Math.max(0, Math.min(s.w - 120, mx - 60));
    const top = Math.max(0, Math.min(s.h - 120, my - 60));
    const tr = this.layout.miniMapRight();
    const w = Math.min(120, s.w);
    return { idx: idx - 1, rc: { x: left, y: top, w, h: Math.min(120, s.h) }, x: tr.x - w, y: tr.y };
  }

  private drawMiniMap(now: number): void {
    const m = this.miniRect();
    if (!m) return;
    const g = this.g;
    const ctx = g.ctx;
    ctx.globalAlpha = this.miniMap === 1 ? 0.7 : 1;
    g.imgPart('mmap', m.idx, m.x, m.y, m.rc.x, m.rc.y, m.rc.w, m.rc.h);
    ctx.globalAlpha = 1;
    this.area({ x: m.x, y: m.y, w: m.rc.w, h: m.rc.h });
    this.hit({
      x: m.x, y: m.y, w: m.rc.w, h: m.rc.h, img: -1,
      onClick: () => {
        // 点小地图走过去（原版没有，方便）
        const tx = Math.round((this.mouse.x - m.x + m.rc.x) * (32 / 48));
        const ty = Math.round(this.mouse.y - m.y + m.rc.y);
        this.ctl.goTo({ x: tx, y: ty });
      },
    });
    const dot = (cx: number, cy: number, color: string, size = 2): void => {
      const px = m.x + Math.floor((cx * 48) / 32) - m.rc.x;
      const py = m.y + cy - m.rc.y;
      if (px >= m.x && py >= m.y && px < m.x + m.rc.w && py < m.y + m.rc.h) g.rect(px, py, size, size, color);
    };
    for (const a of this.world.actors.values()) {
      if (a.dead || a === this.p || Math.abs(a.x - this.p.x) > 30 || Math.abs(a.y - this.p.y) > 30) continue;
      dot(a.x, a.y, a.kind === 'npc' ? '#0f0' : a.kind === 'monster' && a.master ? '#6cf' : '#f00');
    }
    if (Math.floor(now / 300) % 2 === 0) dot(this.p.x, this.p.y, '#fff', 3);
    const it = this.ctl.intent;
    if (it.kind === 'goto') dot(it.target.x, it.target.y, '#ff0', 3);
  }

  // ---------------- 状态窗 ----------------

  private stateOrigin(): Pt {
    return this.layout.stateOrigin(this.bagOpen);
  }

  private drawState(): void {
    const g = this.g;
    const { x, y } = this.stateOrigin();
    const p = this.p;
    this.area({ x, y, w: 232, h: 325 });
    g.img(MAIN, 370, x, y);
    // 名字（标题栏）
    const title = `${p.name}`;
    g.text(title, x + 116 - g.width(title) / 2, y + 23, '#ffe08a', true);
    switch (this.statePage) {
      case 0:
        this.drawEquipPage(x, y);
        break;
      case 1: {
        const s = p.stats;
        const vals = [s.ac, s.mac, s.dc, s.mc, s.sc].map((r) => `${r[0]}-${r[1]}`);
        vals.push(`${p.hp}/${s.maxHp}`, `${p.mp}/${s.maxMp}`);
        vals.forEach((v, i) => g.text(v, x + 112, y + 99 + i * 20, '#fff'));
        break;
      }
      case 2:
        this.drawAbilityPage(x, y);
        break;
      case 3:
        this.drawMagicPage(x, y);
        break;
    }
    this.button({ img: 371, x: x + 8, y: y + 39, w: 16, h: 23, onClick: () => (this.stateOpen = false) });
    this.button({ img: 373, x: x + 7, y: y + 128, w: 24, h: 23, onClick: () => (this.statePage = (this.statePage + 3) % 4) });
    this.button({ img: 372, x: x + 7, y: y + 187, w: 24, h: 23, onClick: () => (this.statePage = (this.statePage + 1) % 4) });
  }

  private drawEquipPage(x: number, y: number): void {
    const g = this.g;
    const p = this.p;
    g.img(MAIN, p.sex === 1 ? 377 : 376, x + 38, y + 52);
    const bbx = x + 31;
    const bby = y + 96;
    const hairIdx = (p.sex === 1 ? 480 : 440) + p.hair;
    const hair = g.sprite(MAIN, hairIdx);
    if (hair) g.img(MAIN, hairIdx, bbx + hair.ox, bby + hair.oy);
    for (const slot of ['dress', 'weapon', 'helmet'] as const) {
      const it = p.equip[slot];
      if (!it || this.isHandFrom('equip', slot)) continue;
      const look = this.catalog.item(it.name).look;
      const s = g.sprite('stateitem', look);
      if (s) g.img('stateitem', look, bbx + s.ox, bby + s.oy);
    }
    for (const [slot, r] of EQUIP_RECTS) {
      if (slot === 'dress' || slot === 'weapon' || slot === 'helmet') continue;
      const it = p.equip[slot];
      if (it && !this.isHandFrom('equip', slot)) g.imgCenter('items', this.catalog.item(it.name).look, x + r.x, y + r.y, r.w, r.h);
    }
  }

  private drawAbilityPage(x: number, y: number): void {
    const g = this.g;
    const p = this.p;
    const s = p.stats;
    g.img(MAIN, 382, x + 38, y + 52);
    const bx = x + 58;
    const by = y + 62;
    const mx = bx + 85;
    const need = this.catalog.expToNext(p.level);
    const rows: [string, string, boolean?][] = [
      ['经验', need === Infinity ? '满级' : `${((100 * p.exp) / need).toFixed(2)}%`],
      ['背包负重', `${this.world.bagWeight(p)}/${s.maxBagWeight}`, this.world.bagWeight(p) > s.maxBagWeight],
      ['穿戴负重', `${this.world.wearWeight(p)}/${s.maxWearWeight}`],
      ['腕力', `${p.equip.weapon ? this.catalog.item(p.equip.weapon.name).weight : 0}/${s.maxHandWeight}`],
      ['准确', String(s.hit)],
      ['敏捷', String(s.agility)],
      ['魔法躲避', `+${s.magicAvoid * 10}%`],
      ['毒物躲避', `+${s.poisonAvoid * 10}%`],
      ['攻击间隔', `${s.hitInterval}ms`],
      ['体力恢复', `+${s.hpRegen * 10}%`],
      ['魔法恢复', `+${s.mpRegen * 10}%`],
    ];
    rows.forEach(([k, v, bad], i) => {
      g.text(k, bx, by + 14 * i, '#c0c0c0');
      g.text(v, mx, by + 14 * i, bad ? '#f00' : '#c0c0c0');
    });
  }

  private drawMagicPage(x: number, y: number): void {
    const g = this.g;
    const p = this.p;
    g.img(MAIN, 383, x + 38, y + 52);
    const bbx = x + 38;
    const bby = y + 52;
    const list = p.skills;
    const top = this.magicPage * 5;
    for (let i = top; i < Math.min(top + 5, list.length); i++) {
      const ls = list[i]!;
      const def = this.catalog.skillsById.get(ls.id)!;
      const m = i - top;
      const iconR = { x: bbx + 8, y: bby + 8 + m * 37, w: 31, h: 33 };
      g.img('magicon', def.effect * 2, iconR.x, iconR.y);
      this.hit({ ...iconR, img: -1, onClick: () => (this.keyDlg = { skill: ls.id }) });
      g.text(def.name, bbx + 48, bby + 8 + m * 37, '#c0c0c0');
      g.img(MAIN, 112, bbx + 48, bby + 23 + m * 37);
      g.img(MAIN, 111, bbx + 74, bby + 23 + m * 37);
      g.text(String(ls.level), bbx + 64, bby + 23 + m * 37, '#c0c0c0');
      g.text(ls.level < 3 ? `${ls.train}/${def.train[ls.level]}` : '-', bbx + 94, bby + 23 + m * 37, '#c0c0c0');
      if (ls.key) g.text(`F${ls.key}`, bbx + 145, bby + 8 + m * 37, '#ff0', true);
    }
    this.button({ img: 398, x: x + 213, y: y + 113, w: 12, h: 21, always: true, onClick: () => (this.magicPage = Math.max(0, this.magicPage - 1)) });
    this.button({
      img: 396, x: x + 213, y: y + 143, w: 12, h: 21, always: true,
      onClick: () => (this.magicPage = Math.min(Math.max(0, Math.ceil(list.length / 5) - 1), this.magicPage + 1)),
    });
  }

  // ---------------- 包裹 ----------------

  private bagOrigin(): Pt {
    return this.layout.bagOrigin(!!this.npc);
  }

  private gridCell(i: number): Rect {
    const { x, y } = this.bagOrigin();
    return { x: x + 20 + (i % 8) * 36, y: y + 13 + Math.floor(i / 8) * 32, w: 36, h: 32 };
  }

  private drawBag(): void {
    const g = this.g;
    const { x, y } = this.bagOrigin();
    const p = this.p;
    this.area({ x, y, w: 336, h: 270 });
    g.img(MAIN, 3, x, y);
    p.bag.forEach((it, i) => {
      if (!it || this.isHandFrom('bag', i)) return;
      const r = this.gridCell(i);
      g.imgCenter('items', this.catalog.item(it.name).look, r.x - 1, r.y + 1, r.w, r.h);
    });
    g.text(goldStr(p.gold), x + 64, y + 185, '#fff');
    // 物品信息栏
    const it = this.hoverItem();
    if (it) {
      const def = this.catalog.item(it.name);
      const lines = itemLines(def, it, p, null);
      const n = g.text(def.name, x + 70, y + 215, '#ff0');
      const why = itemKind(def).kind === 'equip' ? this.world.canWear(p, def) : null;
      const rest = lines.slice(1, 4);
      g.text(` 重量${def.weight}`, x + 70 + n, y + 215, '#fff');
      rest.forEach((l, i) => {
        if (i < 2) g.text(l.text.split('　')[0]!, x + 70, y + 229 + i * 14, i === 1 && why ? '#f00' : '#fff');
      });
    }
    this.button({ img: 371, x: x + 309, y: y + 203, w: 14, h: 20, onClick: () => (this.bagOpen = false) });
    this.button({
      img: 26, x: x + 254, y: y + 183, w: 48, h: 22,
      onClick: () => {
        if (this.hand) this.useHand();
      },
    });
  }

  // ---------------- 商人 ----------------

  private npcText(): string {
    const n = this.npc!;
    if (n.def.service === 'storage') {
      return `${n.def.name}：\\我替你保管物品，要存还是要取？\\ \\<保管物品/@store>   <取回物品/@getback>\\ \\<离开/@exit>`;
    }
    return `${n.def.name}：\\欢迎光临，看看有什么需要的？\\ \\<购买物品/@buy>   <出售物品/@sell>\\ \\<离开/@exit>`;
  }

  private drawMerchant(): void {
    const g = this.g;
    const o = this.layout.merchantOrigin();
    this.area({ x: o.x, y: o.y, w: 416, h: 176 });
    g.img(MAIN, 384, o.x, o.y);
    this.links = [];
    let ly = o.y + 20;
    for (const raw of this.npcText().split('\\')) {
      let sx = o.x + 30;
      const re = /<([^/>]+)\/(@\w+)>/g;
      let last = 0;
      let m: RegExpExecArray | null;
      while ((m = re.exec(raw))) {
        if (m.index > last) sx += g.text(raw.slice(last, m.index), sx, ly, '#fff', true);
        const label = m[1]!;
        const w = g.text(label, sx, ly, this.mode === m[2]!.slice(1) ? '#f00' : '#ff0', true);
        g.rect(sx, ly + 13, w, 1, '#ff0');
        this.links.push({ rect: { x: sx, y: ly, w, h: 14 }, cmd: m[2]! });
        sx += w;
        last = m.index + m[0].length;
      }
      if (last < raw.length) g.text(raw.slice(last), sx, ly, '#fff', true);
      ly += 16;
    }
    this.button({ img: 64, x: o.x + 399, y: o.y + 1, w: 16, h: 23, onClick: () => this.closeNpc() });
    if (this.mode === 'buy' || this.mode === 'getback') this.drawMenu(o);
    if (this.mode === 'sell' || this.mode === 'store') this.drawSell(o);
  }

  /** 商品列表第 m 行（屏幕上第几行）的点击区 */
  private menuRowRect(m: number): Rect {
    const o = this.layout.merchantOrigin();
    return { x: o.x + 14, y: o.y + 176 + 32 + m * LINE_H, w: 265, h: LINE_H };
  }

  private menuRows(): { name: string; price: string; dura: string; onBuy: () => void; item: ItemInstance | null; defName: string }[] {
    const n = this.npc!;
    if (this.mode === 'getback') {
      return this.p.storage.map((it, i) => ({
        name: it.name, price: '', dura: `${Math.ceil(it.dura / 1000)}/${Math.ceil(it.duraMax / 1000)}`, item: it, defName: it.name,
        onBuy: () => {
          if (!this.world.addToBag(this.p, it)) return this.message('包裹满了', 'bad');
          this.p.storage.splice(i, 1);
          this.menuIndex = -1;
        },
      }));
    }
    return n.def.goods.flatMap((gd) => {
      const def = this.catalog.itemsByName.get(gd.name);
      if (!def) return [];
      return [{
        name: def.name, price: `${def.price} 金币`, dura: def.dura > 100 ? String(Math.ceil(def.dura / 1000)) : '-',
        item: null, defName: def.name, onBuy: () => this.buy(def.name),
      }];
    });
  }

  private drawMenu(o: Pt): void {
    const g = this.g;
    const x = o.x;
    const y = o.y + 176;
    this.area({ x, y, w: 308, h: 205 });
    g.img(MAIN, 385, x, y);
    const store = this.mode === 'getback';
    g.text(store ? '保管物品' : '物品列表', x + 19, y + 11, '#fff');
    g.text(store ? '持久' : '价格', x + 156, y + 11, '#fff');
    if (!store) g.text('持久', x + 245, y + 11, '#fff');
    const rows = this.menuRows();
    for (let i = this.menuTop; i < Math.min(rows.length, this.menuTop + MAX_MENU); i++) {
      const r = rows[i]!;
      const m = i - this.menuTop;
      const color = i === this.menuIndex ? '#f00' : '#fff';
      if (i === this.menuIndex) g.text('•', x + 10, y + 32 + m * LINE_H, color);
      g.text(r.name, x + 19, y + 32 + m * LINE_H, color);
      g.text(store ? r.dura : r.price, x + 156, y + 32 + m * LINE_H, color);
      if (!store) g.text(r.dura, x + 245, y + 32 + m * LINE_H, color);
      const rr = this.menuRowRect(m);
      this.hit({
        ...rr, img: -1,
        onClick: () => {
          if (this.layout.itemMenu) {
            this.menuIndex = i;
            this.openShopMenu(r, rr);
            return;
          }
          if (this.menuIndex === i && this.isDouble(`menu${i}`)) r.onBuy();
          this.menuIndex = i;
        },
      });
    }
    this.button({ img: 388, x: x + 43, y: y + 175, w: 24, h: 24, always: true, onClick: () => (this.menuTop = Math.max(0, this.menuTop - MAX_MENU)) });
    this.button({
      img: 387, x: x + 90, y: y + 175, w: 24, h: 24, always: true,
      onClick: () => {
        if (this.menuTop + MAX_MENU < rows.length) this.menuTop += MAX_MENU;
      },
    });
    this.button({
      img: 386, x: x + 215, y: y + 171, w: 44, h: 16, always: true,
      onClick: () => {
        const r = rows[this.menuIndex];
        if (r) r.onBuy();
      },
    });
    this.button({ img: 64, x: x + 291, y, w: 16, h: 23, onClick: () => (this.mode = 'talk') });
  }

  private drawSell(o: Pt): void {
    const g = this.g;
    const x = o.x + 260;
    const y = o.y + 176;
    this.area({ x, y, w: 140, h: 181 });
    g.img(MAIN, 392, x, y);
    const store = this.mode === 'store';
    g.text(store ? '放入要保管的物品' : '放入要出售的物品', x + 12, y + 30, '#fff');
    const spot = { x: x + 27, y: y + 67, w: 61, h: 52 };
    this.hit({ ...spot, img: -1, onClick: () => this.clickSellSpot() });
    if (this.sellSpot) {
      const def = this.catalog.item(this.sellSpot.item.name);
      g.imgCenter('items', def.look, spot.x, spot.y, spot.w, spot.h);
      if (!store) g.text(`${Math.floor(def.price / 2)} 金币`, x + 30, y + 128, '#ff0');
    }
    this.button({ img: 393, x: x + 85, y: y + 150, w: 24, h: 13, always: true, onClick: () => this.confirmSell() });
    this.button({ img: 64, x: x + 115, y, w: 16, h: 23, onClick: () => this.closeSell() });
  }

  // ---------------- 设置（便利功能） ----------------

  private drawSettings(): void {
    const g = this.g;
    const sz = g.size(MAIN, 204) ?? { w: 608, h: 327 };
    const rowH = this.layout.settingsRowH;
    // 电脑用原版设置窗底图；手机行高加大、窗宽随屏幕，画成同色调的面板
    const classic = this.layout.kind === 'board';
    const w = classic ? sz.w : Math.min(sz.w, this.W - this.safe.left - this.safe.right - 8);
    const h = classic ? sz.h : 40 + SETTING_LABELS.length * rowH + 12;
    const { x, y } = this.layout.dialogOrigin(w, h);
    this.area({ x, y, w, h });
    if (classic) {
      g.img(MAIN, 204, x, y);
      g.rect(x + 50, y + 6, 70, 20, '#2a2a2a');
    } else g.frame(x, y, w, h, 'rgba(16,11,6,.96)', '#8a6a3a');
    g.text('便利设置', x + 56, y + 10, '#ffe08a', true);
    const s = this.ctl.settings as unknown as Record<string, number | boolean>;
    const mid = (rowH - 14) / 2;
    SETTING_LABELS.forEach((item, i) => {
      const ly = y + 40 + i * rowH;
      const v = s[item.key];
      if (item.kind === 'bool') {
        const box = { x: x + 30, y: ly + mid, w: 14, h: 14 };
        g.rect(box.x, box.y, box.w, box.h, '#000');
        g.rect(box.x + 1, box.y + 1, box.w - 2, box.h - 2, '#4a3a20');
        if (v) g.text('✓', box.x + 2, box.y + 1, '#ff0', true);
        g.text(item.label, x + 52, ly + mid + 1, '#fff');
        this.hit({ x: box.x, y: ly, w: Math.min(400, w - 40), h: rowH - 1, img: -1, onClick: () => { s[item.key] = !v; this.ctl.onSettingsChanged(); } });
      } else {
        g.text(item.label, x + 52, ly + mid + 1, '#fff');
        const vx = classic ? x + 360 : x + w - 100;
        g.text('◀', vx, ly + mid + 1, '#ff0', true);
        g.text(`${v}%`, vx + 22, ly + mid + 1, '#fff', true);
        g.text('▶', vx + 62, ly + mid + 1, '#ff0', true);
        const bw = classic ? 20 : 32;
        this.hit({ x: vx + 6 - bw / 2, y: ly, w: bw, h: rowH - 1, img: -1, onClick: () => { s[item.key] = Math.max(0, Number(v) - 5); this.ctl.onSettingsChanged(); } });
        this.hit({ x: vx + 68 - bw / 2, y: ly, w: bw, h: rowH - 1, img: -1, onClick: () => { s[item.key] = Math.min(95, Number(v) + 5); this.ctl.onSettingsChanged(); } });
      }
    });
    if (classic) {
      g.text('F1-F8 技能  1-6 快捷栏  空格 捡取  Esc 关闭  右键跑步', x + 30, y + sz.h - 40, '#c0c0c0');
      this.button({ img: 361, x: x + 514, y: y + 287, w: 80, h: 34, onClick: () => (this.settingsOpen = false) });
      this.button({ img: 64, x: x + 584, y: y + 6, w: 16, h: 23, onClick: () => (this.settingsOpen = false) });
    } else {
      // 手机：右上角大关闭键，旁边是退出（存档回登录画面；手机上没有原版底栏的退出按钮）
      const close = { x: x + w - 44, y: y + 4, w: 40, h: 30 };
      g.frame(close.x, close.y, close.w, close.h, this.isPressed(close) ? '#7a5a2a' : '#3a2a16', '#c9a060');
      g.text('关闭', close.x + 8, close.y + 9, '#ffe08a', true);
      this.hit({ ...close, img: -1, onClick: () => (this.settingsOpen = false) });
      const quit = { x: close.x - 50, y: y + 4, w: 44, h: 30 };
      g.frame(quit.x, quit.y, quit.w, quit.h, this.isPressed(quit) ? '#7a5a2a' : '#3a2a16', '#c9a060');
      g.text('退出', quit.x + 10, quit.y + 9, '#ff8a8a', true);
      this.hit({ ...quit, img: -1, onClick: () => this.onLogout() });
    }
  }

  // ---------------- 技能快捷键 ----------------

  private drawKeyDlg(): void {
    const g = this.g;
    const sz = g.size(MAIN, 229) ?? { w: 376, h: 179 };
    const { x, y } = this.layout.dialogOrigin(sz.w, sz.h);
    this.area({ x, y, w: sz.w, h: sz.h });
    g.img(MAIN, 229, x, y);
    const id = this.keyDlg!.skill;
    const def = this.catalog.skillsById.get(id)!;
    g.img('magicon', def.effect * 2, x + 52, y + 29);
    g.text(def.name, x + 100, y + 40, '#fff', true);
    const xs = [34, 66, 98, 130, 171, 203, 235, 267];
    const ls = this.p.skills.find((s) => s.id === id);
    xs.forEach((bx, i) => {
      const k = i + 1;
      const r = { x: x + bx, y: y + 83, w: 32, h: 30 };
      g.img(MAIN, 232 + i * 2 + (ls?.key === k ? 1 : 0), r.x, r.y);
      this.hit({ ...r, img: -1, onClick: () => this.setKey(id, k) });
    });
    this.hit({ x: x + 299, y: y + 83, w: 40, h: 30, img: -1, onClick: () => this.setKey(id, 0) });
    g.img(MAIN, 230, x + 299, y + 83);
    this.button({ img: 62, x: x + 222, y: y + 131, w: 80, h: 34, always: false, onClick: () => (this.keyDlg = null) });
  }

  private setKey(id: number, key: number): void {
    for (const s of this.p.skills) if (key && s.key === key) s.key = 0;
    const ls = this.p.skills.find((s) => s.id === id);
    if (ls) ls.key = key;
    this.keyDlg = null;
  }

  // ---------------- 悬停说明（加强版：把属性讲清楚） ----------------

  private hoverItem(): ItemInstance | null {
    const { x, y } = this.mouse;
    const p = this.p;
    if (this.bagOpen) {
      for (let i = 0; i < p.bag.length; i++) if (p.bag[i] && inside(this.gridCell(i), x, y)) return p.bag[i]!;
    }
    if (this.stateOpen && this.statePage === 0) {
      for (const [slot, r] of EQUIP_RECTS) {
        if (p.equip[slot] && inside(this.equipRect(r), x, y)) return p.equip[slot];
      }
    }
    const belt = this.layout.beltRects();
    for (let i = 0; i < 6; i++) {
      if (p.belt[i] && inside(belt[i]!, x, y)) return p.belt[i]!;
    }
    return null;
  }

  /** 装备格在屏幕上的位置 */
  private equipRect(r: Rect): Rect {
    const o = this.stateOrigin();
    return { ...r, x: o.x + r.x, y: o.y + r.y };
  }

  private drawHover(): void {
    if (this.hand) return;
    const it = this.hoverItem();
    let lines: Line[] | null = null;
    if (it) {
      const def = this.catalog.item(it.name);
      const k = itemKind(def);
      const cur = k.kind === 'equip' ? this.p.equip[k.slots[0]!] : null;
      lines = itemLines(def, it, this.p, cur && cur !== it ? this.catalog.item(cur.name) : null);
    } else if (this.npc && (this.mode === 'buy' || this.mode === 'getback')) {
      const rows = this.menuRows();
      for (let i = this.menuTop; i < Math.min(rows.length, this.menuTop + MAX_MENU); i++) {
        if (inside(this.menuRowRect(i - this.menuTop), this.mouse.x, this.mouse.y)) {
          const r = rows[i]!;
          const def = this.catalog.item(r.defName);
          lines = itemLines(def, r.item, this.p, null);
        }
      }
    } else if (this.stateOpen && this.statePage === 1) {
      const nearby = [...this.world.actors.values()].filter((a) => a.kind === 'monster' && !a.master && !a.dead) as { agility: number; hit: number }[];
      const avgA = nearby.length ? Math.round(nearby.reduce((s, m) => s + m.agility, 0) / nearby.length) : 15;
      const avgH = nearby.length ? Math.round(nearby.reduce((s, m) => s + m.hit, 0) / nearby.length) : 8;
      const so = this.stateOrigin();
      if (inside({ x: so.x + 40, y: so.y + 90, w: 180, h: 150 }, this.mouse.x, this.mouse.y)) {
        lines = statLines(this.p.stats, this.p, avgA, avgH);
      }
    } else if (this.stateOpen && this.statePage === 3) {
      const top = this.magicPage * 5;
      for (let i = top; i < Math.min(top + 5, this.p.skills.length); i++) {
        const m = i - top;
        const so = this.stateOrigin();
        if (inside({ x: so.x + 46, y: so.y + 60 + m * 37, w: 170, h: 33 }, this.mouse.x, this.mouse.y)) {
          const ls = this.p.skills[i]!;
          const def = this.catalog.skillsById.get(ls.id)!;
          lines = [
            { text: `${def.name}　${ls.level} 级　${JOB_NAME[def.job as 0 | 1 | 2]}`, tone: 'title' },
            { text: `耗魔法 ${manaCost(def, ls.level)}` },
            { text: ls.level < 3 ? `熟练度 ${ls.train}/${def.train[ls.level]}，人物 ${def.needLevel[ls.level]} 级后才能继续涨` : '已练满', tone: 'dim' },
            { text: '点图标设置 F1-F8 快捷键', tone: 'dim' },
          ];
        }
      }
    }
    if (lines) this.hint(lines, this.mouse.x + 14, this.mouse.y + 14);
  }

  /** 原版 ShowHint 风格的黑底说明框。 */
  private hint(lines: Line[], x: number, y: number): void {
    const g = this.g;
    const w = Math.max(...lines.map((l) => g.width(l.text))) + 10;
    const h = lines.length * 14 + 6;
    const hx = Math.min(x, this.W - w - 2);
    const hy = Math.min(y, this.H - h - 2);
    g.rect(hx, hy, w, h, 'rgba(0,0,0,.85)');
    const color: Record<string, string> = { title: '#ff0', good: '#7cff7c', bad: '#f55', dim: '#aaa', gold: '#ffd96b' };
    lines.forEach((l, i) => g.text(l.text, hx + 5, hy + 3 + i * 14, color[l.tone ?? ''] ?? '#fff'));
  }

  // ======================= 输入 =======================

  /** 点在界面上返回 true（不传给地图）。 */
  pointerDown(x: number, y: number, button: number): boolean {
    this.mouse = { x, y };
    if (button === 2 && this.hand) {
      this.hand = null;
      return true;
    }
    // 物品菜单在最上层：点在菜单里只算菜单的按钮；点在外面就关掉它，再照常处理（点别的物品直接换成它的菜单）
    if (this.itemMenu && this.menuRect) {
      const r = this.menuRect;
      if (inside(r, x, y)) {
        const b = this.buttonAt(x, y);
        if (b && inside(r, b.x + 1, b.y + 1)) this.pressed = b;
        return true;
      }
      this.itemMenu = null;
    }
    // 从上往下找按钮
    const b = this.buttonAt(x, y);
    if (b) {
      this.pressed = b;
      return true;
    }
    for (const l of this.links) {
      if (inside(l.rect, x, y)) {
        this.runLink(l.cmd);
        return true;
      }
    }
    if (this.clickSlots(x, y)) return true;
    const onUi = this.areas.some((r) => inside(r, x, y));
    if (!onUi && this.hand) {
      // 拿着物品点地面：丢下
      const it = this.takeHand();
      if (it) this.world.dropAt(this.p.pos, it);
      return true;
    }
    return onUi;
  }

  /** 点到的按钮（后登记的在上面）。手机上原版小按钮（关闭×等）很小，没正好点中时允许偏 8 像素。 */
  private buttonAt(x: number, y: number): Button | undefined {
    for (let i = this.buttons.length - 1; i >= 0; i--) {
      const b = this.buttons[i]!;
      if (inside(b, x, y)) return this.coveredAbove(b.z, x, y) ? undefined : b;
    }
    const slop = this.touchSlop();
    if (!slop) return undefined;
    let best: Button | undefined;
    let bd = slop;
    for (let i = this.buttons.length - 1; i >= 0; i--) {
      const b = this.buttons[i]!;
      // 大块的点击区（技能键、小地图、商品行）不放宽，免得抢了旁边的点击
      if (b.w * b.h > 40 * 40) continue;
      const d = distTo(b, x, y);
      if (d < bd && !this.coveredAbove(b.z, x, y)) {
        best = b;
        bd = d;
      }
    }
    return best;
  }

  /** (x, y) 处有比 z 后登记的界面区域盖着（按钮在下层窗口里，被上层窗口挡住） */
  private coveredAbove(z: number, x: number, y: number): boolean {
    return this.areas.some((r) => r.z > z && inside(r, x, y));
  }

  /** 手机上按钮点击允许偏出的距离（界面像素）；电脑 0 */
  private touchSlop(): number {
    return this.layout.kind === 'touch' ? 8 : 0;
  }

  /** 点按钮时（放按钮音效） */
  onButton: () => void = () => {};

  pointerUp(x: number, y: number): void {
    const b = this.pressed;
    this.pressed = null;
    if (b && distTo(b, x, y) <= this.touchSlop()) {
      this.onButton();
      b.onClick();
    }
  }

  pointerMove(x: number, y: number): void {
    this.mouse = { x, y };
  }

  /** 鼠标在界面上（地图不处理悬停） */
  covers(x: number, y: number): boolean {
    return this.areas.some((r) => inside(r, x, y));
  }

  private isDouble(key: string): boolean {
    const t = performance.now();
    const d = this.lastClick.key === key && t - this.lastClick.t < 400;
    this.lastClick = { t, key };
    return d;
  }

  private isHandFrom(kind: HandSource['kind'], at: number | EquipSlot): boolean {
    const s = this.hand?.src;
    if (!s || s.kind !== kind) return false;
    return s.kind === 'equip' ? s.slot === at : s.index === at;
  }

  /**
   * 点物品格。电脑（原版）：点一下拿起、再点放下，双击使用。
   * 手机：没拿着东西时点一下弹出说明和操作菜单；选了「移动」拿在手上后，再点格子放下（同电脑）。
   */
  private clickSlots(x: number, y: number): boolean {
    const p = this.p;
    const menu = this.layout.itemMenu && !this.hand;
    if (this.bagOpen) {
      for (let i = 0; i < p.bag.length; i++) {
        const cell = this.gridCell(i);
        if (!inside(cell, x, y)) continue;
        const it = p.bag[i];
        if (menu) {
          if (it) this.openBagMenu(i, it, cell);
          return true;
        }
        if (it && this.isDouble(`bag${i}`) && (!this.hand || this.hand.item === it)) {
          this.hand = null;
          this.useOrEquip(it);
          return true;
        }
        this.placeIntoBag(i);
        return true;
      }
    }
    if (this.stateOpen && this.statePage === 0) {
      for (const [slot, r] of EQUIP_RECTS) {
        const er = this.equipRect(r);
        if (!inside(er, x, y)) continue;
        if (menu) {
          const it = p.equip[slot];
          if (it) this.openEquipMenu(slot, it, er);
          return true;
        }
        if (this.hand) {
          if (this.hand.src.kind === 'equip') {
            this.hand = null;
            return true;
          }
          const why = this.world.equip(p, this.hand.item.uid, slot);
          if (why) this.message(why, 'bad');
          this.hand = null;
        } else if (p.equip[slot]) {
          this.hand = { src: { kind: 'equip', slot }, item: p.equip[slot]! };
        }
        return true;
      }
    }
    const belt = this.layout.beltRects();
    for (let i = 0; i < 6; i++) {
      if (!inside(belt[i]!, x, y)) continue;
      const it = p.belt[i];
      if (this.hand) this.placeIntoBelt(i);
      // 手机上开着包裹时点快捷栏：弹菜单（可以放回包裹）；平时点一下就喝
      else if (it && menu && this.bagOpen) this.openBeltMenu(i, it, belt[i]!);
      else if (it) this.useOrEquip(it);
      return true;
    }
    return false;
  }

  // ---------------- 手机：物品菜单 ----------------

  /** 物品说明（装备和身上同部位的比较） */
  private describe(it: ItemInstance): Line[] {
    const def = this.catalog.item(it.name);
    const k = itemKind(def);
    const cur = k.kind === 'equip' ? this.p.equip[k.slots[0]!] : null;
    return itemLines(def, it, this.p, cur && cur !== it ? this.catalog.item(cur.name) : null);
  }

  private openBagMenu(i: number, it: ItemInstance, anchor: Rect): void {
    const p = this.p;
    const def = this.catalog.item(it.name);
    const kind = itemKind(def).kind;
    const actions: MenuAction[] = [];
    const src: HandSource = { kind: 'bag', index: i };
    if (this.npc && this.mode === 'sell') actions.push({ label: `卖出 ${Math.floor(def.price / 2)} 金币`, run: () => this.sellOrStore(src, it) });
    if (this.npc && this.mode === 'store') actions.push({ label: '保管', run: () => this.sellOrStore(src, it) });
    const use = USE_LABEL[kind];
    if (use) actions.push({ label: use, run: () => this.useOrEquip(it) });
    if ((kind === 'potion' || kind === 'scroll') && p.belt.includes(null)) {
      actions.push({
        label: '放快捷栏',
        run: () => {
          const slot = p.belt.indexOf(null);
          if (slot < 0 || p.bag[i] !== it) return;
          p.bag[i] = null;
          p.belt[slot] = it;
        },
      });
    }
    actions.push({ label: '移动', run: () => (this.hand = { src, item: it }) });
    actions.push({ label: '丢弃', run: () => this.dropFrom(src, it) });
    this.itemMenu = { lines: this.describe(it), actions, anchor };
  }

  private openEquipMenu(slot: EquipSlot, it: ItemInstance, anchor: Rect): void {
    this.itemMenu = {
      lines: this.describe(it),
      anchor,
      actions: [{ label: '脱下', run: () => this.unequip(slot, it) }],
    };
  }

  private openBeltMenu(i: number, it: ItemInstance, anchor: Rect): void {
    const p = this.p;
    this.itemMenu = {
      lines: this.describe(it),
      anchor,
      actions: [
        { label: '使用', run: () => this.useOrEquip(it) },
        {
          label: '放回包裹',
          run: () => {
            const slot = p.bag.indexOf(null);
            if (slot < 0) return this.message('包裹满了', 'bad');
            if (p.belt[i] !== it) return;
            p.belt[i] = null;
            p.bag[slot] = it;
          },
        },
      ],
    };
  }

  private openShopMenu(row: { onBuy: () => void; item: ItemInstance | null; defName: string; price: string }, anchor: Rect): void {
    const def = this.catalog.item(row.defName);
    const getback = this.mode === 'getback';
    this.itemMenu = {
      lines: itemLines(def, row.item, this.p, null),
      anchor,
      keepOpen: !getback,
      actions: [{ label: getback ? '取回' : `购买 ${row.price}`, run: () => row.onBuy() }],
    };
  }

  private unequip(slot: EquipSlot, it: ItemInstance): void {
    const p = this.p;
    if (p.equip[slot] !== it) return;
    if (!p.bag.includes(null)) return this.message('包裹满了', 'bad');
    p.equip[slot] = null;
    this.world.recalc(p);
    p.bag[p.bag.indexOf(null)] = it;
  }

  private dropFrom(src: HandSource, it: ItemInstance): void {
    this.hand = { src, item: it };
    const taken = this.takeHand();
    if (taken) this.world.dropAt(this.p.pos, taken);
  }

  private sellOrStore(src: HandSource, it: ItemInstance): void {
    this.sellSpot = { src, item: it };
    this.confirmSell();
  }

  /** 物品菜单这一帧画在哪（点在里面不关菜单） */
  private menuRect: Rect | null = null;

  private drawItemMenu(m: ItemMenu): void {
    const g = this.g;
    const LH = 15;
    const BTN_H = 32;
    const pad = 6;
    const btnW = m.actions.map((a) => Math.max(56, g.width(a.label) + 20));
    const textW = Math.max(...m.lines.map((l) => g.width(l.text)));
    const btnRowW = btnW.reduce((s, w) => s + w + 6, -6);
    const w = Math.min(this.W - 8, Math.max(textW, btnRowW) + pad * 2);
    // 按钮一行放不下就折行
    const rows: { a: MenuAction; w: number }[][] = [[]];
    let rowW = 0;
    m.actions.forEach((a, i) => {
      const bw = btnW[i]!;
      if (rowW > 0 && rowW + 6 + bw > w - pad * 2) {
        rows.push([]);
        rowW = 0;
      }
      rows[rows.length - 1]!.push({ a, w: bw });
      rowW += (rowW > 0 ? 6 : 0) + bw;
    });
    const h = pad + m.lines.length * LH + 4 + rows.length * (BTN_H + 6) + pad - 6;
    // 优先摆在格子右边，放不下就左边；纵向尽量和格子顶对齐
    const a = m.anchor;
    let x = a.x + a.w + 4;
    if (x + w > this.W - this.safe.right) x = a.x - w - 4;
    x = Math.max(this.safe.left + 2, Math.min(x, this.W - this.safe.right - w - 2));
    const y = Math.max(this.safe.top + 2, Math.min(a.y, this.H - this.safe.bottom - h - 2));
    this.menuRect = { x, y, w, h };
    this.area(this.menuRect);
    g.frame(x, y, w, h, 'rgba(10,7,3,.94)', '#8a6a3a');
    const color: Record<string, string> = { title: '#ff0', good: '#7cff7c', bad: '#f55', dim: '#aaa', gold: '#ffd96b' };
    m.lines.forEach((l, i) => g.text(l.text, x + pad, y + pad + i * LH, color[l.tone ?? ''] ?? '#fff'));
    let by = y + pad + m.lines.length * LH + 4;
    for (const row of rows) {
      let bx = x + pad;
      for (const { a: act, w: bw } of row) {
        const r = { x: bx, y: by, w: bw, h: BTN_H };
        g.frame(r.x, r.y, r.w, r.h, this.isPressed(r) ? '#7a5a2a' : '#3a2a16', '#c9a060');
        g.text(act.label, r.x + (bw - g.width(act.label)) / 2, r.y + 10, '#ffe08a', true);
        this.hit({
          ...r, img: -1,
          onClick: () => {
            act.run();
            if (!m.keepOpen) this.itemMenu = null;
          },
        });
        bx += bw + 6;
      }
      by += BTN_H + 6;
    }
  }

  private useOrEquip(it: ItemInstance): void {
    const why = this.world.useItem(this.p, it.uid);
    if (why) this.message(why, 'bad');
  }

  private useHand(): void {
    const h = this.hand;
    this.hand = null;
    if (h && h.src.kind !== 'equip') this.useOrEquip(h.item);
  }

  /** 把手上的物品从原处拿走并返回。 */
  private takeHand(): ItemInstance | null {
    const h = this.hand;
    this.hand = null;
    if (!h) return null;
    const p = this.p;
    switch (h.src.kind) {
      case 'bag':
        if (p.bag[h.src.index] !== h.item) return null;
        p.bag[h.src.index] = null;
        return h.item;
      case 'belt':
        if (p.belt[h.src.index] !== h.item) return null;
        p.belt[h.src.index] = null;
        return h.item;
      case 'equip':
        if (p.equip[h.src.slot] !== h.item) return null;
        p.equip[h.src.slot] = null;
        this.world.recalc(p);
        return h.item;
    }
  }

  private placeIntoBag(i: number): void {
    const p = this.p;
    if (!this.hand) {
      if (p.bag[i]) this.hand = { src: { kind: 'bag', index: i }, item: p.bag[i]! };
      return;
    }
    const target = p.bag[i];
    const src = this.hand.src;
    if (src.kind === 'bag') {
      p.bag[src.index] = target ?? null;
      p.bag[i] = this.hand.item;
      this.hand = null;
      return;
    }
    if (target) {
      // 从快捷栏/身上放到有东西的格子：只在空格放下
      this.message('请放到空格子里', 'bad');
      return;
    }
    const it = this.takeHand();
    if (it) p.bag[i] = it;
  }

  private placeIntoBelt(i: number): void {
    const p = this.p;
    const h = this.hand!;
    const k = itemKind(this.catalog.item(h.item.name)).kind;
    if (k !== 'potion' && k !== 'scroll') {
      this.message('快捷栏只能放药品和卷轴', 'bad');
      return;
    }
    const target = p.belt[i];
    if (h.src.kind === 'bag') {
      p.bag[h.src.index] = target ?? null;
      p.belt[i] = h.item;
    } else if (h.src.kind === 'belt') {
      p.belt[h.src.index] = target ?? null;
      p.belt[i] = h.item;
    }
    this.hand = null;
  }

  // ---------------- 商人动作 ----------------

  private runLink(cmd: string): void {
    this.returnSellSpot();
    this.menuIndex = -1;
    this.menuTop = 0;
    switch (cmd) {
      case '@buy':
        this.mode = 'buy';
        break;
      case '@sell':
        this.mode = 'sell';
        if (this.layout.itemMenu) this.message('点包裹里的物品，选「卖出」');
        break;
      case '@store':
        this.mode = 'store';
        if (this.layout.itemMenu) this.message('点包裹里的物品，选「保管」');
        break;
      case '@getback':
        this.mode = 'getback';
        break;
      case '@exit':
        this.closeNpc();
        break;
    }
  }

  private closeNpc(): void {
    this.returnSellSpot();
    this.npc = null;
  }

  private buy(name: string): void {
    const def = this.catalog.item(name);
    const p = this.p;
    if (p.gold < def.price) return this.message('金币不够', 'bad');
    if (this.world.bagWeight(p) + def.weight > p.stats.maxBagWeight) return this.message('包裹太重了', 'bad');
    if (!this.world.addToBag(p, newItem(def))) return this.message('包裹满了', 'bad');
    p.gold -= def.price;
    this.message(`买了 ${def.name}，花费 ${def.price} 金币`, 'item');
  }

  private clickSellSpot(): void {
    if (this.hand) {
      if (this.hand.src.kind === 'equip') return this.message('先把装备脱下来放进包裹', 'bad');
      this.returnSellSpot();
      this.sellSpot = this.hand;
      this.hand = null;
    } else if (this.sellSpot) {
      this.hand = this.sellSpot;
      this.sellSpot = null;
    }
  }

  private returnSellSpot(): void {
    this.sellSpot = null; // 物品一直留在原处，清掉引用即可
  }

  private closeSell(): void {
    this.returnSellSpot();
    this.mode = 'talk';
  }

  private confirmSell(): void {
    const h = this.sellSpot;
    if (!h) return;
    this.sellSpot = null;
    this.hand = h;
    const it = this.takeHand();
    if (!it) return;
    const def = this.catalog.item(it.name);
    if (this.mode === 'store') {
      this.p.storage.push(it);
      this.message(`保管了 ${def.name}`, 'item');
    } else {
      const price = Math.floor(def.price / 2);
      this.p.gold += price;
      this.message(`卖出 ${def.name}，得到 ${price} 金币`, 'item');
    }
  }
}
