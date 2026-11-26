package mir.client.ui

import mir.core.*
import mir.protocol.Look
import mir.rules.Rates

/**
 * 小地图上一个活物的标记（用户 11-26 在 #1323 定：人、怪、boss、宝宝各用一种，一眼分清）。
 * size 是界面像素，手机上再按操作件倍数放大；shape 方块或圆点。
 */
enum MiniMark(val color: String, val size: Double, val round: Boolean, val outline: Option[String]):
  // 顺序就是画的先后：后画的盖在上面，boss 最后
  case Monster extends MiniMark("#f00", 2, false, None)
  case Npc     extends MiniMark("#0f0", 2, false, None)
  case Pet     extends MiniMark("#6cf", 2.5, true, None)
  case Player  extends MiniMark("#39f", 3, true, Some("#fff"))
  case Boss    extends MiniMark("#f0f", 5, false, Some("#fff"))

object MiniMark:
  /** boss 用爆率那份名单（Rates.bosses），不另立。 */
  def of(look: Look): MiniMark = look match
    case Look.Npc(_)                         => Npc
    case _: Look.Human                       => Player
    case m: Look.Monster if m.pet.isDefined  => Pet
    case m: Look.Monster if Rates.bosses(m.kind) => Boss
    case _: Look.Monster                     => Monster

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
    // 手机上界面缩得很小，标记跟操作件一起放大（电脑上 ctlScale = 1）
    val k = ui.ctlScale
    def pos(c: Cell): (Double, Double) = (mm.x + c.x * 48 / 32 - mm.src.x, mm.y + c.y - mm.src.y)
    def dot(c: Cell, color: String, size: Double): Unit =
      val (px, py) = pos(c)
      if box.contains(px, py) then g.rect(px, py, size * k, size * k, color)
    def mark(c: Cell, mk: MiniMark): Unit =
      val (px, py) = pos(c)
      if box.contains(px, py) then
        val s = mk.size * k
        if mk.round then g.circle(px + 1, py + 1, s / 2, mk.color, mk.outline)
        else
          mk.outline.foreach(o => g.rect(px + 1 - s / 2 - 1, py + 1 - s / 2 - 1, s + 2, s + 2, o))
          g.rect(px + 1 - s / 2, py + 1 - s / 2, s, s, mk.color)
    val me = m.actor.cell
    // 先画普通的、最后画 boss，boss 不被别的点盖住
    ui.client.actors
      .filter(a => !a.dead && a.id != m.actor.id && a.cell.chebyshev(me) <= 30)
      .map(a => (a.cell, MiniMark.of(a.look)))
      .toSeq
      .sortBy(_._2.ordinal)
      .foreach(mark.tupled)
    if (scalajs.js.Date.now() / 300).toLong % 2 == 0 then dot(me, "#fff", 3)
    ui.ctl.intent match
      case mir.client.game.Intent.GoTo(r) => dot(r.dest, "#ff0", 3)
      case _                              => ()
  }

