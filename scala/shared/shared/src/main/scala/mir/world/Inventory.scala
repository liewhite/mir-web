package mir.world

import mir.core.*
import mir.data.{Catalog, ItemDef}
import mir.model.*
import mir.rules.ItemKind

import scala.collection.mutable

/**
 * 身上（包裹 40 格 + 快捷栏 6 格）和仓库里物品的进出（#1159，照 TS 版 game/src/world/inventory.ts）：
 * 可叠的（ItemKind.stacks）同名并成一叠、不设上限；格子按「格」算满，重量按个数算（一叠 10 瓶药重 10 瓶）。
 * 用一个只减数量；卖、存、取可以只动一叠里的几个（分出一件新编号的）。
 */
private[world] object Inventory:

  /** 药品、卷轴可以放快捷栏 */
  def beltable(k: ItemKind): Boolean = k match
    case ItemKind.Potion(_, _, _) | ItemKind.Scroll(_) => true
    case _                                             => false

  /** 包裹或快捷栏里的一件物品和它的位置。 */
  def carried(p: Player, uid: ItemUid): Option[(ItemPlace, ItemInstance)] =
    p.bag.indices.collectFirst { case i if p.bag(i).exists(_.uid == uid) => ItemPlace.Bag(i) -> p.bag(i).get }
      .orElse(p.belt.indices.collectFirst { case i if p.belt(i).exists(_.uid == uid) => ItemPlace.Belt(i) -> p.belt(i).get })

  def at(p: Player, place: ItemPlace): Option[ItemInstance] = place match
    case ItemPlace.Bag(i)     => p.bag.lift(i).flatten
    case ItemPlace.Belt(i)    => p.belt.lift(i).flatten
    case ItemPlace.Worn(slot) => p.worn.get(slot)

  def put(p: Player, place: ItemPlace, it: Option[ItemInstance]): Unit = place match
    case ItemPlace.Bag(i)     => p.bag(i) = it
    case ItemPlace.Belt(i)    => p.belt(i) = it
    case ItemPlace.Worn(slot) => it.fold(p.worn.remove(slot).foreach(_ => ()))(p.worn(slot) = _)

  /** 身上已有的同名一叠的位置（快捷栏优先）；不可叠的物品没有「一叠」。 */
  private def stackOf(p: Player, d: ItemDef): Option[ItemPlace] =
    Option.when(ItemKind.stacks(d))(
      p.belt.indexWhere(_.exists(_.name == d.name)) match
        case -1 => Some(p.bag.indexWhere(_.exists(_.name == d.name))).filter(_ >= 0).map(ItemPlace.Bag(_))
        case i  => Some(ItemPlace.Belt(i))
    ).flatten

  /** 空位：药品、卷轴先快捷栏，再包裹。 */
  private def freeSlot(p: Player, d: ItemDef): Option[ItemPlace] =
    Option.when(beltable(ItemKind.of(d)))(p.belt.indexOf(None)).filter(_ >= 0).map(ItemPlace.Belt(_))
      .orElse(Some(p.bag.indexOf(None)).filter(_ >= 0).map(ItemPlace.Bag(_)))

  /** 格子放不放得下（并进已有的一叠也算放得下）；重量另算。 */
  def hasRoom(p: Player, d: ItemDef): Boolean = stackOf(p, d).isDefined || freeSlot(p, d).isDefined

  /** 放到身上：并进同名一叠，否则找空位。放不下返回 false（什么都不动）。 */
  def add(catalog: Catalog, p: Player, it: ItemInstance): Boolean =
    catalog.item(it.name) match
      case None => false
      case Some(d) =>
        stackOf(p, d) match
          case Some(place) =>
            at(p, place).foreach(s => put(p, place, Some(s.copy(count = s.count + it.count))))
            true
          case None =>
            val slot = freeSlot(p, d)
            slot.foreach(put(p, _, Some(it)))
            slot.isDefined

  /** 从一件里拿走 n 个（None = 整件）：(拿走的, 剩下的)。拿一部分时拿走的那份换新编号。 */
  private def split(it: ItemInstance, n: Option[Int], uids: () => ItemUid): (ItemInstance, Option[ItemInstance]) =
    val k = n.fold(it.count)(_.max(1).min(it.count))
    if k >= it.count then (it, None)
    else (it.copy(uid = uids(), count = k), Some(it.copy(count = it.count - k)))

  /** 从身上（包裹或快捷栏）拿走 n 个（None = 整件）；没有这件返回 None。 */
  def take(p: Player, uid: ItemUid, n: Option[Int], uids: () => ItemUid): Option[ItemInstance] =
    carried(p, uid).map { (place, it) =>
      val (taken, rest) = split(it, n, uids)
      put(p, place, rest)
      taken
    }

  /** 物品的总重：每个都算（一叠按个数乘）。 */
  def weight(catalog: Catalog, it: ItemInstance): Int = catalog.item(it.name).fold(0)(_.weight) * it.count

  /** 存进仓库：并进同名一叠。 */
  def store(catalog: Catalog, storage: mutable.ArrayBuffer[ItemInstance], it: ItemInstance): Unit =
    val i = if catalog.item(it.name).exists(ItemKind.stacks) then storage.indexWhere(_.name == it.name) else -1
    if i >= 0 then storage(i) = storage(i).copy(count = storage(i).count + it.count)
    else storage += it

  /** 从仓库拿走 n 个（None = 整件）。 */
  def unstore(storage: mutable.ArrayBuffer[ItemInstance], uid: ItemUid, n: Option[Int], uids: () => ItemUid): Option[ItemInstance] =
    Some(storage.indexWhere(_.uid == uid)).filter(_ >= 0).map { i =>
      val (taken, rest) = split(storage(i), n, uids)
      rest.fold(storage.remove(i): Unit)(storage(i) = _)
      taken
    }

  /**
   * 进图整理（旧存档、TS 版迁移过来的存档）：数量不合法的改成 1，不可叠的一律 1；
   * 身上（快捷栏在前、再包裹）和仓库各自把同名可叠的并成一叠，留第一次出现的那格。
   */
  def normalize(catalog: Catalog, p: Player): Unit =
    def stacks(it: ItemInstance) = catalog.item(it.name).exists(ItemKind.stacks)
    def fix(it: ItemInstance)    = if stacks(it) && it.count >= 1 then it else it.copy(count = 1)
    def merge(items: Vector[Option[ItemInstance]]): Vector[Option[ItemInstance]] =
      val first = mutable.Map.empty[ItemName, Int]
      val out   = items.map(_.map(fix)).toArray
      out.indices.foreach { i =>
        out(i).filter(stacks).foreach { it =>
          first.get(it.name) match
            case None => first(it.name) = i
            case Some(j) =>
              out(j) = out(j).map(s => s.copy(count = s.count + it.count))
              out(i) = None
        }
      }
      out.toVector
    val carried = merge(p.belt.toVector ++ p.bag.toVector)
    carried.take(Capacity.belt).zipWithIndex.foreach((it, i) => p.belt(i) = it)
    carried.drop(Capacity.belt).zipWithIndex.foreach((it, i) => p.bag(i) = it)
    val st = merge(p.storage.toVector.map(Some(_))).flatten
    p.storage.clear()
    p.storage ++= st
    p.worn.mapValuesInPlace((_, it) => fix(it))
