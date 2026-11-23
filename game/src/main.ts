/** 入口：登录 → 读档或建角色 → 主循环。 */
import { loadCatalog, type Catalog } from './data/defs';
import { loadMap } from './assets/mapdata';
import { SpriteStore } from './assets/sprites';
import { Player } from './world/entities';
import { newItem } from './world/items';
import { World } from './world/world';
import type { Job } from './world/rules';
import { Renderer } from './render/renderer';
import { Controller } from './game/controller';
import { bindInput } from './game/input';
import { loadSettings } from './game/settings';
import { HttpSaveStore, type SaveData, type SaveStore, fromSave, toSave } from './save/save';
import { IconFactory } from './ui/icons';
import { Ui } from './ui/ui';

const START = { map: '0', x: 333, y: 272 };

const $ = <T extends HTMLElement>(sel: string): T => document.querySelector(sel) as T;

function status(text: string): void {
  $('#status').textContent = text;
}

async function ensureLogin(): Promise<void> {
  const me = await fetch('api/me', { credentials: 'same-origin', cache: 'no-store' });
  if (me.ok) return;
  const form = $<HTMLFormElement>('#login');
  form.classList.remove('hidden');
  status('');
  await new Promise<void>((resolve) => {
    form.onsubmit = async (e) => {
      e.preventDefault();
      const pw = ($('#password') as HTMLInputElement).value;
      const r = await fetch('api/login', {
        method: 'POST', credentials: 'same-origin', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ password: pw }),
      });
      if (r.ok) {
        form.classList.add('hidden');
        resolve();
      } else $('#login-error').textContent = r.status === 429 ? '试太多次了，过一会再试' : '密码不对';
    };
  });
}

async function createCharacter(): Promise<{ name: string; job: Job; sex: 0 | 1 }> {
  const form = $<HTMLFormElement>('#create');
  form.classList.remove('hidden');
  status('');
  return new Promise((resolve) => {
    form.onsubmit = (e) => {
      e.preventDefault();
      const fd = new FormData(form);
      const name = String(fd.get('name') || '').trim() || '无名';
      form.classList.add('hidden');
      resolve({ name, job: Number(fd.get('job')) as Job, sex: Number(fd.get('sex')) as 0 | 1 });
    };
  });
}

function newPlayer(catalog: Catalog, c: { name: string; job: Job; sex: 0 | 1 }): Player {
  const p = new Player(1, c.name, c.job, c.sex, START.map, START.x, START.y, 0);
  p.gold = 300;
  const give = (name: string, n = 1): void => {
    for (let i = 0; i < n; i++) {
      const d = catalog.itemsByName.get(name);
      if (d) p.bag[p.bag.indexOf(null)] = newItem(d);
    }
  };
  p.equip.weapon = newItem(catalog.item('木剑'));
  p.equip.dress = newItem(catalog.item(c.sex === 0 ? '布衣(男)' : '布衣(女)'));
  give('金创药(小量)', 4);
  give('魔法药(小量)', 2);
  return p;
}

async function main(): Promise<void> {
  status('连接服务器…');
  await ensureLogin();
  status('读取游戏数据…');
  const catalog = await loadCatalog();
  const store: SaveStore = new HttpSaveStore();
  let save: SaveData | null = null;
  try {
    save = await store.load();
  } catch (e) {
    status(`读档失败：${(e as Error).message}，刷新重试`);
    return;
  }
  const world = new World(catalog);
  const player = save ? fromSave(save, catalog, 1, 0) : newPlayer(catalog, await createCharacter());
  world.player = player;
  world.actors.set(player.id, player);
  world.recalc(player);
  if (!save) {
    player.hp = player.stats.maxHp;
    player.mp = player.stats.maxMp;
    // 新角色把药挪进快捷栏
    for (let i = 0; i < player.bag.length; i++) {
      const it = player.bag[i];
      const slot = player.belt.indexOf(null);
      if (it && slot >= 0 && catalog.item(it.name).mode === 0) {
        player.belt[slot] = it;
        player.bag[i] = null;
      }
    }
  }
  status('加载地图…');
  const map = await loadMap(player.map).catch(() => loadMap(START.map));
  world.enterMap(map, player.map === map.id ? player.pos : START);

  const settings = loadSettings(save?.settings);
  const sprites = new SpriteStore();
  const canvas = $<HTMLCanvasElement>('#game');
  const renderer = new Renderer(canvas, sprites, world, catalog);
  const ctl = new Controller(world, settings);
  const icons = new IconFactory(sprites);
  const ui = new Ui($('#ui'), world, ctl, catalog, icons);
  sprites.onLoaded = () => icons.refresh();
  ctl.onMessage = (t, c) => ui.message(t, c);
  ctl.onTalk = (id) => {
    const npc = world.actors.get(id);
    if (npc?.kind === 'npc') ui.openNpc(npc);
  };
  const applyView = (): void => {
    renderer.view = { showHpBars: settings.showHpBars, showItemNames: settings.showItemNames, showMonsterNames: settings.showMonsterNames };
  };
  applyView();
  ui.onSettingsChanged = () => {
    applyView();
    void persist();
  };
  const fit = (): void => renderer.resize(window.innerWidth, window.innerHeight);
  fit();
  window.addEventListener('resize', fit);
  bindInput(canvas, renderer, ctl, ui);
  (window as unknown as Record<string, unknown>).__mir = { world, renderer, ctl, sprites, ui };
  status('');
  $('#loading').classList.add('hidden');

  // 存档：每 30 秒、切到后台时
  let saving = false;
  const persist = async (): Promise<void> => {
    if (saving) return;
    saving = true;
    try {
      await store.save(toSave(player, settings as unknown as Record<string, unknown>));
    } catch (e) {
      ui.message(`存档失败：${(e as Error).message}`, 'bad');
    } finally {
      saving = false;
    }
  };
  setInterval(() => void persist(), 30_000);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') void persist();
  });

  let last = performance.now();
  const frame = (t: number): void => {
    const dt = Math.min(100, t - last);
    last = t;
    world.tick(dt);
    ctl.update();
    for (const ev of world.events) {
      switch (ev.type) {
        case 'damage':
          renderer.float(ev.target, `-${ev.amount}`, ev.target === player.id ? '#ff6b6b' : '#ffd166');
          ui.invalidate();
          break;
        case 'miss':
          renderer.float(ev.target, 'MISS', '#bbb');
          break;
        case 'heal':
          if (ev.amount > 0) renderer.float(ev.target, `+${ev.amount}`, '#7bed9f');
          break;
        case 'msg':
          ui.message(ev.text, ev.color);
          ui.invalidate();
          break;
        case 'levelUp':
          void persist();
          break;
        case 'mapChange':
          ui.closePanels();
          void persist();
          break;
        default:
          break;
      }
    }
    world.events = [];
    renderer.draw();
    ui.update();
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

main().catch((e) => {
  console.error(e);
  status(`出错了：${(e as Error).message}`);
});
