package mir.client.render

import mir.core.*
import mir.protocol.EffectView

/** 头上飘的字：伤害、MISS、回血。born 是本地时钟。 */
final case class Floater(actor: ActorId, text: String, tone: FloatTone, born: Double)

enum FloatTone(val color: String):
  case HurtMe    extends FloatTone("#ff6b6b")
  case HurtOther extends FloatTone("#ffd166")
  case Miss      extends FloatTone("#bbbbbb")
  case Heal      extends FloatTone("#7bed9f")

/** 说话气泡（别的玩家、自己说的话在头上显示几秒）。 */
final case class Bubble(actor: ActorId, text: String, born: Double)

/**
 * 一次性的画面：特效、飘字、说话气泡。服务端只在发生时通知一次（不进视野副本），在这里留到放完。
 * 特效按服务器时刻算（和动作同一个时钟），飘字和气泡按本地时刻。
 */
final case class Transients(
    effects: Vector[EffectView] = Vector.empty,
    floaters: Vector[Floater] = Vector.empty,
    bubbles: Vector[Bubble] = Vector.empty
):
  import Transients.*

  def effect(e: EffectView): Transients = copy(effects = effects :+ e)
  def float(f: Floater): Transients     = copy(floaters = floaters :+ f)
  /** 同一个人新说的话替换旧的 */
  def say(b: Bubble): Transients = copy(bubbles = bubbles.filterNot(_.actor == b.actor) :+ b)

  /** 去掉放完的。 */
  def prune(server: Millis, local: Double): Transients =
    Transients(
      effects.filter(e => server - e.startedAt < effectLife(e)),
      floaters.filter(f => local - f.born < FloatMs),
      bubbles.filter(b => local - b.born < BubbleMs)
    )

  /** 换图：全部清掉。 */
  def cleared: Transients = Transients()

object Transients:
  val FloatMs  = 1100.0
  val BubbleMs = 4000.0

  /** 特效放多久：服务端给的时长（与 TS 版一致，飞行特效在时长内循环放帧）。 */
  def effectLife(e: EffectView): Long = e.duration.toLong
