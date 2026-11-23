/**
 * 把物品和属性讲成人话（用户要求：属性增益要明确）。纯函数，只出文字。
 */
import type { ItemDef, Range } from '../data/defs';
import type { Player } from '../world/entities';
import { type ItemEffects, type ItemInstance, itemEffects, itemKind, requirement } from '../world/items';
import { JOB_NAME, hitChance, type Stats } from '../world/rules';

export interface Line {
  text: string;
  tone?: 'good' | 'bad' | 'dim' | 'title' | 'gold';
}

const r = (v: Range): string => (v[0] === v[1] ? `${v[0]}` : `${v[0]}-${v[1]}`);
const has = (v: Range): boolean => v[0] > 0 || v[1] > 0;

/** 一件装备的效果，逐项写成说明。 */
export function effectLines(e: ItemEffects): Line[] {
  const out: Line[] = [];
  if (has(e.dc)) out.push({ text: `攻击 +${r(e.dc)}　（普通攻击和战士剑术的伤害）` });
  if (has(e.mc)) out.push({ text: `魔法 +${r(e.mc)}　（法师技能的伤害）` });
  if (has(e.sc)) out.push({ text: `道术 +${r(e.sc)}　（道士技能的伤害、治疗量和召唤物强度）` });
  if (has(e.ac)) out.push({ text: `防御 +${r(e.ac)}　（每次挨打减去这个范围里的随机值）` });
  if (has(e.mac)) out.push({ text: `魔防 +${r(e.mac)}　（魔法伤害减去这个范围里的随机值）` });
  if (e.hit) out.push({ text: `准确 +${e.hit}　（更不容易打空）` });
  if (e.agility) out.push({ text: `敏捷 +${e.agility}　（更容易躲开怪物的攻击）` });
  if (e.attackSpeed > 0) out.push({ text: `攻击速度 +${e.attackSpeed}　（每 2 点让两刀之间快 60 毫秒）`, tone: 'good' });
  if (e.attackSpeed < 0) out.push({ text: `攻击速度 ${e.attackSpeed}　（出刀变慢）`, tone: 'bad' });
  if (e.luck) out.push({ text: `幸运 +${e.luck}　（打出最大攻击的机会更高）`, tone: 'good' });
  if (e.curse) out.push({ text: `诅咒 +${e.curse}　（打出最小攻击的机会更高）`, tone: 'bad' });
  if (e.magicAvoid) out.push({ text: `魔法躲避 +${e.magicAvoid}　（有机会完全躲开魔法）` });
  if (e.poisonAvoid) out.push({ text: `毒物躲避 +${e.poisonAvoid}　（有机会不中毒）` });
  if (e.hpRegen) out.push({ text: `体力恢复 +${e.hpRegen}　（每 2 秒多回 ${e.hpRegen} 点血）` });
  if (e.mpRegen) out.push({ text: `魔法恢复 +${e.mpRegen}　（每 2 秒多回 ${e.mpRegen} 点蓝）` });
  if (e.poisonRegen) out.push({ text: `中毒恢复 +${e.poisonRegen}　（中毒时间缩短）` });
  return out;
}

/** 物品说明（含与当前装备的对比）。 */
export function itemLines(def: ItemDef, inst: ItemInstance | null, p: Player, compareWith: ItemDef | null): Line[] {
  const out: Line[] = [{ text: def.name, tone: 'title' }];
  const k = itemKind(def);
  switch (k.kind) {
    case 'potion':
      if (k.hp) out.push({ text: `回复体力 ${k.hp}${k.instant ? '（立即）' : '（几秒内慢慢回满）'}` });
      if (k.mp) out.push({ text: `回复魔法 ${k.mp}${k.instant ? '（立即）' : '（几秒内慢慢回满）'}` });
      break;
    case 'book':
      out.push({ text: `${JOB_NAME[k.job as 0 | 1 | 2] ?? ''}技能书，${k.level} 级可学`, tone: p.job === k.job && p.level >= k.level ? undefined : 'bad' });
      break;
    case 'scroll':
      out.push({ text: { town: '回到城里', escape: '离开地下城回到城里', random: '随机传送到本地图某处', other: '特殊卷轴' }[k.effect] });
      break;
    case 'bundle':
      out.push({ text: '双击拆成 6 瓶（和包裹里同名的药叠在一起）' });
      break;
    case 'amulet':
      out.push({ text: k.poison === 0 ? '道士施法消耗（灵魂火符、召唤、隐身）' : k.poison === 1 ? '施毒术用：绿毒，持续掉血' : '施毒术用：红毒，降低防御' });
      out.push({ text: '装备在左手镯位置使用' , tone: 'dim' });
      if (inst) out.push({ text: `剩余 ${Math.ceil(inst.dura / 100)} 次`, tone: 'dim' });
      break;
    case 'equip': {
      const e = itemEffects(def);
      out.push(...effectLines(e));
      if (compareWith && compareWith.name !== def.name) out.push(...compareLines(e, itemEffects(compareWith), compareWith.name));
      break;
    }
    default:
      break;
  }
  const req = requirement(def);
  if (k.kind === 'equip' && req.value > 0) {
    const label = { level: '等级', dc: '攻击', mc: '魔法', sc: '道术' }[req.stat];
    const have = req.stat === 'level' ? p.level : req.stat === 'dc' ? p.stats.dc[1] : req.stat === 'mc' ? p.stats.mc[1] : p.stats.sc[1];
    out.push({ text: `需要${label} ${req.value}`, tone: have >= req.value ? 'dim' : 'bad' });
  }
  if (inst && inst.count > 1) out.push({ text: `数量 ${inst.count}（叠在一格，用一个少一个）` });
  out.push({ text: `重量 ${def.weight}${inst && k.kind === 'equip' ? `　持久 ${Math.ceil(inst.dura / 1000)}/${Math.ceil(inst.duraMax / 1000)}` : ''}`, tone: 'dim' });
  const each = Math.floor(def.price / 2);
  out.push({ text: inst && inst.count > 1 ? `卖给商店每个约 ${each} 金币，整叠 ${each * inst.count}` : `卖给商店约 ${each} 金币`, tone: 'gold' });
  return out;
}

function compareLines(a: ItemEffects, b: ItemEffects, oldName: string): Line[] {
  const diffs: Line[] = [];
  const range = (name: string, x: Range, y: Range): void => {
    const d0 = x[0] - y[0];
    const d1 = x[1] - y[1];
    if (d0 || d1) {
      const s = (n: number): string => (n > 0 ? `+${n}` : `${n}`);
      diffs.push({ text: `${name} ${s(d0)}/${s(d1)}`, tone: d0 + d1 >= 0 ? 'good' : 'bad' });
    }
  };
  const num = (name: string, x: number, y: number, goodWhenHigher = true): void => {
    const d = x - y;
    if (d) diffs.push({ text: `${name} ${d > 0 ? '+' : ''}${d}`, tone: (d > 0) === goodWhenHigher ? 'good' : 'bad' });
  };
  range('攻击', a.dc, b.dc);
  range('魔法', a.mc, b.mc);
  range('道术', a.sc, b.sc);
  range('防御', a.ac, b.ac);
  range('魔防', a.mac, b.mac);
  num('准确', a.hit, b.hit);
  num('敏捷', a.agility, b.agility);
  num('攻速', a.attackSpeed, b.attackSpeed);
  num('幸运', a.luck - a.curse, b.luck - b.curse);
  if (!diffs.length) return [];
  return [{ text: `换下 ${oldName} 后：`, tone: 'dim' }, ...diffs];
}

/** 属性面板：每项属性写出它实际意味着什么。agilityRef 用当前地图常见怪的敏捷估算命中率。 */
export function statLines(s: Stats, p: Player, typicalMonsterAgility: number, typicalMonsterHit: number): Line[] {
  const hitPct = Math.round(hitChance(s.hit, typicalMonsterAgility) * 100);
  const dodgePct = Math.round((1 - hitChance(typicalMonsterHit, s.agility)) * 100);
  const lines: Line[] = [
    { text: `${p.name}　${JOB_NAME[p.job]}　${p.level} 级`, tone: 'title' },
    { text: `体力 ${p.hp}/${s.maxHp}　魔法 ${p.mp}/${s.maxMp}` },
    { text: `攻击 ${r(s.dc)}：普通攻击每刀伤害在这之间随机，再减去怪物防御` },
  ];
  if (has(s.mc)) lines.push({ text: `魔法 ${r(s.mc)}：法师技能威力 = 技能基础值 + 这个范围里的随机值` });
  if (has(s.sc)) lines.push({ text: `道术 ${r(s.sc)}：治愈、火符威力和召唤物强度都按它算` });
  lines.push(
    { text: `防御 ${r(s.ac)}：每次挨打先减掉这个范围里的随机值` },
    { text: `魔防 ${r(s.mac)}：魔法伤害先减掉这个范围里的随机值` },
    { text: `准确 ${s.hit}：打附近怪物（敏捷约 ${typicalMonsterAgility}）命中率约 ${hitPct}%` },
    { text: `敏捷 ${s.agility}：附近怪物（准确约 ${typicalMonsterHit}）打你约 ${dodgePct}% 打空` },
    { text: `攻击间隔 ${s.hitInterval} 毫秒（装备攻速每 2 点快 60 毫秒）` },
  );
  if (s.luck) lines.push({ text: `幸运 ${s.luck}` });
  lines.push({ text: `负重：包裹 ${s.maxBagWeight}，穿戴 ${s.maxWearWeight}，腕力 ${s.maxHandWeight}（能拿的武器重量）`, tone: 'dim' });
  return lines;
}
