/** 动作 → 图库帧号。动作表照原版客户端 Actor.pas（HA、MA*）。 */
import type { Dir } from '../core/geom';
import type { ActionInfo, MonsterActionTable } from '../data/defs';
import type { Action } from '../world/entities';

/** 人物动作表 HA（每套衣服 600 帧）。 */
const HA = {
  stand: [0, 4, 4, 200, 0],
  walk: [64, 6, 2, 90, 2],
  run: [128, 6, 2, 120, 3],
  warMode: [192, 1, 0, 200, 0],
  hit: [200, 6, 2, 85, 0],
  heavyHit: [264, 6, 2, 90, 0],
  bigHit: [328, 8, 0, 70, 0],
  spell: [392, 6, 2, 60, 0],
  struck: [472, 3, 5, 70, 0],
  die: [536, 4, 4, 120, 0],
} as const satisfies Record<string, ActionInfo>;

export const HUMAN_FRAMES = 600;

/** 动作进度 0..1（循环动作按帧时长循环）。 */
function frameOf(info: ActionInfo, a: Action, now: number, loop: boolean): number {
  const [, frames, , ftime] = info;
  if (frames <= 1) return 0;
  const elapsed = now - a.startedAt;
  if (loop) return Math.floor(elapsed / ftime) % frames;
  const per = a.duration > 0 ? a.duration / frames : ftime;
  return Math.min(frames - 1, Math.max(0, Math.floor(elapsed / per)));
}

function pick(info: ActionInfo, dir: Dir, f: number): number {
  const [start, frames, skip] = info;
  return start + dir * (frames + skip) + f;
}

function finished(a: Action, now: number): boolean {
  return now - a.startedAt >= a.duration;
}

/** 人物当前帧（0..599）。 */
export function humanFrame(a: Action, dir: Dir, now: number, dead: boolean): number {
  if (dead) return pick(HA.die, dir, HA.die[1] - 1);
  if (finished(a, now) && a.kind !== 'die') return pick(HA.stand, dir, frameOf(HA.stand, a, now, true));
  switch (a.kind) {
    case 'walk':
      return pick(HA.walk, dir, frameOf(HA.walk, a, now, false));
    case 'run':
      return pick(HA.run, dir, frameOf(HA.run, a, now, false));
    case 'attack':
      return pick(HA.hit, dir, frameOf(HA.hit, a, now, false));
    case 'heavy':
      return a.hit === 'wide' || a.hit === 'fire'
        ? pick(HA.bigHit, dir, frameOf(HA.bigHit, a, now, false))
        : pick(HA.heavyHit, dir, frameOf(HA.heavyHit, a, now, false));
    case 'spell':
      return pick(HA.spell, dir, frameOf(HA.spell, a, now, false));
    case 'struck':
      return pick(HA.struck, dir, frameOf(HA.struck, a, now, false));
    case 'die':
    case 'dead':
      return pick(HA.die, dir, frameOf(HA.die, a, now, false));
    default:
      return pick(HA.stand, dir, frameOf(HA.stand, a, now, true));
  }
}

/** 怪物当前帧（相对该怪的起始帧）。 */
export function monsterFrame(t: MonsterActionTable, a: Action, dir: Dir, now: number, dead: boolean): number {
  if (dead) {
    if (a.kind === 'die' && !finished(a, now)) return pick(t.ActDie, dir, frameOf(t.ActDie, a, now, false));
    return pick(t.ActDie, dir, t.ActDie[1] - 1);
  }
  if (finished(a, now) || a.kind === 'stand') return pick(t.ActStand, dir, frameOf(t.ActStand, a, now, true));
  switch (a.kind) {
    case 'walk':
    case 'run':
      return t.ActWalk[1] ? pick(t.ActWalk, dir, frameOf(t.ActWalk, a, now, false)) : pick(t.ActStand, dir, 0);
    case 'attack':
    case 'heavy':
      return pick(t.ActAttack, dir, frameOf(t.ActAttack, a, now, false));
    case 'struck':
      return t.ActStruck[1] ? pick(t.ActStruck, dir, frameOf(t.ActStruck, a, now, false)) : pick(t.ActStand, dir, 0);
    default:
      return pick(t.ActStand, dir, frameOf(t.ActStand, a, now, true));
  }
}

/** 武器画在身体前还是后（原版 WORDER 表，按性别、帧号）。1 = 前。 */
const WORDER_SRC = [
  '000000001111111111111111111111110000000000001111000011110000111100000000111111111111111111111111000000000000000100000001000000010000000011111111111111111111111100111111001110010000000100000001011100001110001111100000111000001111111111111111111000000000000011110011011000110110001111100000111001111111111101111111000111000111101111010000110000001111100011001000111000010110000000001110111110000000001100000011000000111000011111111111011111110011001100010011001011111100010000011111111111111111111111111111000111110001111100011111000111110011111101111111111111111111111100011111000111110001111100011111',
  '000000001111111111111111111111110000000000001111000011110000111100000000111111111111111111111111000000000000000100000001000000010000000011111111111111111111111100111111001110010000000100000001111100001110001111100000111000001111111111111111111000000000000011110011011000110110001111100000111001111111111101111111000111000111101111010000110000001111100011001000111000010110000000001110111110000000001100000011000000111000011111111111011111110011001100010011001011111100010000011111111111111111111111111111000111110001111100011111000111110011111101111111111111111111111100011111000111110001111100011111',
];

export function weaponInFront(sex: 0 | 1, frame: number): boolean {
  return WORDER_SRC[sex]![frame] === '1';
}
