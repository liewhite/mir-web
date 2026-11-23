/** 入口：登录 → 读档或建角色 → 主循环。 */
import { loadCatalog, type Catalog } from './data/defs';
import { loadMap } from './assets/mapdata';
import { SpriteStore, loadAssetVersion } from './assets/sprites';
import { Player } from './world/entities';
import { newItem } from './world/items';
import { World } from './world/world';
import type { Job } from './world/rules';
import { Renderer } from './render/renderer';
import { Controller } from './game/controller';
import { bindInput } from './game/input';
import { loadSettings } from './game/settings';
import { HttpSaveStore, type SaveData, type SaveStore, fromSave, toSave } from './save/save';
import { ClassicUi } from './ui/classic/classic';
import { type Viewport, chooseViewport, coarsePointer } from './ui/viewport';
import { Gfx } from './ui/classic/gfx';
import { Audio } from './audio/audio';

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
  await loadAssetVersion();
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
  const map = await Promise.resolve()
    .then(() => loadMap(catalog.map(player.map)))
    .catch(() => loadMap(catalog.map(START.map)));
  world.enterMap(map, player.map === map.id ? player.pos : START);

  const settings = loadSettings(save?.settings);
  const sprites = new SpriteStore();
  const canvas = $<HTMLCanvasElement>('#game');
  const renderer = new Renderer(canvas, sprites, world, catalog);
  const ctl = new Controller(world, settings);
  const touch = coarsePointer();
  const ui = new ClassicUi(new Gfx(renderer.context, sprites), world, ctl, catalog, touch);
  ctl.onMessage = (t, c) => ui.message(t, c);
  ctl.onTalk = (id) => {
    const npc = world.actors.get(id);
    if (npc?.kind === 'npc') ui.openNpc(npc);
  };
  const audio = new Audio();
  await audio.init();
  const applyView = (): void => {
    renderer.view = { showHpBars: settings.showHpBars, showItemNames: settings.showItemNames, showMonsterNames: settings.showMonsterNames };
    audio.soundOn = settings.soundOn;
    const musicWas = audio.musicOn;
    audio.musicOn = settings.musicOn;
    if (settings.musicOn && !musicWas) audio.playMusic('field');
    if (!settings.musicOn) audio.stopMusic();
  };
  const unlock = (): void => audio.unlock();
  window.addEventListener('pointerdown', unlock);
  window.addEventListener('keydown', unlock);
  ui.onButton = () => audio.play(105);
  ui.onLogout = () => {
    void persist().finally(() => location.reload());
  };
  applyView();
  ctl.onSettingsChanged = () => {
    applyView();
    void persist();
  };
  let view: Viewport = chooseViewport(window.innerWidth, window.innerHeight, touch);
  const fit = (): void => {
    const w = window.innerWidth;
    const h = window.innerHeight;
    view = chooseViewport(w, h, touch);
    renderer.resize(w, h, view.worldZoom);
    // 手机布局底下没有高底栏，人物放正中
    renderer.centerY = view.kind === 'touch' ? 0.5 : 0.47;
    ui.setLayout(view.kind);
    ui.ctlScale = view.ctlScale;
    const css = getComputedStyle(document.documentElement);
    const inset = (name: string): number => (parseFloat(css.getPropertyValue(name)) || 0) / view.uiScale;
    ui.safe = { left: inset('--safe-l'), right: inset('--safe-r'), top: inset('--safe-t'), bottom: inset('--safe-b') };
  };
  fit();
  window.addEventListener('resize', fit);
  // iOS 转屏后 innerWidth 有时晚一帧才更新
  window.addEventListener('orientationchange', () => setTimeout(fit, 300));
  bindInput(canvas, renderer, ctl, ui, () => view.uiScale);
  await Promise.race([
    renderer.preloadAround(player.pos, (d, t) => status(`加载画面… ${d}/${t}`)),
    new Promise((r) => setTimeout(r, 30_000)),
  ]);
  await sprites.preload('prguse', [1, 3, 370]);
  (window as unknown as Record<string, unknown>).__mir = { world, renderer, ctl, sprites, ui };
  ui.message(
    ui.layout.joystick
      ? '欢迎来到玛法大陆。左边摇杆走路（推远了跑）；点怪锁定，右边「攻击」打它；点物品看说明和操作。'
      : '欢迎来到玛法大陆。F9 包裹，F10 状态，F11 技能，右下角按钮也可以打开。',
    'good',
  );
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
    const dt = Math.min(100, t - last) * (((window as unknown as Record<string, unknown>).__timeScale as number | undefined) ?? 1);
    last = t;
    world.tick(dt);
    ctl.update();
    for (const ev of world.events) {
      switch (ev.type) {
        case 'damage':
          renderer.float(ev.target, `-${ev.amount}`, ev.target === player.id ? '#ff6b6b' : '#ffd166');
          break;
        case 'miss':
          renderer.float(ev.target, 'MISS', '#bbb');
          break;
        case 'heal':
          if (ev.amount > 0) renderer.float(ev.target, `+${ev.amount}`, '#7bed9f');
          break;
        case 'msg':
          ui.message(ev.text, ev.color);
          break;
        case 'sound':
          audio.play(ev.id, ev.at, player.pos);
          break;
        case 'levelUp':
          void persist();
          break;
        case 'mapChange':
          ui.closeAll();
          void persist();
          break;
        default:
          break;
      }
    }
    world.events = [];
    renderer.targetActor = ui.layout.joystick ? (ctl.focused()?.id ?? 0) : 0;
    renderer.draw();
    renderer.scaleTo(view.uiScale);
    ui.draw(window.innerWidth / view.uiScale, window.innerHeight / view.uiScale, world.now);
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

main().catch((e) => {
  console.error(e);
  status(`出错了：${(e as Error).message}`);
});
