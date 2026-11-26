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
   * 超过 Rates.maxDropsPerKill 件时只留最稀有的：按数据表原版爆率（不乘倍率）从低到高排，取前 N 件。
   * 同爆率的先随机打乱（#1282）：否则名额不够时永远是表里靠前的几件，排后面的永远不掉。没超就原样返回。
   */
  def cap(all: List[Piece])(using Rng): List[Piece] =
    if all.sizeIs <= Rates.maxDropsPerKill then all
    else
      all.groupBy(rarity).toList.sortBy(_._1)
        .flatMap((_, same) => Rng.shuffle(same))
        .take(Rates.maxDropsPerKill)

  /**
   * 怪掉的这件直接进归属者身上、不落地（用户 11-26 #1328 定）：药（含捆装药，即自动捡物里「药品」那类）是；
   * 金币不经过这里，也一律直接进。其余照旧掉地上。进不去（包裹满了）的照旧落地。
   */
  def toOwner(item: ItemDef): Boolean = ItemKind.pickupKind(item) == mir.model.PickupKind.Potion

  /** 原版爆率（不乘倍率），越小越稀有；比值相同的算同一档（如 1/10 和 2/20）。 */
  private def rarity(p: Piece): Double = p.row.numerator.toDouble / p.row.denominator
