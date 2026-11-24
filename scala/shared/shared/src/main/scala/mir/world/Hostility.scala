package mir.world

import mir.protocol.Look
import mir.rules.Side

/** 敌我：谁能打谁（按外观判断，规则和客户端共用 Look.hostile），以及给怪找最近的敌人。 */
final class Hostility(w: World):
  /**
   * 本次 tick 开始时不是野怪的对象（玩家、宝宝、守卫）。野怪只会把它们当敌人，找敌人时只看这些，
   * 不用每只怪都把全图上千只怪扫一遍。发狂的怪不算：野怪不会主动去找它，只在挨了它的打时还手。
   */
  private var fighters: Vector[Actor] = Vector.empty

  private def side(a: Actor): Option[Side] = a.look.side(w.catalog.isGuard)

  /** 每次 tick 开头重新收一遍 fighters。 */
  private[world] def refresh(): Unit =
    fighters = w.eachActor.filter(a => side(a).exists(s => s != Side.Wild && s != Side.Crazy)).toVector

  /**
   * a 能不能打 b（自动找目标、范围攻击都按它）：都活着、不是同一个、外观上敌对（不同阵营，埋着的、石像打不到）；
   * 守卫和玩家平时不互打，但守卫被谁打了，Grudge.Ms 内把他当敌人（原版 GuardUnit.IsProperTarget 认 LastHiter）。
   */
  def isEnemy(a: Actor, b: Actor): Boolean =
    alive(a, b) && (Look.hostile(a.look, b.look, w.catalog.isGuard) || grudge(a, b))

  /** a 能不能亲手打选中的 b：敌人，或者是玩家打守卫（Look.attackable）。所有攻击最终都过这一关（Vitals.strike）。 */
  def canAttack(a: Actor, b: Actor): Boolean =
    isEnemy(a, b) || (alive(a, b) && Look.attackable(a.look, b.look, w.catalog.isGuard))

  private def alive(a: Actor, b: Actor): Boolean = !a.dead && !b.dead && a.id != b.id

  /** a 是守卫、记着 b 刚打过它 */
  private def grudge(a: Actor, b: Actor): Boolean = a match
    case m: Monster => m.grudge.exists(g => g.by == b.id && w.now - g.at <= Grudge.Ms)
    case _          => false

  /** range 格以内最近的敌人（怪看不见隐身的，除非能看穿隐身）。 */
  private[world] def nearestEnemy(m: Actor, range: Int): Option[Actor] =
    val seesInvisible = m match
      case mon: Monster => mon.defn.coolEye > 0
      case _            => true
    val candidates = if side(m).contains(Side.Wild) then fighters.iterator.filter(w.present) else w.eachActor
    candidates
      .filter(a => isEnemy(m, a) && (seesInvisible || !a.statuses.has(StatusKind.Invisible)) && !ignoredByGuard(m, a))
      .map(a => a -> m.cell.chebyshev(a.cell))
      .filter(_._2 <= range)
      .minByOption(_._2)
      .map(_._1)

  /**
   * 守卫不去找的怪：不主动打人的鸡、鹿、羊（Race ≤ 52，原版 GuardUnit.CanPassiveAttack）；
   * 它们先动手打了守卫（记仇）照样还手。
   */
  private def ignoredByGuard(g: Actor, a: Actor): Boolean = (g, a) match
    case (gm: Monster, am: Monster) if gm.isGuard && am.master.isEmpty => am.defn.race <= 52 && !grudge(g, a)
    case _                                                            => false
