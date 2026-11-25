package mir.rules

import mir.core.Gold
import mir.data.ItemDef
import mir.model.ItemInstance

/**
 * 商店一次买几个（#1304，原版一次买一个，玩家嫌手点麻）：服务端和界面共用的上限。
 * 能买几个 = 一次上限、金币够几个、身上放得下几个 三者取小；服务端照这个校验，超了就拒绝（不擅自少买）。
 */
object Purchase:
  /** 一次最多买几个 */
  val MaxPerBuy: Int = 999

  /** 买 n 个一共多少金币（Long：贵东西乘数量会超 Int） */
  def cost(d: ItemDef, n: Int): Long = Pricing.buy(d).toLong * n

  /** 金币够买几个（白送的不限） */
  def affordable(d: ItemDef, gold: Gold): Int =
    Pricing.buy(d) match
      case p if p <= 0 => Int.MaxValue
      case p           => gold.value / p

  /**
   * 身上（快捷栏、包裹）还放得下几个：可叠的同名并成一叠、不设上限，所以已有一叠或有一个空位就不限；
   * 不可叠的一个占一格（药、卷轴还能放快捷栏空位）。
   */
  def room(d: ItemDef, belt: Seq[Option[ItemInstance]], bag: Seq[Option[ItemInstance]]): Int =
    val free = bag.count(_.isEmpty) + (if ItemKind.beltable(ItemKind.of(d)) then belt.count(_.isEmpty) else 0)
    if !ItemKind.stacks(d) then free
    else if free > 0 || (belt ++ bag).exists(_.exists(_.name == d.name)) then Int.MaxValue
    else 0

  /** 这次最多能买几个（0 = 一个也买不了） */
  def most(d: ItemDef, gold: Gold, belt: Seq[Option[ItemInstance]], bag: Seq[Option[ItemInstance]]): Int =
    MaxPerBuy.min(affordable(d, gold)).min(room(d, belt, bag))

  /** 为什么买不了 n 个；买得了是 None。 */
  def refusal(d: ItemDef, n: Int, gold: Gold, belt: Seq[Option[ItemInstance]], bag: Seq[Option[ItemInstance]]): Option[String] =
    if n < 1 || n > MaxPerBuy then Some(s"一次只能买 1–$MaxPerBuy 个")
    else if cost(d, n) > gold.value.toLong then
      Some(if n == 1 then "金币不够" else s"金币不够，只够买 ${affordable(d, gold)} 个")
    else
      room(d, belt, bag) match
        case 0          => Some("包裹满了")
        case r if r < n => Some(s"包裹只放得下 $r 件")
        case _          => None
