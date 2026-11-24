/**
 * 从 TS 版（game/src/world）算出一批数值，存成 ts-golden.json，给 Scala 版的规则测试逐条对照。
 * 只在 TS 版规则改动后重跑：scala/testdata/gen-golden.sh。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import type { GameData, ItemDef } from '../../game/src/data/defs';
import { computeStats, levelAbility, type Job } from '../../game/src/world/rules';
import { itemEffects, itemKind, requirement } from '../../game/src/world/items';
import { RATES, dropChance, isHighEquip } from '../../game/src/world/rates';

const [gameJson, out] = process.argv.slice(2) as [string, string];
const data = JSON.parse(readFileSync(gameJson, 'utf8')) as GameData;
const byName = new Map(data.items.map((i) => [i.name, i] as const));

const jobs: Job[] = [0, 1, 2];
const levels = Array.from({ length: 60 }, (_, i) => i + 1);

const equips = data.items.filter((i) => itemKind(i).kind === 'equip');
const statsCases = equips.map((it, i) => {
  const other: ItemDef = equips[(i * 13 + 5) % equips.length]!;
  const job = jobs[i % 3]!;
  const level = (i * 7) % 50 + 1;
  const passive = { oneSword: (i % 5) - 1, spiritSword: (i % 4) - 1, powerHit: (i % 3) - 1 };
  return {
    job, level, items: [it.name, other.name], passive,
    stats: computeStats(job, level, [itemEffects(it), itemEffects(other)], passive),
  };
});

const golden = {
  rates: RATES,
  levelAbility: jobs.map((j) => levels.map((lv) => levelAbility(j, lv))),
  items: data.items.map((d) => ({
    name: d.name, kind: itemKind(d), effects: itemEffects(d), requirement: requirement(d), highEquip: isHighEquip(d),
  })),
  stats: statsCases,
  drops: Object.entries(data.drops).flatMap(([monster, rows]) =>
    rows.map((d) => ({
      monster, item: d.item,
      chance: dropChance(monster, d, d.item === '金币' ? null : byName.get(d.item) ?? null),
    })),
  ),
};
writeFileSync(out, JSON.stringify(golden));
