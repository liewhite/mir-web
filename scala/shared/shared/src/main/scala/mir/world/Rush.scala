package mir.world

import scala.annotation.tailrec

import mir.core.*
import mir.model.*
import mir.protocol.Tone
import mir.rules.{Rng, Rules, SpellRules}

/**
 * 战士野蛮冲撞（技能 27）：朝一个方向冲 max(2, 等级+1) 格。
 * 照 ref/Mir176 PlayObject.Base.cs DoMotaebo（冲撞）和 BaseObject.CharPushed（被推）。
 *
 * - 面前有人：推得动（比自己等级低、过成功率，宝宝和守卫等打不得的、钉在地上的推不动）就推它一格、自己跟进一格，
 *   推不动就停下；3 级时先把再后面一格的也推开。最后被推的那个挨一下：推着它走的格数越少越疼。
 * - 面前没人：一直往前冲，撞到别人就停；撞到墙停下，还剩冲劲时自己挨一下。
 * - 一步都没冲出去（推不动、贴墙）提示「冲撞力不够」。
 */
private[world] object Rush:
  import World.*

  /** 冲完的结果：自己冲了几格；算不算成功（原版只有推着对象冲出去才算，加熟练度）。 */
  final case class Result(moved: Int, succeeded: Boolean)

  def apply(w: World, p: Player, dir: Dir, level: Int)(using rng: Rng): Result =
    w.face(p, dir)
    val start = p.cell
    val steps = SpellRules.rushSteps(level)

    def pushable(a: Actor): Boolean =
      val rooted = a match
        case m: Monster => m.behavior.rooted
        case _          => false
      w.isEnemy(p, a) && !rooted && SpellRules.rushPushes(p.level, Combat.levelOf(a), level)

    /** 面前有人时：每一步先推它、再跟进。返回（推成功几次、自己走了几格、最后推的对象）。 */
    @tailrec def shoveAlong(i: Int, pushes: Int, moved: Int, victim: Option[Actor]): (Int, Int, Option[Actor]) =
      if i >= steps then (pushes, moved, victim)
      else
        w.occupant(p.cell.step(dir)) match
          case None                           => shoveAlong(i + 1, pushes, moved, victim)
          case Some(front) if !pushable(front) => (pushes, moved, victim)
          case Some(front) =>
            if level >= 3 then w.occupant(p.cell.step(dir, 2)).filter(pushable).foreach(w.pushBack(_, dir, 1))
            if w.pushBack(front, dir, 1) != 1 then (pushes, moved, Some(front))
            else shoveAlong(i + 1, pushes + 1, moved + (if w.shove(p, dir) then 1 else 0), Some(front))

    /** 面前没人时：一直冲。返回（走了几格、是不是撞了墙）；撞到别人就停，不算撞墙。 */
    @tailrec def dash(i: Int, moved: Int): (Int, Boolean) =
      if i >= steps then (moved, false)
      else if w.shove(p, dir) then dash(i + 1, moved + 1)
      else (moved, !w.terrain.walkable(p.cell.step(dir)))

    val result = w.occupant(start.step(dir)) match
      case Some(_) =>
        val (pushes, moved, victim) = shoveAlong(0, 0, 0, None)
        victim.foreach { v =>
          val dmg = Rules.afterArmor(SpellRules.rushImpact(level, pushes), v.ac)
          if dmg > 0 then w.applyDamage(v, dmg, Some(p.id)) else w.miss(v)
        }
        if moved == 0 then tooWeak(w, p)
        Result(moved, succeeded = moved > 0)
      case None =>
        val (moved, wall) = dash(0, 0)
        if wall then
          tooWeak(w, p)
          // 原版：冲劲（等级+1 格）没用完就撞墙，自己挨一下（原版 0 级冲满 2 格时计数溢出也会挨，不照搬）
          if moved < level + 1 then
            val dmg = Rules.afterArmor(SpellRules.rushCrash(level), p.ac)
            if dmg > 0 then w.applyDamage(p, dmg, None)
        Result(moved, succeeded = false)

    val dur = math.max(1, result.moved) * RushStepMs
    w.setAction(p, ActKind.Rush, dur, from = Option.when(result.moved > 0)(start))
    p.busyUntil = w.now + dur + RushDizzyMs
    if result.moved > 0 then w.arrived(p)
    result

  private def tooWeak(w: World, p: Player): Unit = w.notice(p, "冲撞力不够!!!", Tone.Bad)
