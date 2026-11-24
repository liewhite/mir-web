package mir.world

import mir.core.ActorId
import mir.protocol.Look
import mir.rules.Side

/** 敌我：谁能打谁（按外观判断，规则和客户端共用 Look.hostile），以及给怪找最近的敌人。 */
final class Hostility(w: World):
  /**
   * 本次 tick 开始时玩家一边的对象（玩家、宝宝）。野怪只会把它们当敌人（Side.attacks），找敌人时只看这些，
   * 不用每只怪都把全图上千只怪扫一遍。发狂的怪不算：野怪不会主动去找它，只在挨了它的打时还手。
   */
  private var prey: Vector[Actor] = Vector.empty

  private def side(a: Actor): Option[Side] = a.look.side(w.catalog.isGuard)

  /** 每次 tick 开头重新收一遍 prey。 */
  private[world] def refresh(): Unit =
    prey = w.eachActor.filter(a => side(a).contains(Side.Players)).toVector

  /**
   * a 会不会打 b（自动找目标、范围攻击、挨打还手都按它，有方向）：都活着、不是同一个、a 的阵营打 b 的阵营
   * （Side.attacks：野怪不打守卫，所以挨了守卫的打也不还手，同原版 AnimalObject.Struck → IsProperTarget），埋着的、石像打不到；
   * 守卫和玩家平时不互打，但守卫被谁打了，Grudge.Ms 内把他当敌人（原版 GuardUnit.IsProperTarget 认 LastHiter）；
   * 守卫和宝宝之间另看 guardVsPet（#1216）。
   */
  def isEnemy(a: Actor, b: Actor): Boolean =
    alive(a, b) && (Look.hostile(a.look, b.look, w.catalog.isGuard) || grudge(a, b) || guardVsPet(a, b))

  /** a 能不能亲手打选中的 b：敌人，或者是玩家打守卫（Look.attackable）。所有攻击最终都过这一关（Vitals.strike）。 */
  def canAttack(a: Actor, b: Actor): Boolean =
    isEnemy(a, b) || (alive(a, b) && Look.attackable(a.look, b.look, w.catalog.isGuard))

  private def alive(a: Actor, b: Actor): Boolean = !a.dead && !b.dead && a.id != b.id

  /** a 是守卫、记着 b 刚打过它 */
  private def grudge(a: Actor, b: Actor): Boolean = a match
    case m: Monster => m.grudge.exists(g => g.by == b.id && w.now - g.at <= Grudge.Ms)
    case _          => false

  /** range 格以内最近的敌人（怪看不见隐身的，除非能看穿隐身）；远近按曼哈顿距离比（原版 SearchTarget）。 */
  private[world] def nearestEnemy(m: Actor, range: Int): Option[Actor] =
    val seesInvisible = m match
      case mon: Monster => mon.defn.coolEye > 0
      case _            => true
    val candidates = if side(m).contains(Side.Wild) then prey.iterator.filter(w.present) else w.eachActor
    candidates
      .filter(a => isEnemy(m, a) && (seesInvisible || !a.statuses.has(StatusKind.Invisible)) && !ignoredByGuard(m, a))
      .filter(a => m.cell.chebyshev(a.cell) <= range)
      .minByOption(a => m.cell.manhattan(a.cell))

  /**
   * 守卫和宝宝（#1216，阵营上宝宝跟玩家一边，这里按原版补上）：
   *  - 守卫打宝宝看守卫种类（GuardKind.huntsPet：卫士、带刀护卫见了就砍，弓箭守卫不打）；
   *  - 宝宝打守卫：守卫正打着它主人或主人的任何一只宝宝（原版 IsAttackTarget：BaseObject.m_TargetCret = m_Master
   *    或 m_TargetCret.m_Master = m_Master），所以挨砍的宝宝会还手，旁边的同主人宝宝也会围上去。
   */
  private def guardVsPet(a: Actor, b: Actor): Boolean = (a, b) match
    case (g: Monster, pet: Monster) if pet.master.isDefined =>
      g.defn.guard.exists(_.huntsPet(pet.defn.race, targetsMe = pet.target.contains(g.id)))
    case (pet: Monster, g: Monster) if g.isGuard =>
      pet.master.exists(owner => g.target.flatMap(w.actor).exists(t => t.id == owner || ownedBy(t, owner)))
    case _ => false

  private def ownedBy(a: Actor, owner: ActorId): Boolean = a match
    case m: Monster => m.master.contains(owner)
    case _          => false

  /**
   * 守卫不去找的野怪（GuardKind.huntsWild：卫士、带刀护卫只找 Race ≥ 80 的，鸡鹿羊狼练功师不找；
   * 弓箭守卫不找鸡鹿羊）；它们先动手打了守卫（记仇）照样还手。宝宝归 guardVsPet 管。
   */
  private def ignoredByGuard(g: Actor, a: Actor): Boolean = (g, a) match
    case (gm: Monster, am: Monster) if am.master.isEmpty =>
      gm.defn.guard.exists(k => !k.huntsWild(am.defn.race)) && !grudge(g, a)
    case _ => false
