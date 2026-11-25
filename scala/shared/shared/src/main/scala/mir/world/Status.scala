package mir.world

import mir.core.*
import mir.model.{Affliction, Aura, PoisonKind}

import scala.collection.mutable

/** 身上限时状态的种类：同一种身上只有一个，再上一次覆盖或续上。 */
enum StatusKind:
  /** 绿毒、红毒各算一种，身上可以同时有（原版 POISON_DECHEALTH / POISON_DAMAGEARMOR 两个状态位） */
  case GreenPoison, RedPoison, Shield, Invisible, Paralyzed, ArmorUp, MagicArmorUp, Seized, Crazy, Brawler

object StatusKind:
  def poison(k: PoisonKind): StatusKind = k match
    case PoisonKind.Green => GreenPoison
    case PoisonKind.Red   => RedPoison

  val poisons: List[StatusKind] = List(GreenPoison, RedPoison)

  /** 看得见的异常状态（锁定卡、染色）对应的身上状态 */
  def of(af: Affliction): StatusKind = af match
    case Affliction.GreenPoison => GreenPoison
    case Affliction.RedPoison   => RedPoison
    case Affliction.Paralyzed   => Paralyzed

  /** 看得见的增益光效对应的身上状态 */
  def of(aura: Aura): StatusKind = aura match
    case Aura.MagicShield => Shield

/** 身上的限时状态和它带的参数。 */
enum Status(val kind: StatusKind):
  /** 中毒：绿毒每跳掉 power 血，红毒挨打伤害加深 power%。by：下毒的对象（经验归属；换图后没有） */
  case Poisoned(poison: PoisonKind, power: Int, nextTick: Millis, by: Option[ActorId]) extends Status(StatusKind.poison(poison))
  /** 魔法盾：按等级减伤 */
  case Shielded(level: Int) extends Status(StatusKind.Shield)
  /** 隐身：看不穿隐身的怪看不见 */
  case Invisible extends Status(StatusKind.Invisible)
  /** 麻痹（原版 STONE 状态）：不能动 */
  case Paralyzed extends Status(StatusKind.Paralyzed)
  /** 神圣战甲术：防御上限 + amount */
  case ArmorUp(amount: Int) extends Status(StatusKind.ArmorUp)
  /** 幽灵盾：魔防上限 + amount */
  case MagicArmorUp(amount: Int) extends Status(StatusKind.MagicArmorUp)
  /** 怪被定住（诱惑之光、困魔咒）：不动不打，挨打解除 */
  case Seized extends Status(StatusKind.Seized)
  /** 怪发狂（诱惑之光失败）：见谁打谁；宝宝不会发狂 */
  case Crazy extends Status(StatusKind.Crazy)
  /** 玩家灰名（#1225，原版 PKFlag）：刚动手打了清白的人，见 mir.rules.Pk */
  case Brawler extends Status(StatusKind.Brawler)

/** 一个状态和它的到期时刻。 */
final case class Timed(status: Status, until: Millis)

/**
 * 一个对象身上的全部限时状态。到期由 World 在每次 tick 开头统一去掉（expire），
 * 所以这里「有」就是「还在生效」，查的时候不用再比时间。
 */
final class Statuses:
  private val held = mutable.LinkedHashMap.empty[StatusKind, Timed]

  /** 上一个状态：同种的覆盖（时间、参数都换成新的）。 */
  def put(s: Status, until: Millis): Unit = held(s.kind) = Timed(s, until)

  /** 上一个状态，参数换成新的，但不会把还没到期的时间缩短（神圣战甲术、幽灵盾、麻痹）。 */
  def extend(s: Status, until: Millis): Unit =
    put(s, held.get(s.kind).fold(until)(old => if old.until > until then old.until else until))

  /** 换参数、不改到期时间（绿毒记下一跳）。没有这种状态时什么也不做。 */
  def revise(s: Status): Unit = held.get(s.kind).foreach(t => held(s.kind) = t.copy(status = s))

  def has(kind: StatusKind): Boolean        = held.contains(kind)
  def until(kind: StatusKind): Option[Millis] = held.get(kind).map(_.until)
  def remove(kind: StatusKind): Unit        = held.remove(kind): Unit
  def clear(): Unit                         = held.clear()

  /** 找一个带参数的状态，如 `find { case p: Status.Poisoned => p }`。 */
  def find[A](pf: PartialFunction[Status, A]): Option[A] = held.valuesIterator.map(_.status).collectFirst(pf)

  def all: List[Timed] = held.values.toList

  /** 去掉到期的（until <= now），返回去掉的。 */
  def expire(now: Millis): List[Timed] =
    val ended = held.values.filter(_.until <= now).toList
    ended.foreach(t => held.remove(t.status.kind))
    ended
