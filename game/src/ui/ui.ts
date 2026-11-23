/** DOM 界面：底栏（血蓝经验、药品栏、技能栏）、包裹/角色、技能、设置、NPC 商店仓库、提示框、消息。 */
import type { Catalog, ItemDef } from '../data/defs';
import type { Npc, Player } from '../world/entities';
import { EQUIP_SLOTS, type EquipSlot, type ItemInstance, SLOT_NAME, itemKind, newItem } from '../world/items';
import { manaCost } from '../world/combat';
import type { World } from '../world/world';
import type { Controller } from '../game/controller';
import { SETTING_LABELS, type Settings } from '../game/settings';
import { type Line, itemLines, statLines } from './describe';
import type { IconFactory } from './icons';

const h = <K extends keyof HTMLElementTagNameMap>(tag: K, cls = '', text = ''): HTMLElementTagNameMap[K] => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text) e.textContent = text;
  return e;
};

type PanelName = 'bag' | 'skills' | 'settings' | 'npc' | null;

export class Ui {
  private readonly root: HTMLElement;
  private readonly hud = h('div', 'hud');
  private readonly bars = h('div', 'bars');
  private readonly belt = h('div', 'belt');
  private readonly skillBar = h('div', 'skillbar');
  private readonly log = h('div', 'log');
  private readonly panel = h('div', 'panel hidden');
  private readonly tip = h('div', 'tip hidden');
  private readonly deathBox = h('div', 'death hidden');
  private open: PanelName = null;
  private npc: Npc | null = null;
  private lastBeltSig = '';
  private lastSkillSig = '';
  private dirty = true;
  onSettingsChanged: () => void = () => {};

  constructor(
    root: HTMLElement,
    private readonly world: World,
    private readonly ctl: Controller,
    private readonly catalog: Catalog,
    private readonly icons: IconFactory,
  ) {
    this.root = root;
    const buttons = h('div', 'buttons');
    const btn = (label: string, fn: () => void): void => {
      const b = h('button', '', label);
      b.onclick = fn;
      buttons.append(b);
    };
    btn('包裹', () => this.toggle('bag'));
    btn('技能', () => this.toggle('skills'));
    btn('设置', () => this.toggle('settings'));
    btn('捡取', () => this.world.pickUp(this.world.player));
    this.hud.append(this.bars, this.belt, this.skillBar, buttons);
    const revive = h('button', '', '回城复活');
    revive.onclick = () => this.world.revive();
    this.deathBox.append(h('div', '', '你死了'), revive);
    root.append(this.log, this.hud, this.panel, this.tip, this.deathBox);
    this.panel.addEventListener('pointerdown', (e) => e.stopPropagation());
    this.hud.addEventListener('pointerdown', (e) => e.stopPropagation());
  }

  get player(): Player {
    return this.world.player;
  }

  message(text: string, color: 'system' | 'good' | 'bad' | 'item' = 'system'): void {
    const m = h('div', `msg ${color}`, text);
    this.log.append(m);
    while (this.log.children.length > 8) this.log.firstChild?.remove();
    setTimeout(() => m.classList.add('fade'), 6000);
  }

  toggle(p: Exclude<PanelName, null>): void {
    this.open = this.open === p ? null : p;
    if (this.open !== 'npc') this.npc = null;
    this.dirty = true;
  }

  openNpc(npc: Npc): void {
    this.npc = npc;
    this.open = 'npc';
    this.dirty = true;
  }

  closePanels(): void {
    this.open = null;
    this.npc = null;
    this.hideTip();
    this.dirty = true;
  }

  /** 物品、金币变化时由外部调用，下一帧重画面板。 */
  invalidate(): void {
    this.dirty = true;
  }

  // ---------------- 每帧 ----------------

  update(): void {
    const p = this.player;
    const s = p.stats;
    const need = this.catalog.expToNext(p.level);
    this.bars.innerHTML = '';
    const bar = (cls: string, cur: number, max: number, label: string): void => {
      const b = h('div', `bar ${cls}`);
      const fill = h('div', 'fill');
      fill.style.width = `${Math.max(0, Math.min(100, (cur / max) * 100))}%`;
      b.append(fill, h('span', '', label));
      this.bars.append(b);
    };
    bar('hp', p.hp, s.maxHp, `体力 ${p.hp}/${s.maxHp}`);
    bar('mp', p.mp, s.maxMp, `魔法 ${p.mp}/${s.maxMp}`);
    bar('exp', p.exp, need, `${p.level} 级　经验 ${need === Infinity ? '满级' : `${((p.exp / need) * 100).toFixed(1)}%`}`);

    const beltSig = p.belt.map((x) => x?.uid ?? 0).join(',');
    if (beltSig !== this.lastBeltSig) {
      this.lastBeltSig = beltSig;
      this.belt.innerHTML = '';
      p.belt.forEach((it, i) => {
        const slot = h('div', 'slot');
        slot.append(h('span', 'key', String(i + 1)));
        if (it) {
          slot.append(this.itemIcon(it));
          slot.onclick = () => this.use(it);
          this.hoverTip(slot, () => this.itemTip(it));
        }
        this.belt.append(slot);
      });
    }
    const skillSig = p.skills.map((x) => `${x.id}:${x.key}:${x.level}`).join(',');
    if (skillSig !== this.lastSkillSig) {
      this.lastSkillSig = skillSig;
      this.skillBar.innerHTML = '';
      for (let k = 1; k <= 8; k++) {
        const ls = p.skills.find((x) => x.key === k);
        const slot = h('div', 'slot');
        slot.append(h('span', 'key', `F${k}`));
        if (ls) {
          slot.append(this.icons.icon('magicon', ls.id * 2, 30));
          slot.onclick = () => this.ctl.castByKey(k, undefined, this.player.pos);
          const def = this.catalog.skillsById.get(ls.id)!;
          this.hoverTip(slot, () => [{ text: `${def.name}　${ls.level} 级`, tone: 'title' }, { text: `耗蓝 ${manaCost(def, ls.level)}` }]);
        }
        this.skillBar.append(slot);
      }
    }
    this.deathBox.classList.toggle('hidden', !p.dead);
    if (this.dirty) {
      this.dirty = false;
      this.renderPanel();
    }
  }

  private itemIcon(it: ItemInstance): HTMLCanvasElement {
    return this.icons.icon('items', this.catalog.item(it.name).look);
  }

  private use(it: ItemInstance): void {
    const why = this.world.useItem(this.player, it.uid);
    if (why) this.message(why, 'bad');
    this.dirty = true;
    this.lastBeltSig = '';
  }

  // ---------------- 提示框 ----------------

  private hoverTip(el: HTMLElement, lines: () => Line[]): void {
    el.addEventListener('pointerenter', (e) => {
      if (e.pointerType === 'mouse') this.showTip(lines(), el);
    });
    el.addEventListener('pointerleave', () => this.hideTip());
  }

  private showTip(lines: Line[], anchor: HTMLElement, extra: HTMLElement[] = []): void {
    this.tip.innerHTML = '';
    for (const l of lines) this.tip.append(h('div', l.tone ?? '', l.text));
    this.tip.append(...extra);
    this.tip.classList.remove('hidden');
    const r = anchor.getBoundingClientRect();
    const root = this.root.getBoundingClientRect();
    const tw = this.tip.offsetWidth;
    const th = this.tip.offsetHeight;
    let x = r.right + 6 - root.left;
    if (x + tw > root.width) x = r.left - tw - 6 - root.left;
    let y = r.top - root.top;
    if (y + th > root.height) y = root.height - th - 4;
    this.tip.style.left = `${Math.max(4, x)}px`;
    this.tip.style.top = `${Math.max(4, y)}px`;
  }

  hideTip(): void {
    this.tip.classList.add('hidden');
  }

  private itemTip(it: ItemInstance): Line[] {
    const def = this.catalog.item(it.name);
    const k = itemKind(def);
    let cmp: ItemDef | null = null;
    if (k.kind === 'equip') {
      const cur = this.player.equip[k.slots[0]!];
      cmp = cur ? this.catalog.item(cur.name) : null;
    }
    return itemLines(def, it, this.player, cmp);
  }

  // ---------------- 面板 ----------------

  private renderPanel(): void {
    this.panel.innerHTML = '';
    this.panel.classList.toggle('hidden', !this.open);
    if (!this.open) return;
    const head = h('div', 'head');
    const close = h('button', 'close', '×');
    close.onclick = () => this.closePanels();
    head.append(h('span', '', { bag: '包裹与装备', skills: '技能', settings: '便利设置', npc: this.npc?.def.name ?? '' }[this.open]), close);
    this.panel.append(head);
    switch (this.open) {
      case 'bag':
        this.renderBag();
        break;
      case 'skills':
        this.renderSkills();
        break;
      case 'settings':
        this.renderSettings();
        break;
      case 'npc':
        this.renderNpc();
        break;
    }
  }

  private renderBag(): void {
    const p = this.player;
    const w = this.world;
    const equip = h('div', 'equip');
    for (const slot of EQUIP_SLOTS) {
      const it = p.equip[slot];
      const cell = h('div', 'slot eq');
      cell.append(h('span', 'label', SLOT_NAME[slot]));
      if (it) {
        cell.append(this.itemIcon(it));
        cell.onclick = () => this.menu(cell, [[`脱下 ${it.name}`, () => this.unequip(slot)]]);
        this.hoverTip(cell, () => itemLines(this.catalog.item(it.name), it, p, null));
      }
      equip.append(cell);
    }
    const info = h('div', 'stats');
    const nearby = [...w.actors.values()].filter((a) => a.kind === 'monster' && !a.master && !a.dead) as { agility: number; hit: number }[];
    const avgA = nearby.length ? Math.round(nearby.reduce((s, m) => s + m.agility, 0) / nearby.length) : 15;
    const avgH = nearby.length ? Math.round(nearby.reduce((s, m) => s + m.hit, 0) / nearby.length) : 8;
    for (const l of statLines(p.stats, p, avgA, avgH)) info.append(h('div', l.tone ?? '', l.text));
    const grid = h('div', 'grid');
    p.bag.forEach((it) => {
      const cell = h('div', 'slot');
      if (it) {
        cell.append(this.itemIcon(it));
        cell.onclick = () => this.bagMenu(cell, it);
        cell.ondblclick = () => this.use(it);
        this.hoverTip(cell, () => this.itemTip(it));
      }
      grid.append(cell);
    });
    const foot = h('div', 'foot', `金币 ${p.gold}　包裹重量 ${w.bagWeight(p)}/${p.stats.maxBagWeight}　穿戴 ${w.wearWeight(p)}/${p.stats.maxWearWeight}`);
    this.panel.append(h('div', 'cols', ''), foot);
    const cols = this.panel.querySelector('.cols')!;
    const left = h('div', 'col');
    left.append(equip, info);
    const right = h('div', 'col');
    right.append(grid);
    cols.append(left, right);
  }

  private bagMenu(anchor: HTMLElement, it: ItemInstance): void {
    const def = this.catalog.item(it.name);
    const k = itemKind(def);
    const items: [string, () => void][] = [];
    const useLabel = k.kind === 'equip' || k.kind === 'amulet' ? '穿上' : k.kind === 'book' ? '学习' : k.kind === 'bundle' ? '拆开' : '使用';
    if (k.kind !== 'material' && k.kind !== 'other') items.push([useLabel, () => this.use(it)]);
    if (this.npc?.def.service === 'shop') items.push([`卖出（${Math.floor(def.price / 2)} 金币）`, () => this.sell(it)]);
    if (this.npc?.def.service === 'storage') items.push(['存进仓库', () => this.store(it)]);
    items.push(['丢在地上', () => this.drop(it)]);
    this.menu(anchor, items, this.itemTip(it));
  }

  private menu(anchor: HTMLElement, items: [string, () => void][], lines: Line[] = []): void {
    const buttons = items.map(([label, fn]) => {
      const b = h('button', 'act', label);
      b.onclick = (e) => {
        e.stopPropagation();
        this.hideTip();
        fn();
      };
      return b;
    });
    this.showTip(lines, anchor, buttons);
  }

  private unequip(slot: EquipSlot): void {
    const why = this.world.unequip(this.player, slot);
    if (why) this.message(why, 'bad');
    this.dirty = true;
  }

  private drop(it: ItemInstance): void {
    if (this.world.removeOwned(this.player, it.uid)) this.world.dropAt(this.player.pos, it);
    this.dirty = true;
    this.lastBeltSig = '';
  }

  private sell(it: ItemInstance): void {
    const def = this.catalog.item(it.name);
    if (!this.world.removeOwned(this.player, it.uid)) return;
    this.player.gold += Math.floor(def.price / 2);
    this.message(`卖出 ${def.name}，得到 ${Math.floor(def.price / 2)} 金币`, 'item');
    this.dirty = true;
    this.lastBeltSig = '';
  }

  private store(it: ItemInstance): void {
    if (!this.world.removeOwned(this.player, it.uid)) return;
    this.player.storage.push(it);
    this.dirty = true;
    this.lastBeltSig = '';
  }

  private renderNpc(): void {
    const npc = this.npc;
    if (!npc) return;
    const p = this.player;
    if (npc.def.service === 'storage') {
      this.panel.append(h('div', 'hint', '点包裹里的物品可以存进来，点下面的物品取回。'));
      const grid = h('div', 'grid');
      p.storage.forEach((it, i) => {
        const cell = h('div', 'slot');
        cell.append(this.itemIcon(it));
        cell.onclick = () => {
          if (!this.world.addToBag(p, it)) return this.message('包裹满了', 'bad');
          p.storage.splice(i, 1);
          this.dirty = true;
        };
        this.hoverTip(cell, () => this.itemTip(it));
        grid.append(cell);
      });
      this.panel.append(grid);
    } else {
      this.panel.append(h('div', 'hint', `点商品购买；打开包裹点物品可以卖给我。你有 ${p.gold} 金币。`));
      const list = h('div', 'goods');
      for (const g of npc.def.goods) {
        const def = this.catalog.itemsByName.get(g.name);
        if (!def) continue;
        const row = h('div', 'good');
        row.append(this.icons.icon('items', def.look, 28), h('span', 'name', def.name), h('span', 'price', `${def.price} 金币`));
        row.onclick = () => this.buy(def);
        this.hoverTip(row, () => itemLines(def, null, p, null));
        list.append(row);
      }
      this.panel.append(list);
    }
    // 商店开着时也显示包裹，方便卖东西/存东西
    const grid = h('div', 'grid');
    p.bag.forEach((it) => {
      const cell = h('div', 'slot');
      if (it) {
        cell.append(this.itemIcon(it));
        cell.onclick = () => this.bagMenu(cell, it);
        this.hoverTip(cell, () => this.itemTip(it));
      }
      grid.append(cell);
    });
    this.panel.append(h('div', 'sub', '你的包裹'), grid);
  }

  private buy(def: ItemDef): void {
    const p = this.player;
    if (p.gold < def.price) return this.message('金币不够', 'bad');
    if (this.world.bagWeight(p) + def.weight > p.stats.maxBagWeight) return this.message('包裹太重了', 'bad');
    if (!this.world.addToBag(p, newItem(def))) return this.message('包裹满了', 'bad');
    p.gold -= def.price;
    this.message(`买了 ${def.name}`, 'item');
    this.dirty = true;
    this.lastBeltSig = '';
  }

  private renderSkills(): void {
    const p = this.player;
    if (!p.skills.length) this.panel.append(h('div', 'hint', '还没学会技能。技能书在书店买，双击学习。'));
    for (const ls of p.skills) {
      const def = this.catalog.skillsById.get(ls.id)!;
      const row = h('div', 'skill');
      row.append(this.icons.icon('magicon', ls.id * 2, 30));
      const need = ls.level < 3 ? def.train[ls.level] : 0;
      const desc = h('div', 'desc');
      desc.append(h('div', 'title', `${def.name}　${ls.level} 级`));
      desc.append(h('div', 'dim', ls.level < 3 ? `熟练度 ${ls.train}/${need}（${def.needLevel[ls.level]} 级后才能继续涨）` : '已满级'));
      row.append(desc);
      const passive = [3, 4, 7, 12].includes(ls.id);
      if (passive && ls.id !== 12) {
        row.append(h('span', 'dim', '被动'));
      } else {
        const sel = h('select');
        for (let k = 0; k <= 8; k++) {
          const o = h('option', '', k ? `F${k}` : '无');
          o.value = String(k);
          if (ls.key === k) o.selected = true;
          sel.append(o);
        }
        sel.onchange = () => {
          const k = Number(sel.value);
          for (const o of p.skills) if (o.key === k && k) o.key = 0;
          ls.key = k;
          this.dirty = true;
        };
        row.append(sel);
      }
      this.panel.append(row);
    }
  }

  private renderSettings(): void {
    const s = this.ctl.settings;
    for (const item of SETTING_LABELS) {
      const row = h('label', 'setting');
      const input = h('input');
      if (item.kind === 'bool') {
        input.type = 'checkbox';
        input.checked = s[item.key] as boolean;
        input.onchange = () => {
          (s as unknown as Record<string, unknown>)[item.key] = input.checked;
          this.onSettingsChanged();
        };
        row.append(input, h('span', '', item.label));
      } else {
        input.type = 'number';
        input.min = '0';
        input.max = '95';
        input.value = String(s[item.key]);
        input.onchange = () => {
          (s as unknown as Record<string, unknown>)[item.key] = Math.max(0, Math.min(95, Number(input.value) || 0));
          this.onSettingsChanged();
        };
        row.append(h('span', '', item.label), input);
      }
      this.panel.append(row);
    }
    this.panel.append(h('div', 'hint', '键盘：F1-F8 放技能，1-6 用药品栏，B 包裹，K 技能，空格捡东西，Esc 关闭窗口。鼠标：左键走路/攻击/对话，右键跑步。'));
  }
}

export type { Settings };
