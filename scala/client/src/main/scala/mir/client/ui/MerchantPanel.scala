package mir.client.ui

import mir.client.input.Pt
import mir.core.*
import mir.data.{ItemDef, MerchantDef, MerchantService}
import mir.model.{ItemInstance, ItemPlace}
import mir.protocol.GameCmd
import mir.rules.{Durability, ItemKind, Pricing}

/** 商人对话的哪一页：说话、买、卖、存、取。 */
enum MerchantMode:
  case Talk, Buy, Sell, Store, GetBack

/** 商品列表一行：买的商品或仓库里的一件。 */
final case class MenuRow(name: String, col1: String, col2: String, defn: ItemDef, item: Option[ItemInstance], take: () => Unit)

/**
 * 商人和仓库（照 FState.pas DMerchantDlg / DMenuDlg / DSellDlg，TS 版 classic.ts 商人部分）：
 * 对话框里点「购买 / 出售 / 保管 / 取回」，买和取在商品列表里双击（手机点一下弹菜单）；
 * 卖和存把物品放进出售框再点确定（手机在包裹里点物品选「卖出 / 保管」）。全部发指令给服务端。
 */
final class MerchantPanel(ui: ClassicUi) extends Window:
  import MerchantPanel.*
  import ui.{catalog, g}

  private final case class Session(npc: ActorId, defn: MerchantDef)

  private var session   = Option.empty[Session]
  private var mode      = MerchantMode.Talk
  private var menuTop   = 0
  private var menuIndex = -1
  /** 出售/保管框里放着的物品（只是界面状态，物品还在包裹里） */
  private var sellSpot  = Option.empty[Hand]
  private var links     = List.empty[(Rect, String)]

  def isOpen: Boolean = session.isDefined

  /** 出售/保管框里放的是这一格的东西（包裹里就不画它）。 */
  def holds(place: ItemPlace): Boolean = sellSpot.exists(_.from == place)

  /** 开着商店的出售页（这时包裹里的物品用商店的「卖出」，不再另列随时出售） */
  def selling: Boolean = session.isDefined && mode == MerchantMode.Sell

  def open(npc: ActorId, d: MerchantDef): Unit =
    session = Some(Session(npc, d))
    mode = MerchantMode.Talk
    menuTop = 0
    menuIndex = -1
    sellSpot = None

  def close(): Unit =
    session = None
    sellSpot = None

  private def origin: Pt = Pt(ui.safe.left, 0)

  private def text(d: MerchantDef): String = d.service match
    case MerchantService.Storage => s"${d.name}：\\我替你保管物品，要存还是要取？\\ \\<保管物品/@store>   <取回物品/@getback>\\ \\<离开/@exit>"
    case _                       => s"${d.name}：\\欢迎光临，看看有什么需要的？\\ \\<购买物品/@buy>   <出售物品/@sell>\\ \\<离开/@exit>"

  def draw(m: Me): Unit = session.foreach { s =>
    // NPC 不在视野里了（走远、换图）：关掉
    if !ui.client.view.actors.contains(s.npc) then close()
    else
      sellSpot = sellSpot.filter(Hand.valid(m.view, _))
      val o = origin
      ui.area(Rect(o.x, o.y, 416, 176))
      g.img(Gfx.Main, 384, o.x, o.y)
      links = Nil
      text(s.defn).split("\\\\").zipWithIndex.foreach { (raw, li) =>
        val ly = o.y + 20 + li * 16
        val sx = LinkRe.findAllMatchIn(raw).foldLeft((o.x + 30, 0)) { case ((sx, last), mt) =>
          val before = raw.substring(last, mt.start)
          val x1     = sx + (if before.nonEmpty then g.textW(before, sx, ly, "#fff", bold = true) else 0)
          val label  = mt.group(1)
          val cmd    = mt.group(2)
          val w      = g.textW(label, x1, ly, if modeOf(cmd).contains(mode) then "#f00" else "#ff0", bold = true)
          g.rect(x1, ly + 13, w, 1, "#ff0")
          links = (Rect(x1, ly, w, 14), cmd) :: links
          (x1 + w, mt.end)
        }
        val rest = raw.substring(sx._2)
        if rest.nonEmpty then g.text(rest, sx._1, ly, "#fff", bold = true)
      }
      links.foreach((r, cmd) => ui.hit(r)(runLink(cmd)))
      ui.button(Button(Rect(o.x + 399, o.y + 1, 16, 23), () => close(), img = Some(64)))
      mode match
        case MerchantMode.Buy | MerchantMode.GetBack => drawMenu(m, s, o)
        case MerchantMode.Sell | MerchantMode.Store  => drawSell(m, s, o)
        case MerchantMode.Talk                       => ()
  }

  private def modeOf(cmd: String): Option[MerchantMode] = cmd match
    case "@buy"     => Some(MerchantMode.Buy)
    case "@sell"    => Some(MerchantMode.Sell)
    case "@store"   => Some(MerchantMode.Store)
    case "@getback" => Some(MerchantMode.GetBack)
    case _          => None

  private def runLink(cmd: String): Unit =
    sellSpot = None
    menuIndex = -1
    menuTop = 0
    modeOf(cmd) match
      case None => close()
      case Some(md) =>
        mode = md
        if ui.touch then
          md match
            case MerchantMode.Sell  => ui.message("点包裹里的物品，选「卖出」")
            case MerchantMode.Store => ui.message("点包裹里的物品，选「保管」")
            case _                  => ()

  // ---------------- 商品列表（买、取） ----------------

  private def rows(m: Me, s: Session): Vector[MenuRow] = mode match
    case MerchantMode.GetBack =>
      m.view.storage.toVector.flatMap { it =>
        catalog.item(it.name).map { d =>
          val dura = ItemKind.of(d) match
            case ItemKind.Equip(_) => s"${Durability.shown(it.dura)}/${Durability.shown(it.duraMax)}"
            case _                 => "-"
          val label = if it.count > 1 then s"${it.name.value} ×${it.count}" else it.name.value
          MenuRow(label, dura, "", d, Some(it),
            () => ui.askCount(it, s"取回 ${it.name.value}", _ => None, n => ui.send(GameCmd.Withdraw(s.npc, it.uid, Some(n)))))
        }
      }
    case _ =>
      s.defn.goods.toVector.flatMap { gd =>
        catalog.item(gd.name).map { d =>
          MenuRow(d.name.value, s"${Pricing.buy(d)} 金币", if d.dura > 100 then Durability.shown(d.dura).toString else "-", d, None,
            () => ui.send(GameCmd.Buy(s.npc, d.name)))
        }
      }

  private def rowRect(o: Pt, row: Int): Rect = Rect(o.x + 14, o.y + 176 + 32 + row * LineH, 265, LineH)

  private def drawMenu(m: Me, s: Session, o: Pt): Unit =
    val x     = o.x
    val y     = o.y + 176
    val store = mode == MerchantMode.GetBack
    ui.area(Rect(x, y, 308, 205))
    g.img(Gfx.Main, 385, x, y)
    g.text(if store then "保管物品" else "物品列表", x + 19, y + 11)
    g.text(if store then "持久" else "价格", x + 156, y + 11)
    if !store then g.text("持久", x + 245, y + 11)
    val all = rows(m, s)
    all.zipWithIndex.slice(menuTop, menuTop + MaxMenu).foreach { (r, i) =>
      val row   = i - menuTop
      val ly    = y + 32 + row * LineH
      val color = if i == menuIndex then "#f00" else "#fff"
      if i == menuIndex then g.text("•", x + 10, ly, color)
      g.text(r.name, x + 19, ly, color)
      g.text(r.col1, x + 156, ly, color)
      if !store then g.text(r.col2, x + 245, ly, color)
      val rr = rowRect(o, row)
      ui.hit(rr) {
        if ui.touch then
          menuIndex = i
          ui.itemMenu.show(ItemMenu(
            Describe.itemLines(catalog, r.defn, r.item, m.job, m.stats, None),
            List(MenuAction(if store then "取回" else s"购买 ${r.col1}", r.take)),
            rr,
            keepOpen = !store
          ))
        else
          if menuIndex == i && ui.isDouble(s"menu$i") then r.take() else ui.isDouble(s"menu$i"): Unit
          menuIndex = i
      }
    }
    ui.button(Button(Rect(x + 43, y + 175, 24, 24), () => menuTop = math.max(0, menuTop - MaxMenu), img = Some(388), always = true))
    ui.button(Button(Rect(x + 90, y + 175, 24, 24), () => if menuTop + MaxMenu < all.size then menuTop += MaxMenu, img = Some(387), always = true))
    ui.button(Button(Rect(x + 215, y + 171, 44, 16), () => all.lift(menuIndex).foreach(_.take()), img = Some(386), always = true))
    ui.button(Button(Rect(x + 291, y, 16, 23), () => mode = MerchantMode.Talk, img = Some(64)))

  /** 电脑上鼠标停在商品列表某行：说明。 */
  override def hover(m: Me, p: Pt): Option[List[Line]] =
    for
      s <- session
      if mode == MerchantMode.Buy || mode == MerchantMode.GetBack
      (r, _) <- rows(m, s).zipWithIndex.slice(menuTop, menuTop + MaxMenu).find((_, i) => rowRect(origin, i - menuTop).contains(p.x, p.y))
    yield Describe.itemLines(catalog, r.defn, r.item, m.job, m.stats, None)

  // ---------------- 出售、保管框 ----------------

  private def drawSell(m: Me, s: Session, o: Pt): Unit =
    val x     = o.x + 260
    val y     = o.y + 176
    val store = mode == MerchantMode.Store
    ui.area(Rect(x, y, 140, 181))
    g.img(Gfx.Main, 392, x, y)
    g.text(if store then "放入要保管的物品" else "放入要出售的物品", x + 12, y + 30)
    val spot = Rect(x + 27, y + 67, 61, 52)
    ui.hit(spot)(clickSpot())
    sellSpot.flatMap(h => catalog.item(h.item.name)).foreach { d =>
      val count = sellSpot.fold(1)(_.item.count)
      g.item(d.look, count, spot)
      if !store then g.text(s"${Pricing.sell(d) * count} 金币", x + 30, y + 128, "#ff0")
    }
    ui.button(Button(Rect(x + 85, y + 150, 24, 13), () => confirm(s), img = Some(393), always = true))
    ui.button(Button(Rect(x + 115, y, 16, 23), () => { sellSpot = None; mode = MerchantMode.Talk }, img = Some(64)))

  /** 拿着物品点出售框：放进去；空手点：拿回手上。 */
  private def clickSpot(): Unit = (ui.hand, sellSpot) match
    case (Some(Hand(ItemPlace.Worn(_), _)), _) => ui.message("先把装备脱下来放进包裹", mir.protocol.Tone.Bad)
    case (Some(h), _) =>
      sellSpot = Some(h)
      ui.hand = None
    case (None, Some(h)) =>
      ui.hand = Some(h)
      sellSpot = None
    case (None, None) => ()

  private def confirm(s: Session): Unit = sellSpot.foreach { h =>
    sellSpot = None
    sellOrStore(s, h.item)
  }

  /** 手机上点包裹里的物品：在卖、存页时加「卖出」「保管」。 */
  def bagActions(it: ItemInstance, d: ItemDef): List[MenuAction] =
    session.toList.flatMap { s =>
      mode match
        case MerchantMode.Sell =>
          val each = Pricing.sell(d)
          List(MenuAction(if it.count > 1 then s"卖出（每个 $each 金币）" else s"卖出 $each 金币", () => sellOrStore(s, it)))
        case MerchantMode.Store => List(MenuAction("保管", () => sellOrStore(s, it)))
        case _                  => Nil
    }

  /** 卖出 / 保管：一叠先问数量（默认全部）。 */
  private def sellOrStore(s: Session, it: ItemInstance): Unit =
    if mode == MerchantMode.Store then
      ui.askCount(it, s"保管 ${it.name.value}", _ => None, n => ui.send(GameCmd.Deposit(s.npc, it.uid, Some(n))))
    else
      val each = catalog.item(it.name).fold(0)(Pricing.sell)
      ui.askCount(it, s"卖出 ${it.name.value}", n => Some(s"得到 ${each * n} 金币"), n => ui.send(GameCmd.Sell(s.npc, it.uid, Some(n))))

object MerchantPanel:
  /** 商品列表行高、一页几行 */
  val LineH   = 14.0
  val MaxMenu = 10
  private val LinkRe = """<([^/>]+)/(@\w+)>""".r
