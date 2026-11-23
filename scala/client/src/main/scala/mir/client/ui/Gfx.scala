package mir.client.ui

import mir.client.assets.{Sprite, SpriteStore}
import org.scalajs.dom

/** 界面上的矩形（界面像素）。 */
final case class Rect(x: Double, y: Double, w: Double, h: Double):
  def contains(px: Double, py: Double): Boolean = px >= x && py >= y && px < x + w && py < y + h
  /** 点到矩形的距离（在里面是 0） */
  def distTo(px: Double, py: Double): Double =
    math.hypot(math.max(math.max(x - px, 0), px - (x + w)), math.max(math.max(y - py, 0), py - (y + h)))
  def moved(dx: Double, dy: Double): Rect = copy(x = x + dx, y = y + dy)

/** 说明文字一行的颜色：普通、标题、好、坏、暗、金。 */
enum Tint(val color: String):
  case Plain extends Tint("#fff")
  case Title extends Tint("#ff0")
  case Good  extends Tint("#7cff7c")
  case Bad   extends Tint("#f55")
  case Dim   extends Tint("#aaa")
  case Gold  extends Tint("#ffd96b")

final case class Line(text: String, tint: Tint = Tint.Plain)

/**
 * 原版界面的绘图小工具（照 TS 版 ui/classic/gfx.ts）：按图库帧号画图（界面图按左上角摆，不带偏移），画 12 像素宋体字。
 */
final class Gfx(val ctx: dom.CanvasRenderingContext2D, sprites: SpriteStore):
  import Gfx.*

  def sprite(lib: String, idx: Int): Option[Sprite] = sprites.sprite(lib, idx)

  def size(lib: String, idx: Int): Option[(Int, Int)] = sprite(lib, idx).map(s => (s.w, s.h))

  /** 按左上角画（界面图）。 */
  def img(lib: String, idx: Int, x: Double, y: Double): Unit =
    sprite(lib, idx).foreach(s => ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, math.round(x).toDouble, math.round(y).toDouble, s.w, s.h))

  /** 画图的一部分（血球、经验条按比例裁剪）。 */
  def imgPart(lib: String, idx: Int, x: Double, y: Double, rx: Double, ry: Double, rw: Double, rh: Double): Unit =
    if rw > 0 && rh > 0 then
      sprite(lib, idx).foreach(s => ctx.drawImage(s.img, s.sx + rx, s.sy + ry, rw, rh, math.round(x).toDouble, math.round(y).toDouble, rw, rh))

  /** 居中画在一个矩形里（物品图标）。 */
  def imgCenter(lib: String, idx: Int, r: Rect): Unit =
    sprite(lib, idx).foreach { s =>
      ctx.drawImage(s.img, s.sx, s.sy, s.w, s.h, math.round(r.x + (r.w - s.w) / 2).toDouble, math.round(r.y + (r.h - s.h) / 2).toDouble, s.w, s.h)
    }

  def circle(cx: Double, cy: Double, r: Double, fill: String, stroke: Option[String] = None): Unit =
    ctx.beginPath()
    ctx.arc(cx, cy, r, 0, math.Pi * 2)
    ctx.fillStyle = fill
    ctx.fill()
    stroke.foreach { s =>
      ctx.strokeStyle = s
      ctx.lineWidth = 1.5
      ctx.stroke()
    }

  /** 描边矩形（自己画的按钮、面板）。 */
  def frame(r: Rect, fill: String, stroke: String): Unit =
    ctx.fillStyle = fill
    ctx.fillRect(r.x, r.y, r.w, r.h)
    ctx.strokeStyle = stroke
    ctx.lineWidth = 1
    ctx.strokeRect(math.round(r.x) + 0.5, math.round(r.y) + 0.5, math.round(r.w) - 1.0, math.round(r.h) - 1.0)

  /** 写字并返回宽度（接着往右写用）。 */
  def textW(s: String, x: Double, y: Double, color: String = "#fff", bold: Boolean = false): Double =
    text(s, x, y, color, bold)
    ctx.measureText(s).width

  /** 写字（左上角对齐），bold 是原版 BoldTextOut 的黑边。 */
  def text(s: String, x: Double, y: Double, color: String = "#fff", bold: Boolean = false): Unit =
    ctx.font = Font
    ctx.textAlign = "left"
    ctx.textBaseline = "top"
    if bold then
      ctx.fillStyle = "#000"
      List((-1, 0), (1, 0), (0, -1), (0, 1)).foreach((dx, dy) => ctx.fillText(s, x + dx, y + dy))
    ctx.fillStyle = color
    ctx.fillText(s, x, y)

  def width(s: String): Double =
    ctx.font = Font
    ctx.measureText(s).width

  def rect(x: Double, y: Double, w: Double, h: Double, color: String): Unit =
    ctx.fillStyle = color
    ctx.fillRect(x, y, w, h)

  /** 原版 ShowHint 风格的黑底说明框，摆在 (x, y)，超出界面就往回挪。 */
  def hint(lines: List[Line], x: Double, y: Double, maxW: Double, maxH: Double): Unit =
    if lines.nonEmpty then
      val w  = lines.map(l => width(l.text)).max + 10
      val h  = lines.size * 14 + 6
      val hx = math.min(x, maxW - w - 2)
      val hy = math.min(y, maxH - h - 2)
      rect(hx, hy, w, h, "rgba(0,0,0,.85)")
      lines.zipWithIndex.foreach((l, i) => text(l.text, hx + 5, hy + 3 + i * 14, l.tint.color))

  /** 在 (x, y) 处放大 s 倍画（原版底栏在手机上缩得很小，自己画的面板按触屏倍数放大）。 */
  def scaled(x: Double, y: Double, s: Double)(draw: => Unit): Unit =
    ctx.save()
    ctx.translate(x, y)
    ctx.scale(s, s)
    draw
    ctx.restore()

object Gfx:
  val Font = "12px \"SimSun\",\"宋体\",\"Songti SC\",\"STSong\",\"PingFang SC\",serif"
  /** 原版 g_WMainImages */
  val Main = "prguse"

  /** 金币显示：1,234,567 */
  def gold(n: Long): String = n.toString.reverse.grouped(3).mkString(",").reverse
