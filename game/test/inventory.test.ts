/**
 * 物品叠放（#1159）：捡、买、拆包并进同名一叠；用一个减一；卖、存、取可以只动几个；旧存档读进来补数量、合并。
 */
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Catalog, type GameData } from '../src/data/defs';
import { Player } from '../src/world/entities';
import { World } from '../src/world/world';
import { newItem } from '../src/world/items';
import { fromSave, toSave } from '../src/save/save';

const data = JSON.parse(readFileSync(`${process.cwd()}/../public/assets/data/game.json`, 'utf8')) as GameData;
const catalog = new Catalog(data);
const RED = '金创药(小量)';

function setup(): { w: World; p: Player } {
  const w = new World(catalog);
  const p = new Player(1, '测试', 0, 0, '0', 30, 30, 0);
  w.player = p;
  w.actors.set(p.id, p);
  w.recalc(p);
  p.gold = 100000;
  return { w, p };
}

const all = (p: Player) => [...p.belt, ...p.bag].filter((x) => x !== null);

test('同名药并成一叠，装备不叠', () => {
  const { w, p } = setup();
  for (let i = 0; i < 3; i++) assert.equal(w.buy(p, RED), null);
  w.addToBag(p, newItem(catalog.item(RED), 4));
  const reds = all(p).filter((x) => x.name === RED);
  assert.equal(reds.length, 1);
  assert.equal(reds[0]!.count, 7);
  assert.equal(p.belt[0], reds[0], '药放在快捷栏');
  w.addToBag(p, newItem(catalog.item('木剑')));
  w.addToBag(p, newItem(catalog.item('木剑'), 5));
  const swords = all(p).filter((x) => x.name === '木剑');
  assert.deepEqual(swords.map((x) => x.count), [1, 1]);
});

test('用一个只减一，用完格子空出来；重量按个数算', () => {
  const { w, p } = setup();
  w.addToBag(p, newItem(catalog.item(RED), 2));
  const it = p.belt[0]!;
  assert.equal(w.bagWeight(p), catalog.item(RED).weight * 2);
  assert.equal(w.useItem(p, it.uid), null);
  assert.equal(p.belt[0]?.count, 1);
  assert.equal(w.useItem(p, it.uid), null);
  assert.equal(p.belt[0], null);
});

test('格子满了：已有同名一叠还能捡/买，没有就不行', () => {
  const { w, p } = setup();
  w.addToBag(p, newItem(catalog.item(RED)));
  p.belt = p.belt.map((x) => x ?? newItem(catalog.item('回城卷')));
  p.bag = p.bag.map(() => newItem(catalog.item('木剑')));
  p.stats = { ...p.stats, maxBagWeight: 1e9 };
  assert.equal(w.buy(p, RED), null);
  assert.equal(p.belt[0]!.count, 2);
  assert.equal(w.buy(p, '魔法药(小量)'), '包裹满了');
});

test('拆捆装药：6 个并进同名一叠', () => {
  const { w, p } = setup();
  const bundle = catalog.data.items.find((i) => i.mode === 31)!;
  const one = catalog.data.items.find((i) => i.mode !== 31 && i.ani === bundle.shape)!;
  w.addToBag(p, newItem(catalog.item(one.name), 2));
  w.addToBag(p, newItem(bundle, 3));
  const b = all(p).find((x) => x.name === bundle.name)!;
  assert.equal(w.useItem(p, b.uid), null);
  assert.equal(b.count, 2);
  assert.equal(all(p).find((x) => x.name === one.name)!.count, 8);
});

test('卖一叠里的几个、存取并叠', () => {
  const { w, p } = setup();
  w.addToBag(p, newItem(catalog.item(RED), 10));
  const it = p.belt[0]!;
  const gold = p.gold;
  assert.equal(w.sell(p, it.uid, 3), null);
  assert.equal(it.count, 7);
  assert.equal(p.gold - gold, Math.floor(catalog.item(RED).price / 2) * 3);
  assert.equal(w.deposit(p, it.uid, 2), null);
  assert.equal(w.deposit(p, it.uid, 1), null);
  assert.equal(p.storage.length, 1);
  assert.equal(p.storage[0]!.count, 3);
  assert.equal(it.count, 4);
  assert.equal(w.withdraw(p, p.storage[0]!.uid, 1), null);
  assert.equal(it.count, 5);
  assert.equal(p.storage[0]!.count, 2);
  assert.equal(w.withdraw(p, p.storage[0]!.uid), null);
  assert.equal(it.count, 7);
  assert.equal(p.storage.length, 0);
  assert.equal(w.sell(p, it.uid), null);
  assert.equal(p.belt[0], null);
});

test('旧存档：没有数量的补 1，同名药并成一叠，装备不动', () => {
  const { p } = setup();
  const legacy = (name: string, uid: number) => {
    const d = catalog.item(name);
    return { uid, name, dura: d.dura, duraMax: d.dura } as unknown as ReturnType<typeof newItem>;
  };
  p.belt[0] = legacy(RED, 1);
  p.bag[0] = legacy(RED, 2);
  p.bag[1] = legacy('木剑', 3);
  p.bag[2] = legacy('木剑', 4);
  p.bag[3] = legacy(RED, 5);
  p.storage = [legacy('回城卷', 6), legacy('回城卷', 7)];
  const save = JSON.parse(JSON.stringify(toSave(p, {})));
  const q = fromSave(save, catalog, 1, 0);
  assert.equal(q.belt[0]!.count, 3);
  assert.equal(q.bag[0], null);
  assert.equal(q.bag[3], null);
  assert.deepEqual([q.bag[1]!.count, q.bag[2]!.count], [1, 1]);
  assert.equal(q.storage.length, 1);
  assert.equal(q.storage[0]!.count, 2);
});
