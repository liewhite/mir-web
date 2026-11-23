package mir.client.ui

import mir.core.TeleportRef
import mir.protocol.GameCmd

/**
 * 传送窗口（用户 #1139：任意地方直接传到城中心、打怪地图入口；照 TS 版 ui/classic/teleport.ts）。原版没有，画成同色调的面板。
 * 两层：先选分组（城市、骷髅洞、沃玛、祖玛寺庙……），再选地图；点了就发 Teleport，窗口关掉。目的地来自 game.json teleports。
 */
final class TeleportPanel(ui: ClassicUi) extends ToggleWindow:
  import TeleportPanel.*
  import ui.g

  /** 选中的分组；None = 在选分组 */
  private var group = Option.empty[String]

  /** 每次打开回到选地区那一页 */
  override def show(): Unit =
    super.show()
    group = None

  private final case class Entry(label: String, active: Boolean, onClick: () => Unit)

  def draw(m: Me): Unit =
    val all     = ui.catalog.data.teleports.zipWithIndex
    val here    = m.view.map
    val entries = group match
      case None =>
        val activeGroups = all.collect { case (t, _) if here.contains(t.map) => t.group }.toSet
        all.map(_._1.group).distinct.map(name => Entry(name, activeGroups(name), () => group = Some(name)))
      case Some(gr) =>
        all.collect { case (t, i) if t.group == gr =>
          Entry(t.name, here.contains(t.map), () => go(i, if t.group == "城市" then t.name else s"${t.group} · ${t.name}"))
        }
    def width(cols: Int) = Pad * 2 + cols * BtnW + (cols - 1) * Gap
    val want = ui.ctlScale
    val cols = if (ui.W - 8) / want >= width(3) then 3 else 2
    val rows = (entries.size + cols - 1) / cols
    // 面板内坐标（未放大）；画的时候整体放大 s 倍
    val w  = width(cols)
    val hh = Head + rows * (BtnH + Gap) + Pad
    val s  = math.min(want, math.min((ui.W - 8) / w, (ui.H - 8) / hh))
    val o  = ui.dialogOrigin(w * s, hh * s)
    def toUi(r: Rect) = Rect(o.x + r.x * s, o.y + r.y * s, r.w * s, r.h * s)
    ui.area(toUi(Rect(0, 0, w, hh)))
    val closeBtn = Rect(w - 50, 4, 46, 30)
    val buttons =
      List((closeBtn, "关闭", () => close(), "#ffe08a")) ++
        group.map(_ => (Rect(closeBtn.x - 52, 4, 46, 30), "返回", () => group = None, "#ffe08a")) ++
        entries.zipWithIndex.map { (e, i) =>
          (Rect(Pad + (i % cols) * (BtnW + Gap), Head + (i / cols) * (BtnH + Gap), BtnW, BtnH), e.label, e.onClick, if e.active then "#9cff8a" else "#fff")
        }
    g.scaled(o.x, o.y, s) {
      g.frame(Rect(0, 0, w, hh), "rgba(16,11,6,.96)", "#8a6a3a")
      g.text(group.fold("传送：选地区")(gr => s"传送：$gr"), Pad + 4, 13, "#ffe08a", bold = true)
      buttons.foreach { (r, label, _, color) =>
        g.frame(r, if ui.isPressed(toUi(r)) then "#7a5a2a" else "#3a2a16", "#c9a060")
        val text = Iterator.iterate(label)(_.dropRight(1)).find(t => t.length <= 2 || g.width(t) <= r.w - 8).getOrElse(label)
        g.text(text, r.x + (r.w - g.width(text)) / 2, r.y + (r.h - 14) / 2 + 1, color, bold = true)
      }
    }
    buttons.foreach((r, _, onClick, _) => ui.button(Button(toUi(r), onClick)))

  private def go(i: Int, label: String): Unit =
    ui.send(GameCmd.Teleport(TeleportRef(i)))
    ui.message(s"传送到 $label")
    close()

object TeleportPanel:
  val BtnW = 124.0
  val BtnH = 34.0
  val Gap  = 6.0
  val Pad  = 10.0
  val Head = 40.0
