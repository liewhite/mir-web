/** 原版音效编号（Sound/SoundUtil.pas、Actor.pas SetSound）。播放在 audio/ 里，这里只决定「什么事发什么声」。 */

export const S = {
  walkGround: [1, 2],
  runGround: [3, 4],
  walkCave: [21, 22],
  runCave: [23, 24],
  hitFist: 57,
  money: 106,
  eatDrug: 107,
  spaceOut: 109,
  spaceIn: 110,
  itemClick: 118,
  glassButton: 105,
  yedoMan: 130,
  yedoWoman: 131,
  longHit: 132,
  wideHit: 133,
  fireHitReady: 136,
  fireHit: 137,
  manStruck: 138,
  womStruck: 139,
  manDie: 144,
  womDie: 145,
} as const;

/** 武器挥动声：按武器外观（Shape），Actor.pas:2156。 */
export function weaponSwing(shape: number | null): number {
  if (shape === null) return S.hitFist;
  if ([6, 20].includes(shape)) return 50; // 短剑
  if (shape === 1) return 51; // 木剑
  if ([2, 13, 9, 5, 14, 22].includes(shape)) return 52; // 剑
  if ([4, 17, 10, 15, 16, 23].includes(shape)) return 53; // 刀
  if ([3, 7, 11].includes(shape)) return 54; // 斧
  if (shape === 24) return 55;
  if ([8, 12, 18, 21].includes(shape)) return 56; // 长兵器
  return S.hitFist;
}

/** 被武器打中的声音（打在肉上）：Actor.pas:2215。 */
export function struckBody(shape: number | null): number {
  if (shape === null) return 73;
  if ([6, 1, 2, 4, 5, 9, 10, 13, 14, 15, 16, 17].includes(shape)) return 70;
  if ([3, 11].includes(shape)) return 71;
  if ([8, 12, 18].includes(shape)) return 72;
  return 73;
}

/** 怪物：200 + 外观×10 + k（0 出现 1 平时 2 攻击 3 武器 4 惨叫 5 死亡）。 */
export function monsterSound(appr: number, k: 0 | 1 | 2 | 3 | 4 | 5): number {
  return 200 + appr * 10 + k;
}

/** 魔法：10000 + 技能编号×10 + k（0 起手 1 飞行 2 爆炸）。 */
export function magicSound(id: number, k: 0 | 1 | 2): number {
  return 10000 + id * 10 + k;
}
