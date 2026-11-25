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
  /** 极品（与地上极品名字同色） */
  case Special extends Tint(mir.client.render.Renderer.SpecialItemColor)
  /** 比身上的强（与地上提升装备名字同色，#1200） */
  case Upgrade extends Tint(mir.client.render.Renderer.UpgradeItemColor)

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

  /** 物品格：图标居中，叠了几个时右上角黄字写数量（#1159）。 */
  def item(look: Int, count: Int, r: Rect): Unit =
    imgCenter("items", look, r)
    if count > 1 then
      val s = count.toString
      text(s, r.x + r.w - width(s) - 2, r.y + 1, "#ffe08a", bold = true)

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

  /** 齿轮（实心，中间挖空）：圆心 (cx, cy)、齿尖半径 r，8 个齿。 */
  def gear(cx: Double, cy: Double, r: Double, color: String): Unit =
    val teeth = 8
    val inner = r * 0.72
    ctx.beginPath()
    (0 until teeth * 4).foreach { k =>
      // 每个齿分 4 段：齿顶两点在外圈，齿根两点在内圈
      val a   = (k.toDouble / (teeth * 4) - 0.0625 / 2) * math.Pi * 2
      val rad = if k % 4 < 2 then r else inner
      val (x, y) = (cx + rad * math.cos(a), cy + rad * math.sin(a))
      if k == 0 then ctx.moveTo(x, y) else ctx.lineTo(x, y)
    }
    ctx.closePath()
    ctx.moveTo(cx + r * 0.32, cy)
    ctx.arc(cx, cy, r * 0.32, 0, math.Pi * 2, counterclockwise = true)
    ctx.fillStyle = color
    ctx.fill()

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
  def hint(all: List[Line], x: Double, y: Double, maxW: Double, maxH: Double): Unit =
    val lines = wrap(all, math.min(360, maxW - 14))
    if lines.nonEmpty then
      val w  = lines.map(l => width(l.text)).max + 10
      val h  = lines.size * 14 + 6
      val hx = math.min(x, maxW - w - 2)
      val hy = math.min(y, maxH - h - 2)
      rect(hx, hy, w, h, "rgba(0,0,0,.85)")
      lines.zipWithIndex.foreach((l, i) => text(l.text, hx + 5, hy + 3 + i * 14, l.tint.color))

  /** 超过 maxW 像素宽的行折开（尽量断在标点后，见 Gfx.breakText），续行缩进一个全角空格、颜色不变。 */
  def wrap(lines: List[Line], maxW: Double): List[Line] =
    lines.flatMap(l => breakText(l.text, maxW, width).map(t => l.copy(text = t)))

  /** 在 (x, y) 处放大 s 倍画（原版底栏在手机上缩得很小，自己画的面板按触屏倍数放大）。 */
  def scaled(x: Double, y: Double, s: Double)(draw: => Unit): Unit =
    ctx.save()
    ctx.translate(x, y)
    ctx.scale(s, s)
    draw
    ctx.restore()

object Gfx:
  /** 这些字后面可以断行（#1295：折行别把「60%」「1/10」这种拆开，也别让下一行以标点开头） */
  private val BreakAfter = "，、；：。）」".toSet

  /**
   * 把一行字折成每行不超过 maxW 宽：先按字塞满一行，再往回找最近的断点（全角标点后；不断在空格，那样会把「威力 18-55」拆开）；
   * 断点在这一行后半段才用，不然一行太短，就照字硬断。续行开头加一个全角空格缩进。
   */
  def breakText(text: String, maxW: Double, width: String => Double): List[String] =
    @scala.annotation.tailrec
    def go(rest: String, indent: String, acc: List[String]): List[String] =
      val s = indent + rest
      if rest.isEmpty && acc.nonEmpty then acc.reverse
      else if width(s) <= maxW then (s :: acc).reverse
      else
        val start = indent.length + 1
        val fit   = (start + 1 to s.length).takeWhile(i => width(s.take(i)) <= maxW).lastOption.getOrElse(start)
        val soft  = (fit until math.max(start, (fit + indent.length) / 2) by -1).find(i => BreakAfter(s(i - 1)))
        val cut   = soft.getOrElse(fit)
        go(s.drop(cut), "　", s.take(cut) :: acc)
    go(text, "", Nil)

  val Font = "12px \"SimSun\",\"宋体\",\"Songti SC\",\"STSong\",\"PingFang SC\",serif"
  /** 原版 g_WMainImages */
  val Main = "prguse"

  /** 金币显示：1,234,567 */
  def gold(n: Long): String = n.toString.reverse.grouped(3).mkString(",").reverse
