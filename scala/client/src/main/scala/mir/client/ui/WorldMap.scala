package mir.client.ui

import mir.client.game.Intent
import mir.client.input.Pt
import mir.core.*
import mir.data.RouteDef
import mir.protocol.Look

/**
 * 全景地图（#1195，原版没有，原版只有右上角的局部小地图）：整张小地图图片等比缩放摆满屏幕，标出自己、正在去的路、
 * 商人（带名字）、出口（去哪张图）、视野里的怪和人；点图上哪儿就自动寻路走过去并关掉窗口。点右上角小地图或按 M 开关。
 */
final class WorldMap(ui: ClassicUi) extends ToggleWindow:
  import WorldMap.*
  import ui.{catalog, g}

  def draw(m: Me): Unit =
    val s   = ui.ctlScale
    val box = Rect(ui.safe.left, ui.safe.top, ui.W - ui.safe.left - ui.safe.right, ui.H - ui.safe.top - ui.safe.bottom)
    ui.area(box)
    g.rect(box.x, box.y, box.w, box.h, "rgba(0,0,0,.88)")
    val head  = Head * s
    val space = Rect(box.x + Pad, box.y + head, box.w - Pad * 2, box.h - head - Pad)
    val title = m.view.map.flatMap(catalog.map).fold("地图")(_.title)
    label(title, box.x + Pad, box.y + (head - 14 * s) / 2, "#ffe08a")
    m.view.map.flatMap(id => image(id).map(id -> _)) match
      case None =>
        val t = "此地图没有小地图"
        label(t, space.x + (space.w - g.width(t) * s) / 2, space.y + space.h / 2 - 7 * s, "#aaa")
      case Some((id, (idx, w, h))) =>
        val fit = Fit(w, h, space, MaxScale)
        g.scaled(fit.x, fit.y, fit.scale)(g.img("mmap", idx, 0, 0))
        ui.hit(fit.rect)(fit.toCell(ui.mouse).foreach { c =>
          ui.ctl.goTo(c)
          close()
        })
        drawMarks(m, id, fit, s)
    val closeBtn = Rect(box.x + box.w - Pad - 56 * s, box.y + (head - 30 * s) / 2, 56 * s, 30 * s)
    g.frame(closeBtn, if ui.isPressed(closeBtn) then "#7a5a2a" else "#3a2a16", "#c9a060")
    label("关闭", closeBtn.x + (closeBtn.w - g.width("关闭") * s) / 2, closeBtn.y + (closeBtn.h - 14 * s) / 2, "#ffe08a")
    ui.button(Button(closeBtn, () => close()))

  /** 这张图的小地图：图号、宽、高。 */
  private def image(id: MapId): Option[(Int, Int, Int)] =
    for
      n <- catalog.data.minimaps.get(id)
      sp <- g.sprite("mmap", n - 1)
    yield (n - 1, sp.w, sp.h)

  /** 界面字号随触屏倍数放大。 */
  private def label(t: String, x: Double, y: Double, color: String): Unit =
    g.scaled(x, y, ui.ctlScale)(g.text(t, 0, 0, color, bold = true))

  private def drawMarks(m: Me, id: MapId, fit: Fit, s: Double): Unit =
    def dot(c: Cell, color: String, r: Double): Unit =
      val p = fit.toScreen(c)
      g.circle(p.x, p.y, r * s, color, Some("#000"))
    // 正在去的路（黄线）和终点
    ui.ctl.intent match
      case Intent.GoTo(route) =>
        val ctx = g.ctx
        ctx.beginPath()
        (m.actor.cell +: route.cells).map(fit.toScreen).zipWithIndex.foreach((p, i) => if i == 0 then ctx.moveTo(p.x, p.y) else ctx.lineTo(p.x, p.y))
        ctx.strokeStyle = "rgba(255,230,0,.8)"
        ctx.lineWidth = 2 * s
        ctx.stroke()
        dot(route.dest, "#ff0", 4)
      case _ => ()
    // 出口和商人：先画点，名字按先后摆、摆不下（和已有的重叠）就不写
    val gates = exits(catalog.routesByMap.getOrElse(id, Nil))
    val shops = catalog.merchantsByMap.getOrElse(id, Nil)
    gates.foreach(e => dot(e.at, ExitColor, 3.5))
    shops.foreach(d => dot(d.at, NpcColor, 3))
    def named(e: Exit) = catalog.map(e.to).map(d => (e, d.title, d.width * d.height >= BigMapCells))
    val exitNames                 = gates.flatMap(named)
    val (bigExits, roomExits)     = exitNames.partition(_._3)
    def mark(c: Cell, t: String, color: String) = Mark(fit.toScreen(c), t, color)
    val marks =
      bigExits.map((e, t, _) => mark(e.at, s"→$t", ExitColor)) ++
        shops.map(d => mark(d.at, d.name, NpcColor)) ++
        roomExits.map((e, t, _) => mark(e.at, t, ExitColor))
    placeLabels(marks, fit.rect, t => g.width(t) * s, 14 * s, 6 * s).foreach((mk, r) => label(mk.text, r.x, r.y, mk.color))
    // 视野里的怪和人
    val me = m.actor.cell
    ui.client.actors.foreach { a =>
      if !a.dead && a.id != m.actor.id then
        a.look match
          case Look.Npc(_)                    => ()
          case Look.Monster(_, _, Some(_), _) => dot(a.cell, "#6cf", 2.5)
          case _: Look.Human                  => dot(a.cell, "#39f", 3)
          case _                              => dot(a.cell, "#f33", 2.5)
    }
    if (scalajs.js.Date.now() / 300).toLong % 2 == 0 then dot(me, "#fff", 4.5)
    else dot(me, "#fff", 3.5)

object WorldMap:
  /** 标题栏高（未放大） */
  val Head        = 40.0
  val Pad         = 8.0
  /** 小图（洞穴）最多放大几倍 */
  val MaxScale    = 3.0
  /** 去这么大（格数）以上的图的出口名字优先写（进屋的门最后写） */
  val BigMapCells = 100 * 100
  /** 同一张图的出口格离得这么近（格）算同一个门 */
  val GateMerge   = 6
  val ExitColor   = "#f9a"
  val NpcColor    = "#3f3"

  /** 小地图图片里一格横 1.5 像素、竖 1 像素（原版 DrawMiniMap 的比例）。 */
  val PxPerCellX = 1.5
  val PxPerCellY = 1.0

  /** 图片（w×h 像素）等比缩放进 space 居中，放大不超过 maxScale。 */
  final case class Fit(x: Double, y: Double, scale: Double, w: Int, h: Int):
    def rect: Rect = Rect(x, y, w * scale, h * scale)
    /** 格子中心在屏幕上的位置 */
    def toScreen(c: Cell): Pt = Pt(x + (c.x + 0.5) * PxPerCellX * scale, y + (c.y + 0.5) * PxPerCellY * scale)
    /** 屏幕上一点是哪一格；不在图上为 None */
    def toCell(p: Pt): Option[Cell] =
      Option.when(rect.contains(p.x, p.y))(
        Cell(math.floor((p.x - x) / scale / PxPerCellX).toInt, math.floor((p.y - y) / scale / PxPerCellY).toInt)
      )

  object Fit:
    def apply(w: Int, h: Int, space: Rect, maxScale: Double): Fit =
      val k = math.min(maxScale, math.min(space.w / w, space.h / h))
      Fit(space.x + (space.w - w * k) / 2, space.y + (space.h - h * k) / 2, k, w, h)

  /** 一个出口（门）：站哪格、去哪张图。 */
  final case class Exit(at: Cell, to: MapId)

  /** 通道格合并成门：去同一张图、彼此不远的格子算一个门（取第一格）。 */
  def exits(routes: List[RouteDef]): List[Exit] =
    routes.foldLeft(Vector.empty[Exit]) { (acc, r) =>
      if acc.exists(e => e.to == r.to && e.at.chebyshev(r.at) <= GateMerge) then acc else acc :+ Exit(r.at, r.to)
    }.toList

  /** 图上一个要写名字的点：屏幕位置、文字、颜色。 */
  final case class Mark(at: Pt, text: String, color: String)

  /**
   * 摆名字：写在点的右边（出了图就写左边），按先后摆，和已摆的重叠或出了 bounds 就不写（城里门和商人挤在一起时只写前面的）。
   */
  def placeLabels(marks: List[Mark], bounds: Rect, width: String => Double, lineH: Double, gap: Double): List[(Mark, Rect)] =
    def overlaps(a: Rect, b: Rect) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
    def inside(r: Rect)            = r.x >= bounds.x && r.y >= bounds.y && r.x + r.w <= bounds.x + bounds.w && r.y + r.h <= bounds.y + bounds.h
    marks.foldLeft(Vector.empty[(Mark, Rect)]) { (placed, mk) =>
      val w     = width(mk.text)
      val right = Rect(mk.at.x + gap, mk.at.y - lineH / 2, w, lineH)
      val left  = right.copy(x = mk.at.x - gap - w)
      List(right, left).find(r => inside(r) && !placed.exists(p => overlaps(p._2, r))) match
        case Some(r) => placed :+ (mk -> r)
        case None    => placed
    }.toList
