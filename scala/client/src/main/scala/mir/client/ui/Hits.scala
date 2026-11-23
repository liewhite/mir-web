package mir.client.ui

import scala.collection.mutable

/**
 * 一个可点的区域。img：原版按钮图号（按下时画、always 时平时也画），自己画的按钮为空。
 */
final case class Button(rect: Rect, onClick: () => Unit, img: Option[Int] = None, always: Boolean = false)

/**
 * 每帧重建的点击登记表：界面占用区域（点在里面不传给地图）和按钮。
 * 登记顺序就是叠放顺序（后登记的在上层），被上层窗口盖住的按钮点不到。
 */
final class Hits:
  private val areas   = mutable.ArrayBuffer.empty[(Rect, Int)]
  private val buttons = mutable.ArrayBuffer.empty[(Button, Int)]
  private var z       = 0

  def reset(): Unit =
    areas.clear()
    buttons.clear()
    z = 0

  def area(r: Rect): Unit =
    areas += r -> z
    z += 1

  def button(b: Button): Unit =
    buttons += b -> z
    z += 1

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
