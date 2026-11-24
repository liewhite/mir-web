package mir.client.ui

import mir.core.*
import mir.protocol.Look

/**
 * 右上角小地图（PlayScn.DrawMiniMap，TS 版 ui/classic/classic.ts）：常驻 HUD，不算窗口；
 * 底栏小按钮或 Tab 在「半透明 → 不透明 → 关」之间切。点小地图打开全景地图（WorldMap，原版没有）。
 */
final class MiniMap(ui: ClassicUi):
  import ui.{catalog, g}

  /** 0 关、1 半透明、2 不透明 */
  private var mode = 1

  def cycle(): Unit = mode = (mode + 1) % 3

  private final case class Mini(idx: Int, src: Rect, x: Double, y: Double)

  private def mini(m: Me): Option[Mini] =
    for
      map <- m.view.map
      idx <- catalog.data.minimaps.get(map)
      s   <- g.sprite("mmap", idx - 1)
    yield
      val c    = m.actor.cell
      val left = math.max(0, math.min(s.w - 120, c.x * 48 / 32 - 60))
      val top  = math.max(0, math.min(s.h - 120, c.y - 60))
      val w    = math.min(120, s.w)
      Mini(idx - 1, Rect(left, top, w, math.min(120, s.h)), ui.W - ui.safe.right - w, ui.safe.top)

  def draw(m: Me): Unit = if mode > 0 then mini(m).foreach { mm =>
    val box = Rect(mm.x, mm.y, mm.src.w, mm.src.h)
    g.ctx.globalAlpha = if mode == 1 then 0.7 else 1
    g.imgPart("mmap", mm.idx, mm.x, mm.y, mm.src.x, mm.src.y, mm.src.w, mm.src.h)
    g.ctx.globalAlpha = 1
    ui.area(box)
    ui.hit(box)(ui.run(UiAction.WorldMap))
    def dot(c: Cell, color: String, size: Int = 2): Unit =
      val px = mm.x + c.x * 48 / 32 - mm.src.x
      val py = mm.y + c.y - mm.src.y
      if box.contains(px, py) then g.rect(px, py, size, size, color)
    val me = m.actor.cell
    ui.client.actors.foreach { a =>
      if !a.dead && a.id != m.actor.id && a.cell.chebyshev(me) <= 30 then
        dot(a.cell, a.look match
          case Look.Npc(_)                  => "#0f0"
          case Look.Monster(_, _, Some(_), _) => "#6cf"
          case _                            => "#f00"
        )
    }
    if (scalajs.js.Date.now() / 300).toLong % 2 == 0 then dot(me, "#fff", 3)
    ui.ctl.intent match
      case mir.client.game.Intent.GoTo(r) => dot(r.dest, "#ff0", 3)
      case _                              => ()
  }
