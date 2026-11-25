package mir.rules

import mir.data.ItemDef

/** 物品相关的原版音效编号（Sound/SoundUtil.pas）。客户端（点物品格）和服务端（捡到、用掉）共用。 */
object ItemSounds:
  /**
   * 点物品的声音（拿起、放到装备位、放进买卖/交易框、捡到）：按 StdMode，原版 ItemClickSound。
   * 24、26 照原版名字里有「手镯」或「手套」就是手套声，其余是手镯声。
   */
  def click(d: ItemDef): Int = d.mode match
    case 0 | 31       => 108
    case 5 | 6        => 111
    case 10 | 11      => 112
    case 22 | 23      => 113
    case 24 | 26      => if d.name.value.contains("手镯") || d.name.value.contains("手套") then 117 else 114
    case 19 | 20 | 21 => 115
    case 15           => 116
    case _            => 118

  /** 使用物品的声音：原版 ClMain.pas 吃东西时的 ItemUseSound，药（StdMode 0）108、1、2 是 107，其余不出声。 */
  def use(d: ItemDef): Option[Int] = d.mode match
    case 0     => Some(108)
    case 1 | 2 => Some(107)
    case _     => None
