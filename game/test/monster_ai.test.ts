/**
 * 怪物行为的模拟测试：用真实 game.json，造一张全可走的空地图，把玩家和怪放上去推进时间。
 * 运行：npm test（esbuild 打包后用 node --test 跑）。
 */
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Catalog, type GameData } from '../src/data/defs';
import { MapData } from '../src/assets/mapdata';
import { Player, type Monster } from '../src/world/entities';
import { World } from '../src/world/world';

const data = JSON.parse(readFileSync(`${process.cwd()}/../public/assets/data/game.json`, 'utf8')) as GameData;

function setup(): World {
  const catalog = new Catalog(data);
  const w = new World(catalog);
  const n = 60 * 60;
  const z = (): Uint8Array => new Uint8Array(n);
  const map = new MapData('T', 'T', 60, 60, z(), new Uint16Array(n), z(), z(), z(), z(), z(), z());
  // 不刷数据表里的怪：只测手放的
  (catalog.data as { spawns: unknown[] }).spawns = [];
  (catalog.data as { guards: unknown[] }).guards = [];
  (catalog.data as { merchants: unknown[] }).merchants = [];
  const p = new Player(1, '测试', 0, 0, 'T', 30, 30, 0);
  w.player = p;
  w.actors.set(p.id, p);
  w.recalc(p);
  p.stats = { ...p.stats, maxHp: 100000 };
  p.hp = 100000;
  w.enterMap(map, { x: 30, y: 30 });
  return w;
}

function put(w: World, name: string, x: number, y: number): Monster {
  const def = w.catalog.monstersByName.get(name);
  assert.ok(def, name);
  const m = w.spawnMonster(def, -1, { x, y });
  assert.ok(m);
  return m;
}

/** 推进时间，返回期间玩家挨打的总伤害（看事件，不看血：玩家会回血）。 */
function run(w: World, ms: number): number {
  let hurt = 0;
  for (let t = 0; t < ms; t += 50) {
    w.tick(50);
    for (const e of w.events) if (e.type === 'damage' && e.target === w.player.id) hurt += e.amount;
    w.events = [];
  }
  return hurt;
}

test('食人花埋在地下，人走近才钻出来，打不到埋着的', () => {
  const w = setup();
  const m = put(w, '食人花', 40, 30);
  assert.equal(m.cover, 'buried');
  assert.equal(w.isEnemy(w.player, m), false);
  run(w, 3000);
  assert.equal(m.cover, 'buried');
  w.teleportActor(w.player, { x: 38, y: 30 });
  run(w, 3000);
  assert.equal(m.cover, 'open');
  assert.equal(w.isEnemy(w.player, m), true);
});

test('祖玛雕像是石像，2 格内醒来并叫醒附近的', () => {
  const w = setup();
  const a = put(w, '祖玛雕像', 40, 30);
  const b = put(w, '祖玛雕像', 45, 30);
  assert.equal(a.cover, 'stone');
  w.teleportActor(w.player, { x: 38, y: 30 });
  run(w, 3000);
  assert.equal(a.cover, 'open');
  assert.equal(b.cover, 'open');
});

test('守卫按 Race 认：沃玛卫士、祖玛卫士能打，卫士打不到（#1157）', () => {
  const w = setup();
  const woma = put(w, '沃玛卫士', 31, 30);
  const guard = put(w, '卫士', 5, 5);
  assert.equal(w.isEnemy(w.player, woma), true);
  assert.equal(w.isEnemy(w.player, guard), false);
  assert.ok(run(w, 3000) > 0, '沃玛卫士会打人');
  // 祖玛卫士是石像，走近醒来后能打
  const zuma = put(w, '祖玛卫士', 32, 30);
  run(w, 3000);
  assert.equal(w.isEnemy(w.player, zuma), true);
});

test('洞蛆贴身喷气会打人', () => {
  const w = setup();
  put(w, '洞蛆', 31, 30);
  assert.ok(run(w, 20_000) > 0);
});

test('僵尸电光隔几格也能打到', () => {
  const w = setup();
  const m = put(w, '僵尸1', 34, 30);
  m.dc = [200, 200];
  assert.ok(run(w, 10_000) > 0);
});

test('角蝇巢不动，见人放蝙蝠', () => {
  const w = setup();
  const q = put(w, '角蝇', 34, 30);
  const at = q.pos;
  run(w, 15_000);
  assert.deepEqual(q.pos, at);
  assert.ok(q.minions.length > 0);
});

test('爆裂蜘蛛贴身自爆，自己死、不给经验', () => {
  const w = setup();
  const s = put(w, '爆裂蜘蛛', 31, 30);
  const exp = w.player.exp;
  run(w, 10_000);
  assert.ok(s.dead);
  assert.equal(w.player.exp, exp);
});

test('祖玛教主掉血会召唤祖玛怪', () => {
  const w = setup();
  const k = put(w, '祖玛教主', 31, 30);
  w.teleportActor(w.player, { x: 30, y: 30 });
  run(w, 3000);
  assert.equal(k.cover, 'open');
  k.hp = Math.floor(k.maxHp * 0.5);
  run(w, 3000);
  assert.ok(k.minions.length > 0);
});

test('传送菜单：每个目的地都是游戏里有的地图，传送会排上', () => {
  const w = setup();
  const list = w.catalog.data.teleports;
  assert.ok(list.length > 10);
  for (const t of list) assert.ok(w.catalog.mapsById.has(t.map), t.map);
  assert.equal(w.teleportTo(list[1]!), null);
  assert.deepEqual(w.pendingTeleport, { map: list[1]!.map, x: list[1]!.x, y: list[1]!.y });
});
