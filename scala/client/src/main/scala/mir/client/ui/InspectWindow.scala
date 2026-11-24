package mir.client.ui

import mir.client.input.Pt
import mir.client.render.Renderer
import mir.model.*
import mir.protocol.InspectView

/**
 * 查看别的玩家的装备（#1263，照原版 FState.pas DUserState1：锁定玩家后点「装备」，服务端回 Inspected）。
 * 画的是服务端回话那一刻的快照，对方之后换装不跟着变（原版同样）；再点一次「装备」刷新。
 * 位置照原版摆在自己状态窗左边（SCREENWIDTH - 2 × 窗宽），两个能并排对比。
 * 装备说明：电脑悬停，触屏点格子弹说明（只看，不带操作）；说明里和自己身上同部位的比较照常给。
 */
final class InspectWindow(ui: ClassicUi) extends Window:
  import StateWindow.{EquipRects, Width}
  import ui.g

  private var shown = Option.empty[InspectView]

  def isOpen: Boolean = shown.isDefined
  def close(): Unit   = shown = None
  def open(v: InspectView): Unit = shown = Some(v)

  def origin: Pt = Pt(math.max(0, ui.W - 2 * Width - ui.safe.right), 0)

  def draw(m: Me): Unit = shown.foreach { v =>
    val o = origin
    ui.area(Rect(o.x, o.y, Width, StateWindow.Height))
    g.img(Gfx.Main, 370, o.x, o.y)
    g.text(v.name, o.x + 116 - g.width(v.name) / 2, o.y + 23, Renderer.pkColor(v.pk), bold = true)
    StateWindow.drawFigure(ui, o, v.sex, v.hair, v.worn)
    if ui.touch then
      v.worn.foreach { (slot, it) =>
        val r = EquipRects(slot).moved(o.x, o.y)
        ui.hit(r)(ui.itemMenu.show(ItemMenu(ui.describe(m, it), Nil, r)))
      }
    ui.button(Button(Rect(o.x + 8, o.y + 39, 16, 23), () => close(), img = Some(371)))
  }

  override def hover(m: Me, p: Pt): Option[List[Line]] =
    for
      v  <- shown
      o   = origin
      it <- v.worn.collectFirst { case (slot, it) if EquipRects(slot).moved(o.x, o.y).contains(p.x, p.y) => it }
    yield ui.describe(m, it)
