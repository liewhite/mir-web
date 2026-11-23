/**
 * 原版界面（照 ref/mir2delphi/client/FState.pas 的窗口、图号、坐标）：
 * 底栏 DBottom、状态窗 DStateWin（装备/属性/能力/技能 4 页）、包裹 DItemBag、商人对话 DMerchantDlg、
 * 商品列表 DMenuDlg、出售/保管 DSellDlg、技能快捷键 DKeySelDlg、设置 DConfigDlg（便利功能）。
 * 物品操作和原版一样：点一下拿起、再点放下；双击使用；拿着物品点地面就丢下。
 */
import type { Catalog } from '../../data/defs';
import type { Npc, Player } from '../../world/entities';
import { type EquipSlot, type ItemInstance, itemKind, newItem } from '../../world/items';
import { manaCost } from '../../world/combat';
import type { World } from '../../world/world';
import type { Controller } from '../../game/controller';
import { SETTING_LABELS } from '../../game/settings';
import { JOB_NAME } from '../../world/rules';
import { type Line, itemLines, statLines } from '../describe';
import { Gfx, MAIN, type Rect, goldStr, inside } from './gfx';

const BOARD_W = 800;
const BOARD_H = 251;
const LINE_H = 14; // 商品列表行高
const MAX_MENU = 10;
const CHAT_LINES = 9;

type ChatColor = 'system' | 'good' | 'bad' | 'item';
const CHAT_COLOR: Record<ChatColor, [string, string]> = {
  system: ['#fff', '#000'], good: ['#9cff8a', '#000'], bad: ['#fff', '#c00'], item: ['#ff0', '#000'],
};

/** 手上拿着的物品：从哪里拿起来的（放下前物品留在原处，不会丢）。 */
type HandSource = { kind: 'bag'; index: number } | { kind: 'belt'; index: number } | { kind: 'equip'; slot: EquipSlot };
interface Hand {
  src: HandSource;
  item: ItemInstance;
}

type MerchantMode = 'talk' | 'buy' | 'sell' | 'store' | 'getback';

interface Button extends Rect {
  img: number;
  /** 平时也画（不是底图的一部分） */
  always?: boolean;
  onClick: () => void;
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

export class ClassicUi {
  private chat: { text: string; color: ChatColor }[] = [];
  private hand: Hand | null = null;
  private mouse = { x: -1, y: -1 };
  private pressed: Button | null = null;
  private lastClick = { t: 0, key: '' };

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

  /** 每帧算出的界面占用区域（点在里面就不传给地图） */
  private areas: Rect[] = [];
  private buttons: Button[] = [];
  W = 800;
  H = 600;

  constructor(
    private readonly g: Gfx,
    private readonly world: World,
    private readonly ctl: Controller,
    private readonly catalog: Catalog,
  ) {}

  get p(): Player {
    return this.world.player;
  }

  message(text: string, color: ChatColor = 'system'): void {
    this.chat.push({ text, color });
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
    this.cancelHand();
  }

  toggleBag(): void {
    this.bagOpen = !this.bagOpen;
  }

  toggleState(page: number): void {
    if (this.stateOpen && this.statePage === page) this.stateOpen = false;
    else {
      this.stateOpen = true;
      this.statePage = page;
    }
  }

  cancelHand(): void {
    this.hand = null;
  }

  // ======================= 绘制 =======================

  draw(W: number, H: number, now: number): void {
    this.W = W;
    this.H = H;
    this.areas = [];
    this.buttons = [];
    this.drawBottom(now);
    if (this.npc) this.drawMerchant();
    if (this.bagOpen) this.drawBag();
    if (this.stateOpen) this.drawState();
    if (this.settingsOpen) this.drawSettings();
    if (this.keyDlg) this.drawKeyDlg();
    this.drawHover();
    if (this.hand) {
      const def = this.catalog.item(this.hand.item.name);
      this.g.imgCenter('items', def.look, this.mouse.x - 20, this.mouse.y - 16, 40, 32);
    }
  }

  private button(b: Button): void {
    this.buttons.push(b);
    if (b.always || this.pressed === b || (this.pressed && this.pressed.img === b.img && this.pressed.x === b.x && this.pressed.y === b.y)) {
      this.g.img(MAIN, b.always && this.isPressed(b) ? b.img + 1 : b.img, b.x, b.y);
    }
  }

  private isPressed(b: Button): boolean {
    return !!this.pressed && this.pressed.img === b.img && this.pressed.x === b.x && this.pressed.y === b.y;
  }

  // ---------------- 底栏 ----------------

  private boardX(): number {
    return Math.round((this.W - BOARD_W) / 2);
  }

  private drawBottom(now: number): void {
    const g = this.g;
    const bx = this.boardX();
    const by = this.H - BOARD_H;
    this.areas.push({ x: bx, y: by + 40, w: BOARD_W, h: BOARD_H - 40 }, { x: bx, y: by, w: 190, h: 40 }, { x: bx + 610, y: by, w: 190, h: 40 });
    // 下半部分不透明（原版 Draw(..., FALSE)），上半部分透明叠在地图上
    g.rect(bx + 208, by + 120, 387, 124, '#000');
    g.img(MAIN, 1, bx, by);
    g.img(MAIN, 12, bx + 748, by + 79); // 白天
    const p = this.p;
    const s = p.stats;
    if (p.job === 0 && p.level < 28) {
      const full = g.size(MAIN, 5);
      if (full) {
        g.imgPart(MAIN, 5, bx + 38, by + 90, 0, 0, full.w - 2, full.h);
        const top = Math.round((full.h / s.maxHp) * (s.maxHp - p.hp));
        g.imgPart(MAIN, 6, bx + 38, by + 90 + top, 0, top, full.w - 2, full.h - top);
      }
    } else {
      const d = g.size(MAIN, 4);
      if (d) {
        const half = Math.floor(d.w / 2);
        const top = Math.round((d.h / s.maxHp) * (s.maxHp - p.hp));
        g.imgPart(MAIN, 4, bx + 40, by + 91 + top, 0, top, half - 1, d.h - top);
        const mtop = Math.round((d.h / Math.max(1, s.maxMp)) * (s.maxMp - p.mp));
        g.imgPart(MAIN, 4, bx + 40 + half + 1, by + 91 + mtop, half + 1, mtop, d.w - half - 2, d.h - mtop);
      }
    }
    // 血蓝数值（原版没有，为了看得清楚）
    const hpText = `${p.hp}/${s.maxHp}`;
    const mpText = `${p.mp}/${s.maxMp}`;
    g.text(hpText, bx + 86 - g.width(hpText) / 2, by + 186, '#fff', true);
    g.text(mpText, bx + 86 - g.width(mpText) / 2, by + 200, '#9fc6ff', true);
    g.text(String(p.level), bx + 660, this.H - 104, '#fff', true);
    const bar = g.size(MAIN, 7);
    if (bar) {
      const need = this.catalog.expToNext(p.level);
      const er = need === Infinity ? 1 : p.exp / need;
      g.imgPart(MAIN, 7, bx + 666, this.H - 73, 0, 0, Math.round(bar.w * Math.min(1, er)), bar.h);
      const wr = this.world.bagWeight(p) / Math.max(1, s.maxBagWeight);
      g.imgPart(MAIN, 7, bx + 666, this.H - 40, 0, 0, Math.round(bar.w * Math.min(1, wr)), bar.h);
    }
    // 聊天/系统消息
    const lines = this.chat.slice(-CHAT_LINES);
    lines.forEach((l, i) => {
      const [fg, bg] = CHAT_COLOR[l.color];
      const w = g.width(l.text);
      g.rect(bx + 208, this.H - 130 + i * 12, w, 12, bg);
      g.text(l.text, bx + 208, this.H - 130 + i * 12, fg);
    });
    // 功能按钮：状态、包裹、技能、设置
    const by0 = by;
    this.button({ img: 8, x: bx + 643, y: by0 + 61, w: 24, h: 24, onClick: () => this.toggleState(0) });
    this.button({ img: 9, x: bx + 682, y: by0 + 41, w: 24, h: 22, onClick: () => this.toggleBag() });
    this.button({ img: 10, x: bx + 722, y: by0 + 21, w: 24, h: 23, onClick: () => this.toggleState(3) });
    this.button({ img: 11, x: bx + 764, y: by0 + 11, w: 24, h: 23, onClick: () => (this.settingsOpen = !this.settingsOpen) });
    // 快捷栏
    for (let i = 0; i < 6; i++) {
      const r = { x: bx + 285 + 43 * i, y: by + 59, w: 32, h: 29 };
      const it = p.belt[i];
      if (it && !(this.hand?.src.kind === 'belt' && this.hand.src.index === i)) {
        g.imgCenter('items', this.catalog.item(it.name).look, r.x, r.y, r.w, r.h);
      }
      g.text(String(i + 1), r.x + 13, r.y + 19, '#fff', true);
    }
    // 技能快捷键提示（原版没有：F1-F8 对应的技能名，画在底栏左上方）
    void now;
  }

  // ---------------- 状态窗 ----------------

  private stateX(): number {
    return this.W - 232;
  }

  private drawState(): void {
    const g = this.g;
    const x = this.stateX();
    const y = 0;
    const p = this.p;
    this.areas.push({ x, y, w: 232, h: 325 });
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
      this.buttons.push({ ...iconR, img: -1, onClick: () => (this.keyDlg = { skill: ls.id }) });
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

  private bagX(): number {
    return this.npc ? 475 : 0;
  }

  private gridCell(i: number): Rect {
    const x = this.bagX();
    return { x: x + 20 + (i % 8) * 36, y: 13 + Math.floor(i / 8) * 32, w: 36, h: 32 };
  }

  private drawBag(): void {
    const g = this.g;
    const x = this.bagX();
    const y = 0;
    const p = this.p;
    this.areas.push({ x, y, w: 336, h: 270 });
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
    this.areas.push({ x: 0, y: 0, w: 416, h: 176 });
    g.img(MAIN, 384, 0, 0);
    this.links = [];
    let ly = 20;
    for (const raw of this.npcText().split('\\')) {
      let sx = 30;
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
    this.button({ img: 64, x: 399, y: 1, w: 16, h: 23, onClick: () => this.closeNpc() });
    if (this.mode === 'buy' || this.mode === 'getback') this.drawMenu();
    if (this.mode === 'sell' || this.mode === 'store') this.drawSell();
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

  private drawMenu(): void {
    const g = this.g;
    const x = 0;
    const y = 176;
    this.areas.push({ x, y, w: 308, h: 205 });
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
      this.buttons.push({
        x: x + 14, y: y + 32 + m * LINE_H, w: 265, h: LINE_H, img: -1,
        onClick: () => {
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

  private drawSell(): void {
    const g = this.g;
    const x = 260;
    const y = 176;
    this.areas.push({ x, y, w: 140, h: 181 });
    g.img(MAIN, 392, x, y);
    const store = this.mode === 'store';
    g.text(store ? '放入要保管的物品' : '放入要出售的物品', x + 12, y + 30, '#fff');
    const spot = { x: x + 27, y: y + 67, w: 61, h: 52 };
    this.buttons.push({ ...spot, img: -1, onClick: () => this.clickSellSpot() });
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
    const x = Math.round((this.W - sz.w) / 2);
    const y = Math.max(0, Math.round((this.H - BOARD_H - sz.h) / 2));
    this.areas.push({ x, y, w: sz.w, h: sz.h });
    g.img(MAIN, 204, x, y);
    g.rect(x + 50, y + 6, 70, 20, '#2a2a2a');
    g.text('便利设置', x + 56, y + 10, '#ffe08a', true);
    const s = this.ctl.settings as unknown as Record<string, number | boolean>;
    SETTING_LABELS.forEach((item, i) => {
      const ly = y + 44 + i * 22;
      const v = s[item.key];
      if (item.kind === 'bool') {
        const box = { x: x + 30, y: ly, w: 14, h: 14 };
        g.rect(box.x, box.y, box.w, box.h, '#000');
        g.rect(box.x + 1, box.y + 1, box.w - 2, box.h - 2, '#4a3a20');
        if (v) g.text('✓', box.x + 2, box.y + 1, '#ff0', true);
        g.text(item.label, x + 52, ly + 1, '#fff');
        this.buttons.push({ x: box.x, y: ly - 2, w: 400, h: 18, img: -1, onClick: () => { s[item.key] = !v; this.ctl.onSettingsChanged(); } });
      } else {
        g.text(item.label, x + 52, ly + 1, '#fff');
        const vx = x + 360;
        g.text('◀', vx, ly + 1, '#ff0', true);
        g.text(`${v}%`, vx + 22, ly + 1, '#fff', true);
        g.text('▶', vx + 62, ly + 1, '#ff0', true);
        this.buttons.push({ x: vx - 4, y: ly - 2, w: 20, h: 18, img: -1, onClick: () => { s[item.key] = Math.max(0, Number(v) - 5); this.ctl.onSettingsChanged(); } });
        this.buttons.push({ x: vx + 58, y: ly - 2, w: 20, h: 18, img: -1, onClick: () => { s[item.key] = Math.min(95, Number(v) + 5); this.ctl.onSettingsChanged(); } });
      }
    });
    g.text('F1-F8 技能  1-6 快捷栏  空格 捡取  Esc 关闭  右键跑步', x + 30, y + sz.h - 40, '#c0c0c0');
    this.button({ img: 361, x: x + 514, y: y + 287, w: 80, h: 34, onClick: () => (this.settingsOpen = false) });
    this.button({ img: 64, x: x + 584, y: y + 6, w: 16, h: 23, onClick: () => (this.settingsOpen = false) });
  }

  // ---------------- 技能快捷键 ----------------

  private drawKeyDlg(): void {
    const g = this.g;
    const sz = g.size(MAIN, 229) ?? { w: 376, h: 179 };
    const x = Math.round((this.W - sz.w) / 2);
    const y = Math.round((this.H - BOARD_H - sz.h) / 2);
    this.areas.push({ x, y, w: sz.w, h: sz.h });
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
      this.buttons.push({ ...r, img: -1, onClick: () => this.setKey(id, k) });
    });
    this.buttons.push({ x: x + 299, y: y + 83, w: 40, h: 30, img: -1, onClick: () => this.setKey(id, 0) });
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
      const sx = this.stateX();
      for (const [slot, r] of EQUIP_RECTS) {
        if (p.equip[slot] && inside({ ...r, x: sx + r.x }, x, y)) return p.equip[slot];
      }
    }
    const bx = this.boardX();
    const by = this.H - BOARD_H;
    for (let i = 0; i < 6; i++) {
      if (p.belt[i] && inside({ x: bx + 285 + 43 * i, y: by + 59, w: 32, h: 29 }, x, y)) return p.belt[i]!;
    }
    return null;
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
        if (inside({ x: 14, y: 176 + 32 + (i - this.menuTop) * LINE_H, w: 265, h: LINE_H }, this.mouse.x, this.mouse.y)) {
          const r = rows[i]!;
          const def = this.catalog.item(r.defName);
          lines = itemLines(def, r.item, this.p, null);
        }
      }
    } else if (this.stateOpen && this.statePage === 1) {
      const nearby = [...this.world.actors.values()].filter((a) => a.kind === 'monster' && !a.master && !a.dead) as { agility: number; hit: number }[];
      const avgA = nearby.length ? Math.round(nearby.reduce((s, m) => s + m.agility, 0) / nearby.length) : 15;
      const avgH = nearby.length ? Math.round(nearby.reduce((s, m) => s + m.hit, 0) / nearby.length) : 8;
      if (inside({ x: this.stateX() + 40, y: 90, w: 180, h: 150 }, this.mouse.x, this.mouse.y)) {
        lines = statLines(this.p.stats, this.p, avgA, avgH);
      }
    } else if (this.stateOpen && this.statePage === 3) {
      const top = this.magicPage * 5;
      for (let i = top; i < Math.min(top + 5, this.p.skills.length); i++) {
        const m = i - top;
        if (inside({ x: this.stateX() + 46, y: 60 + m * 37, w: 170, h: 33 }, this.mouse.x, this.mouse.y)) {
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
    // 从上往下找按钮
    for (let i = this.buttons.length - 1; i >= 0; i--) {
      const b = this.buttons[i]!;
      if (inside(b, x, y)) {
        this.pressed = b;
        return true;
      }
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

  pointerUp(x: number, y: number): void {
    const b = this.pressed;
    this.pressed = null;
    if (b && inside(b, x, y)) b.onClick();
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

  private clickSlots(x: number, y: number): boolean {
    const p = this.p;
    if (this.bagOpen) {
      for (let i = 0; i < p.bag.length; i++) {
        if (!inside(this.gridCell(i), x, y)) continue;
        const it = p.bag[i];
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
      const sx = this.stateX();
      for (const [slot, r] of EQUIP_RECTS) {
        if (!inside({ ...r, x: sx + r.x }, x, y)) continue;
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
    const bx = this.boardX();
    const by = this.H - BOARD_H;
    for (let i = 0; i < 6; i++) {
      if (!inside({ x: bx + 285 + 43 * i, y: by + 59, w: 32, h: 29 }, x, y)) continue;
      if (this.hand) this.placeIntoBelt(i);
      else if (p.belt[i]) {
        const why = this.world.useItem(p, p.belt[i]!.uid);
        if (why) this.message(why, 'bad');
      }
      return true;
    }
    return false;
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
        break;
      case '@store':
        this.mode = 'store';
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
