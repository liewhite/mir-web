package mir.client.ui

import mir.client.input.Pt
import mir.model.{ItemInstance, ItemPlace}
import mir.protocol.{GameCmd, Tone}
import mir.rules.ItemKind

/** 手机上点了物品弹出的说明 + 操作按钮。keepOpen：做完操作菜单留着（连续买药）。 */
final case class MenuAction(label: String, run: () => Unit)
final case class ItemMenu(lines: List[Line], actions: List[MenuAction], anchor: Rect, keepOpen: Boolean = false)

/**
 * 说明弹框（触屏：点物品、商品、属性、技能，长按技能环），叠在所有窗口最上层。
 * 点在框里只算框里的按钮，点在外面就关掉它（ClassicUi.gameDown）。
 */
final class ItemMenuWindow(ui: ClassicUi) extends Window:
  import ItemMenuWindow.*
  import ui.{catalog, g}

  private var menu = Option.empty[ItemMenu]
  /** 这一帧画在哪（点在里面不关菜单）；每帧重算 */
  private var drawn = Option.empty[Rect]

  def isOpen: Boolean = menu.isDefined
  def close(): Unit =
    menu = None
    drawn = None
  def show(m: ItemMenu): Unit =
    menu = Some(m)
    drawn = None

  /** (x, y) 在这一帧画出的菜单里。 */
  def contains(p: Pt): Boolean = isOpen && drawn.exists(_.contains(p.x, p.y))

  /** 触屏上点身上一格：有东西就弹说明和操作。 */
  def forItem(place: ItemPlace, there: Option[ItemInstance], anchor: Rect): Unit =
    for
      m  <- ui.me
      it <- there
      d  <- catalog.item(it.name)
    do
      val kind = ItemKind.of(d)
      val actions: List[MenuAction] = place match
        case ItemPlace.Worn(slot) => List(MenuAction("脱下", () => ui.send(GameCmd.Unequip(slot))))
        case ItemPlace.Belt(_) =>
          List(
            MenuAction("使用", () => ui.send(GameCmd.UseItem(it.uid))),
            MenuAction("放回包裹", () => firstEmpty(m.view.bag).fold(ui.message("包裹满了", Tone.Bad))(i => ui.send(GameCmd.MoveItem(it.uid, ItemPlace.Bag(i)))))
          )
        case ItemPlace.Bag(_) =>
          ui.merchant.bagActions(it, d) ++
            useLabel(kind).map(l => MenuAction(l, () => ui.send(GameCmd.UseItem(it.uid)))) ++
            Option.when(beltable(kind))(firstEmpty(m.view.belt)).flatten.map(i => MenuAction("放快捷栏", () => ui.send(GameCmd.MoveItem(it.uid, ItemPlace.Belt(i))))) ++
            List(
              MenuAction("移动", () => ui.hand = Some(Hand(place, it))),
              MenuAction("丢弃", () => ui.send(GameCmd.DropItem(it.uid)))
            )
      show(ItemMenu(ui.describe(m, it), actions, anchor))

  private def firstEmpty(slots: Vector[Option[ItemInstance]]): Option[Int] = Some(slots.indexOf(None)).filter(_ >= 0)

  def draw(m: Me): Unit = menu.foreach(mm => drawn = Some(drawMenu(mm)))

  /**
   * 画菜单，返回它占的范围（界面像素）。原版底栏在手机上缩得很小，菜单按触屏操作件的倍数放大，手指才点得到；
   * 排版在菜单自己的坐标里算（未放大），摆放和点击区换算成界面坐标。
   */
  private def drawMenu(m0: ItemMenu): Rect =
    val lh   = 15.0
    val btnH = 32.0
    val pad  = 6.0
    val s    = math.min(ui.ctlScale, (ui.W - 8) / 200)
    val maxW = (ui.W - 8) / s
    val menu = m0.copy(lines = g.wrap(m0.lines, math.min(360, maxW - pad * 2)))
    val btnW = menu.actions.map(a => math.max(56, g.width(a.label) + 20))
    val textW = menu.lines.map(l => g.width(l.text)).maxOption.getOrElse(0.0)
    val w    = math.min(maxW, math.max(textW, btnW.sum + 6 * (btnW.size - 1)) + pad * 2)
    // 按钮一行放不下就折行
    val rows = menu.actions.zip(btnW).foldLeft(Vector(Vector.empty[(MenuAction, Double)])) { (acc, ab) =>
      val cur = acc.last
      val used = cur.map(_._2).sum + 6 * cur.size
      if cur.nonEmpty && used + ab._2 > w - pad * 2 then acc :+ Vector(ab) else acc.init :+ (cur :+ ab)
    }.filter(_.nonEmpty)
    val h = pad + menu.lines.size * lh + 4 + rows.size * (btnH + 6) + pad - 6
    // 优先摆在格子右边，放不下就左边；纵向尽量和格子顶对齐
    val (sw, sh) = (w * s, h * s)
    val a  = menu.anchor
    val x0 = if a.x + a.w + 4 + sw > ui.W - ui.safe.right then a.x - sw - 4 else a.x + a.w + 4
    val x  = math.max(ui.safe.left + 2, math.min(x0, ui.W - ui.safe.right - sw - 2))
    val y  = math.max(ui.safe.top + 2, math.min(a.y, ui.H - ui.safe.bottom - sh - 2))
    def toUi(r: Rect) = Rect(x + r.x * s, y + r.y * s, r.w * s, r.h * s)
    val buttons = rows.zipWithIndex.flatMap { (row, ri) =>
      val by = pad + menu.lines.size * lh + 4 + ri * (btnH + 6)
      row.scanLeft((pad, Option.empty[(MenuAction, Rect)])) { case ((bx, _), (act, bw)) =>
        (bx + bw + 6, Some(act -> Rect(bx, by, bw, btnH)))
      }.flatMap(_._2)
    }
    val box = Rect(x, y, sw, sh)
    ui.area(box)
    g.scaled(x, y, s) {
      g.frame(Rect(0, 0, w, h), "rgba(10,7,3,.94)", "#8a6a3a")
      menu.lines.zipWithIndex.foreach((l, i) => g.text(l.text, pad, pad + i * lh, l.tint.color))
      buttons.foreach { (act, r) =>
        g.frame(r, if ui.isPressed(toUi(r)) then "#7a5a2a" else "#3a2a16", "#c9a060")
        g.text(act.label, r.x + (r.w - g.width(act.label)) / 2, r.y + 10, "#ffe08a", bold = true)
      }
    }
    buttons.foreach { (act, r) =>
      ui.hit(toUi(r)) {
        act.run()
        if !menu.keepOpen then close()
      }
    }
    box

object ItemMenuWindow:
  /** 物品菜单里「使用」按钮叫什么；没法直接用的没有 */
  def useLabel(k: ItemKind): Option[String] = k match
    case ItemKind.Potion(_, _, _) | ItemKind.Scroll(_) => Some("使用")
    case ItemKind.Book(_, _)                           => Some("学习")
    case ItemKind.Bundle(_)                            => Some("拆开")
    case ItemKind.Equip(_)                             => Some("穿戴")
    case ItemKind.Amulet(_)                            => Some("装备")
    case ItemKind.Material | ItemKind.Other            => None

  /** 能放快捷栏：药和卷轴 */
  def beltable(k: ItemKind): Boolean = k match
    case ItemKind.Potion(_, _, _) | ItemKind.Scroll(_) => true
    case _                                             => false
