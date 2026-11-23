package mir.client.ui

import mir.client.input.Pt
import mir.model.{ItemInstance, ItemPlace}
import mir.protocol.{GameCmd, Tone}

/**
 * 包裹（照 FState.pas DItemBag，TS 版 ui/classic/classic.ts）：8×N 格、金币、物品信息栏、「使用」框。
 * 商人开着时挪到商人窗右边。
 */
final class BagWindow(ui: ClassicUi) extends ToggleWindow:
  import ui.{catalog, g}

  def origin: Pt = Pt((if ui.merchant.isOpen then 475 else 0) + ui.safe.left, 0)

  def gridCell(i: Int): Rect =
    val o = origin
    Rect(o.x + 20 + (i % 8) * 36, o.y + 13 + (i / 8) * 32, 36, 32)

  def draw(m: Me): Unit =
    val o = origin
    ui.area(Rect(o.x, o.y, 336, 270))
    g.img(Gfx.Main, 3, o.x, o.y)
    m.view.bag.zipWithIndex.foreach { (slot, i) =>
      val r = gridCell(i)
      slot.filterNot(_ => ui.handFrom(ItemPlace.Bag(i)) || ui.merchant.holds(ItemPlace.Bag(i)))
        .foreach(it => catalog.item(it.name).foreach(d => g.item(d.look, it.count, r.moved(-1, 1))))
      ui.hit(r)(ui.clickPlace(ItemPlace.Bag(i), ui.client.view.bag.lift(i).flatten, r))
    }
    g.text(Gfx.gold(m.stats.gold.value), o.x + 64, o.y + 185)
    // 物品信息栏（电脑：鼠标停在哪件上，包裹、装备、快捷栏都算）
    ui.itemUnder(m).foreach { it =>
      catalog.item(it.name).foreach { d =>
        g.text(d.name.value, o.x + 70, o.y + 215, "#ff0")
        // 说明的前两条（只取括号前的部分）；穿不了的要求标红
        ui.describe(m, it).slice(1, 3).zipWithIndex.foreach { (l, i) =>
          g.text(l.text.takeWhile(_ != '　'), o.x + 70, o.y + 229 + i * 14, if l.tint == Tint.Bad then "#f00" else "#fff")
        }
      }
    }
    ui.button(Button(Rect(o.x + 309, o.y + 203, 14, 20), () => close(), img = Some(371)))
    // 原版包裹里的「使用」框：把手上的东西放上去就用
    ui.button(Button(Rect(o.x + 254, o.y + 183, 48, 22), () => ui.hand.foreach(useHand), img = Some(26)))
    // 随时出售（#1178，原版没有）：电脑上拿起物品再点它；手机在物品菜单里点「出售」
    val sell = Rect(o.x + 211, o.y + 183, 40, 22)
    g.frame(sell, if ui.isPressed(sell) then "#7a5a2a" else "#3a2a16", "#c9a060")
    g.text("出售", sell.x + (sell.w - g.width("出售")) / 2, sell.y + 4, "#ffe08a", bold = true)
    ui.button(Button(sell, () => sellHand()))

  private def useHand(h: Hand): Unit =
    ui.hand = None
    h.from match
      case ItemPlace.Worn(_) => ()
      case _                 => ui.send(GameCmd.UseItem(h.item.uid))

  private def sellHand(): Unit = ui.hand match
    case None                              => ui.message("先点包裹里的物品拿起来，再点「出售」", Tone.Bad)
    case Some(Hand(ItemPlace.Worn(_), _))  => ui.message("先把装备脱下来放进包裹", Tone.Bad)
    case Some(h) =>
      ui.hand = None
      if ui.quickSellPrice(h.item).isEmpty then ui.message(s"${h.item.name.value}不值钱，卖不了", Tone.Bad)
      else ui.quickSell(h.item)

  override def itemAt(m: Me, p: Pt): Option[ItemInstance] =
    m.view.bag.indices.collectFirst { case i if gridCell(i).contains(p.x, p.y) => m.view.bag(i) }.flatten
