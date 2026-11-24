package mir.rules

import mir.data.{DropDef, ItemDef}

/**
 * 掷中的爆率行变成地上的物品件数，以及每只怪的件数上限（#1241）。
 * 金币不经过这里（不占名额）。
 */
object DropRules:
  /** 地上的一件：可叠物一叠（count 个）算一件，不可叠的每个一件（count = 1）。row 留着按原版爆率排稀有度。 */
  final case class Piece(row: DropDef, item: ItemDef, count: Int)

  /** 一行爆率掷中后掉的件：可叠的一次掉多个就是一叠（#1159），不可叠的掉 amount 件。 */
  def pieces(row: DropDef, item: ItemDef): List[Piece] =
    val n = math.max(1, row.amount)
    if ItemKind.stacks(item) then List(Piece(row, item, n)) else List.fill(n)(Piece(row, item, 1))

  /**
   * 超过 Rates.maxDropsPerKill 件时只留最稀有的：按数据表原版爆率（不乘倍率）从低到高排，
   * 同爆率保持数据表顺序（sortBy 是稳定排序），取前 N 件。没超就原样返回。
   */
  def cap(all: List[Piece]): List[Piece] =
    if all.sizeIs <= Rates.maxDropsPerKill then all
    else all.sortBy(p => p.row.numerator.toDouble / p.row.denominator).take(Rates.maxDropsPerKill)
