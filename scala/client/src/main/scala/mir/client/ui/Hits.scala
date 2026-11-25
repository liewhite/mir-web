package mir.client.ui

import scala.collection.mutable

/**
 * 一个可点的区域。img：原版按钮图号（按下时画、always 时平时也画），自己画的按钮为空。
 */
final case class Button(
    rect: Rect,
    onClick: () => Unit,
    img: Option[Int] = None,
    always: Boolean = false,
    onHold: Option[() => Unit] = None,
    sound: ClickSound = ClickSound.Glass
)

/** 点下去出什么声：一般按钮是原版玻璃按钮声；物品格不出按钮声，由处理点击的地方按物品播（原版 ItemClickSound）。 */
enum ClickSound:
  case Glass, ItemSlot

object Button:
  /** 按住超过这么久（毫秒）再松开算长按：有 onHold 的按钮做 onHold（技能环长按看说明），不做 onClick */
  val HoldMs = 450.0

/**
 * 每帧重建的点击登记表：界面占用区域（点在里面不传给地图）、按钮和摇杆区（按下就地起摇杆，#1278）。
 * 登记顺序就是叠放顺序（后登记的在上层），被上层窗口盖住的按钮、摇杆区点不到。
 */
final class Hits:
  private val areas   = mutable.ArrayBuffer.empty[(Rect, Int)]
  private val buttons = mutable.ArrayBuffer.empty[(Button, Int)]
  private val sticks  = mutable.ArrayBuffer.empty[(Rect, Int)]
  private var z       = 0

  def reset(): Unit =
    areas.clear()
    buttons.clear()
    sticks.clear()
    z = 0

  def area(r: Rect): Unit =
    areas += r -> z
    z += 1

  def button(b: Button): Unit =
    buttons += b -> z
    z += 1

  /** 摇杆区：也算界面占用（不传给地图），按下就地起摇杆。 */
  def stick(r: Rect): Unit =
    sticks += r -> z
    areas += r -> z
    z += 1

  /** (x, y) 最上层是摇杆区（没被上层的窗口、按钮盖住）。 */
  def stickAt(x: Double, y: Double): Boolean =
    sticks.reverseIterator.find(_._1.contains(x, y)).exists { (_, sz) =>
      !coveredAbove(sz, x, y) && !buttons.exists((b, bz) => bz > sz && b.rect.contains(x, y))
    }

  /** (x, y) 在界面上（不传给地图）。 */
  def covers(x: Double, y: Double): Boolean = areas.exists(_._1.contains(x, y))

  /**
   * 点到的按钮（上层优先）。slop > 0 时（触屏）没正好点中的小按钮允许偏出这么多像素，
   * 但大块的点击区（技能键、小地图、商品行）不放宽，免得抢了旁边的点击。
   */
  def buttonAt(x: Double, y: Double, slop: Double): Option[Button] =
    buttons.reverseIterator.find(_._1.rect.contains(x, y)) match
      case Some((b, bz)) => Option.unless(coveredAbove(bz, x, y))(b)
      case None =>
        Option.when(slop > 0)(()).flatMap { _ =>
          buttons.reverseIterator
            .filter((b, bz) => b.rect.w * b.rect.h <= 40 * 40 && b.rect.distTo(x, y) < slop && !coveredAbove(bz, x, y))
            .minByOption(_._1.rect.distTo(x, y))
            .map(_._1)
        }

  private def coveredAbove(z: Int, x: Double, y: Double): Boolean = areas.exists((r, rz) => rz > z && r.contains(x, y))
