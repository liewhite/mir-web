package mir.client.input

import mir.core.Dir

/** 界面上的一个点（界面像素）。 */
final case class Pt(x: Double, y: Double)

/** 摇杆推的方向：8 方向之一，推得远就跑。 */
final case class Stick(dir: Dir, run: Boolean)

/**
 * 左半屏虚拟摇杆（用户 #1140）：手指按下的位置就是摇杆中心，拖动方向按 8 方向取整（原版人物只走 8 个方向），
 * 推过半径的 60% 就跑。照 TS 版 game/joystick.ts。
 */
final class Joystick:
  /** 按着摇杆的手指；没有为 None */
  private var finger = Option.empty[Double]
  var base: Pt   = Pt(0, 0)
  var knob: Pt   = Pt(0, 0)
  /** 半径（界面像素），布局按操作件放大倍数设 */
  var radius: Double = Joystick.Radius

  def active: Boolean               = finger.isDefined
  def owns(pointer: Double): Boolean = finger.contains(pointer)

  def start(pointer: Double, at: Pt): Unit =
    finger = Some(pointer)
    base = at
    knob = at

  /** 手指移到 at：返回方向，在死区里为 None。 */
  def move(at: Pt): Option[Stick] =
    val vx = at.x - base.x
    val vy = at.y - base.y
    val d  = math.hypot(vx, vy)
    val k  = if d > radius then radius / d else 1.0
    knob = Pt(base.x + vx * k, base.y + vy * k)
    Option.when(d >= Joystick.Dead)(Stick(Dir.fromVector(vx, vy), d >= radius * Joystick.RunAt))

  def end(): Unit = finger = None

object Joystick:
  val Radius = 56.0
  /** 小于这个距离不动（手指抖动） */
  val Dead = 12.0
  /** 超过半径的这个比例就跑 */
  val RunAt = 0.6
