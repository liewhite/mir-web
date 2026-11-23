/** 便利功能设置：当年要靠外挂的功能，做成游戏内开关（用户 #1137 要求）。 */

export interface Settings {
  /** 自动捡附近的物品 */
  autoPickup: boolean;
  /** 只捡装备、金币和书（不捡药和杂物） */
  pickupValuableOnly: boolean;
  /** 血量低于这个百分比自动喝红（0 关闭） */
  autoHpPercent: number;
  /** 蓝量低于这个百分比自动喝蓝（0 关闭） */
  autoMpPercent: number;
  /** 点一下怪就一直打到它死 */
  stickyAttack: boolean;
  /** 被怪打时自动还手 */
  autoFightBack: boolean;
  /** 战士：烈火好了就自动开 */
  autoFireSword: boolean;
  /** 战士：自动站到隔一格的位置用刺杀 */
  autoLongHitPosition: boolean;
  showHpBars: boolean;
  showItemNames: boolean;
  showMonsterNames: boolean;
}

export const DEFAULT_SETTINGS: Settings = {
  autoPickup: true,
  pickupValuableOnly: false,
  autoHpPercent: 40,
  autoMpPercent: 20,
  stickyAttack: true,
  autoFightBack: true,
  autoFireSword: true,
  autoLongHitPosition: false,
  showHpBars: true,
  showItemNames: true,
  showMonsterNames: true,
};

export function loadSettings(raw: Record<string, unknown> | undefined): Settings {
  const s = { ...DEFAULT_SETTINGS };
  if (!raw) return s;
  for (const k of Object.keys(s) as (keyof Settings)[]) {
    const v = raw[k];
    if (typeof v === typeof s[k]) (s as Record<string, unknown>)[k] = v;
  }
  return s;
}

export const SETTING_LABELS: { key: keyof Settings; label: string; kind: 'bool' | 'percent' }[] = [
  { key: 'autoPickup', label: '自动捡附近的物品', kind: 'bool' },
  { key: 'pickupValuableOnly', label: '只捡装备、金币和书', kind: 'bool' },
  { key: 'autoHpPercent', label: '血量低于多少自动喝红药（%，0 关闭）', kind: 'percent' },
  { key: 'autoMpPercent', label: '蓝量低于多少自动喝蓝药（%，0 关闭）', kind: 'percent' },
  { key: 'stickyAttack', label: '点一下怪就一直打到死', kind: 'bool' },
  { key: 'autoFightBack', label: '站着被打时自动还手', kind: 'bool' },
  { key: 'autoFireSword', label: '战士：烈火冷却好了自动开', kind: 'bool' },
  { key: 'showHpBars', label: '头顶显示血条', kind: 'bool' },
  { key: 'showItemNames', label: '地上物品显示名字', kind: 'bool' },
  { key: 'showMonsterNames', label: '怪物显示名字', kind: 'bool' },
];
