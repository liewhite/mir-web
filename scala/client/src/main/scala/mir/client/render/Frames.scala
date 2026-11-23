package mir.client.render

import mir.core.*
import mir.data.{ActionInfo, MonsterActionTable, MonsterLook}
import mir.model.{ActKind, Cover, HitStyle, Sex}
import mir.protocol.ActionView

/** 动作 → 图库帧号，动作表照原版客户端 Actor.pas（HA、MA*），照 TS 版 render/frames.ts。 */
object Frames:
  /** 人物动作表 HA（每套衣服 600 帧）。 */
  object HA:
    val stand    = ActionInfo(0, 4, 4, 200, 0)
    val walk     = ActionInfo(64, 6, 2, 90, 2)
    val run      = ActionInfo(128, 6, 2, 120, 3)
    val hit      = ActionInfo(200, 6, 2, 85, 0)
    val heavyHit = ActionInfo(264, 6, 2, 90, 0)
    val bigHit   = ActionInfo(328, 8, 0, 70, 0)
    val spell    = ActionInfo(392, 6, 2, 60, 0)
    val struck   = ActionInfo(472, 3, 5, 70, 0)
    val die      = ActionInfo(536, 4, 4, 120, 0)

  val HumanFrames = 600

  def finished(a: ActionView, now: Millis): Boolean = now - a.startedAt >= a.duration

  /** 动作的第几帧：循环动作按每帧时长循环，一次性动作按动作时长摊到各帧。 */
  private def frameOf(info: ActionInfo, a: ActionView, now: Millis, loop: Boolean): Int =
    if info.frames <= 1 then 0
    else
      val elapsed = math.max(0L, now - a.startedAt)
      if loop then ((elapsed / info.frameTime) % info.frames).toInt
      else
        val per = if a.duration > 0 then a.duration.toDouble / info.frames else info.frameTime.toDouble
        math.min(info.frames - 1, (elapsed / per).toInt)

  private def pick(info: ActionInfo, dir: Int, f: Int): Int = info.start + dir * (info.frames + info.skip) + f

  private def once(info: ActionInfo, a: ActionView, dir: Dir, now: Millis): Int = pick(info, dir.code, frameOf(info, a, now, loop = false))
  private def looped(info: ActionInfo, a: ActionView, dir: Int, now: Millis): Int = pick(info, dir, frameOf(info, a, now, loop = true))

  /** 野蛮冲撞：每冲一格放半圈跑步帧，左右脚轮流（原版 ActRushLeft / ActRushRight）。 */
  private def rush(a: ActionView, dir: Dir, now: Millis): Int =
    val per = mir.rules.Pace.RushStepMs / 3.0
    pick(HA.run, dir.code, ((math.max(0L, now - a.startedAt) / per).toInt) % HA.run.frames)

  /** 被推倒退：走路帧倒着放（原版 SM_BACKSTEP）。 */
  private def backstep(info: ActionInfo, a: ActionView, dir: Dir, now: Millis): Int =
    pick(info, dir.code, info.frames - 1 - frameOf(info, a, now, loop = false))

  /** 人物当前帧（0..599）。 */
  def human(a: ActionView, dir: Dir, now: Millis, dead: Boolean): Int =
    if dead && (a.kind != ActKind.Die || finished(a, now)) then pick(HA.die, dir.code, HA.die.frames - 1)
    else if finished(a, now) && a.kind != ActKind.Die then looped(HA.stand, a, dir.code, now)
    else
      a.kind match
        case ActKind.Walk   => once(HA.walk, a, dir, now)
        case ActKind.Run    => once(HA.run, a, dir, now)
        case ActKind.Rush   => rush(a, dir, now)
        case ActKind.Backstep => backstep(HA.walk, a, dir, now)
        case ActKind.Attack => once(HA.hit, a, dir, now)
        case ActKind.Heavy =>
          a.hit match
            case Some(HitStyle.Wide | HitStyle.Fire) => once(HA.bigHit, a, dir, now)
            case _                                   => once(HA.heavyHit, a, dir, now)
        case ActKind.Spell                => once(HA.spell, a, dir, now)
        case ActKind.Struck               => once(HA.struck, a, dir, now)
        case ActKind.Die | ActKind.Dead   => once(HA.die, a, dir, now)
        case ActKind.Stand | ActKind.DigUp | ActKind.DigDown => looped(HA.stand, a, dir.code, now)

  /** 剑术刀光（原版 HitEffectBase）：攻杀、刺杀、半月、烈火在 magic 库的起始帧。 */
  def swordLight(style: HitStyle): Option[Int] = style match
    case HitStyle.Power  => Some(800)
    case HitStyle.Long   => Some(1410)
    case HitStyle.Wide   => Some(1700)
    case HitStyle.Fire   => Some(3390)
    case HitStyle.Normal => None

  /** 怪物当前帧（相对该怪的起始帧），画法差别按外观查 MonsterLook（和服务端共用）。石像状态（祖玛雕像没醒时）停在 ActDeath 第一帧。 */
  def monster(t: MonsterActionTable, style: MonsterLook, a: ActionView, dir: Dir, now: Millis, dead: Boolean, cover: Cover): Int =
    val deathDir = if style.deathNoDir then 0 else dir.code
    def stand    = looped(t.stand, a, if style.herb then 0 else dir.code, now)
    if dead then
      if a.kind == ActKind.Die && !finished(a, now) then once(t.die, a, dir, now)
      else pick(t.die, dir.code, t.die.frames - 1)
    else if cover == Cover.Stone then pick(t.death, deathDir, 0)
    else if finished(a, now) then stand
    else
      a.kind match
        case ActKind.Walk | ActKind.Run | ActKind.Rush =>
          if t.walk.frames > 0 then once(t.walk, a, dir, now) else pick(t.stand, dir.code, 0)
        case ActKind.Backstep =>
          if t.walk.frames > 0 then backstep(t.walk, a, dir, now) else pick(t.stand, dir.code, 0)
        case ActKind.Attack | ActKind.Heavy | ActKind.Spell =>
          once(style.attackAction(t), a, dir, now)
        case ActKind.Struck => if t.struck.frames > 0 then once(t.struck, a, dir, now) else stand
        case ActKind.DigUp =>
          if style.herb then pick(t.walk, 0, frameOf(t.walk, a, now, loop = false))
          else pick(t.death, deathDir, frameOf(t.death, a, now, loop = false))
        case ActKind.DigDown => pick(t.death, if style.herb then 0 else deathDir, frameOf(t.death, a, now, loop = false))
        case ActKind.Stand | ActKind.Die | ActKind.Dead => stand

  /** 武器画在身体前还是后（原版 WORDER 表，按性别、帧号）。 */
  private val WOrder: Vector[String] = Vector(
    "000000001111111111111111111111110000000000001111000011110000111100000000111111111111111111111111000000000000000100000001000000010000000011111111111111111111111100111111001110010000000100000001011100001110001111100000111000001111111111111111111000000000000011110011011000110110001111100000111001111111111101111111000111000111101111010000110000001111100011001000111000010110000000001110111110000000001100000011000000111000011111111111011111110011001100010011001011111100010000011111111111111111111111111111000111110001111100011111000111110011111101111111111111111111111100011111000111110001111100011111",
    "000000001111111111111111111111110000000000001111000011110000111100000000111111111111111111111111000000000000000100000001000000010000000011111111111111111111111100111111001110010000000100000001111100001110001111100000111000001111111111111111111000000000000011110011011000110110001111100000111001111111111101111111000111000111101111010000110000001111100011001000111000010110000000001110111110000000001100000011000000111000011111111111011111110011001100010011001011111100010000011111111111111111111111111111000111110001111100011111000111110011111101111111111111111111111100011111000111110001111100011111"
  )

  def weaponInFront(sex: Sex, frame: Int): Boolean =
    val row = WOrder(sex.code)
    frame >= 0 && frame < row.length && row.charAt(frame) == '1'

  /** 金币在地上的图：按数量换图（原版 GetGoldLooks）。 */
  def goldLook(n: Int): Int =
    if n >= 1000 then 116 else if n >= 500 then 115 else if n >= 300 then 114 else if n >= 100 then 113 else 112

  /** NPC 外观 → npc 库起始帧（原版 GetNpcOffset）。 */
  def npcOffset(appr: Int): Int = appr match
    case a if a <= 22                     => a * 60
    case 23                               => 1380
    case 24 | 25                          => (appr - 24) * 60 + 1470
    case 27 | 32                          => (appr - 26) * 60 + 1620 - 30
    case a if a == 26 || (a >= 28 && a <= 41) => (a - 26) * 60 + 1620
    case 42 | 43                          => 2580
    case a if a >= 44 && a <= 47          => 2640
    case a if a >= 48 && a <= 50          => (a - 48) * 60 + 2700
    case 51                               => 2880
    case 52                               => 2960
    case _                                => 0
