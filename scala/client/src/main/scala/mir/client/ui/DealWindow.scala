package mir.client.ui

import mir.client.input.Pt
import mir.core.*
import mir.model.{ItemInstance, ItemPlace}
import mir.protocol.{DealOffer, DealView, GameCmd, Look, Tone}
import mir.rules.TradeRules

/**
 * 交易窗口（#1222，照原版 FState.pas DDealRemoteDlg / DDealDlg）：右上角上面是对方的交易栏（prguse 390），
 * 下面是自己的（389，右上角 × 取消，「交易」键 391 确认）；每边 5×2 格、下面一格金币。打开时包裹一起打开。
 * 一切以服务端发来的交易状态（view.deal）为准：东西一直在包裹里（包裹里画暗），交易栏只是指着它们。
 *
 * 操作：
 * - 电脑：包裹里点物品拿起来，点自己的交易栏放进去（一叠先问几个）；点交易栏里的东西拿回；鼠标停在上面看说明。
 * - 手机：点包裹里的物品，菜单里「放入交易」；点交易栏里的东西看说明（自己的可以「拿回」）。
 * - 点左下角金币那格输入要给的金币数；「交易」键确认，双方都确认就成交；名字变绿打勾的是已确认的一方。
 */
final class DealWindow(ui: ClassicUi) extends Window:
  import DealWindow.*
  import ui.{catalog, g}

  private def deal: Option[DealView] = ui.client.view.deal

  def isOpen: Boolean = deal.isDefined

  /** Esc、死亡时关窗口：交易取消 */
  def close(): Unit = if isOpen then ui.send(GameCmd.DealCancel)

  /** 这件东西放在我的交易栏里（包裹、快捷栏里画暗） */
  def offers(uid: ItemUid): Boolean = deal.exists(_.mine.items.exists(_.uid == uid))

  /** 对方窗口、自己窗口的左上角（原版：屏幕右边留 100，两窗重叠 15）；右边至少让出攻击键（手机上攻击键放大了） */
  private def left: Double = ui.W - ui.safe.right - 236 - math.max(100, Board.AttackR * 2 * ui.ctlScale + 36)
  private def theirsAt: Pt = Pt(left, 0)
  private def mineAt: Pt   = Pt(left, RemoteH - 15)

  private def cell(o: Pt, i: Int): Rect = Rect(o.x + 21 + (i % 5) * 36, o.y + 56 + (i / 5) * 33, 36, 33)

  def draw(m: Me): Unit = deal.foreach { d =>
    drawSide(theirsAt, 390, d.partner, d.theirs, mine = false)
    drawSide(mineAt, 389, m.name, d.mine, mine = true)
  }

  private def drawSide(o: Pt, img: Int, name: String, offer: DealOffer, mine: Boolean): Unit =
    val (w, h) = (if mine then 236.0 else 220.0, RemoteH)
    ui.area(Rect(o.x, o.y, w, h))
    g.img(Gfx.Main, img, o.x, o.y)
    // 名字（确认了变绿打勾）
    val title = if offer.confirmed then s"$name ✓" else name
    g.text(title, o.x + 59 + (106 - g.width(title)) / 2, o.y + 6, if offer.confirmed then "#7cff6a" else "#fff")
    g.text(Gfx.gold(offer.gold.value), o.x + 64, o.y + 131)
    if !mine && offer.confirmed then g.text("已确认", o.x + 160, o.y + 131, "#7cff6a", bold = true)
    (0 until TradeRules.MaxItems).foreach { i =>
      val r  = cell(o, i)
      val it = offer.items.lift(i)
      it.foreach(x => catalog.item(x.name).foreach(dd => g.item(dd.look, x.count, r.moved(-1, 1))))
      if mine then ui.hit(r)(clickMine(it, r)) else it.foreach(x => ui.hit(r)(showTheirs(x, r)))
    }
    if mine then
      ui.button(Button(Rect(o.x + 220, o.y + 42, 16, 23), () => close(), img = Some(64)))
      if offer.confirmed then g.img(Gfx.Main, 391, o.x + 155, o.y + 128)
      ui.button(Button(Rect(o.x + 155, o.y + 128, 44, 16), () => ui.send(GameCmd.DealConfirm), img = Some(391)))
      // 左下角金币圈和金币数那格：输入金币
      ui.hit(Rect(o.x + 6, o.y + 120, 140, 40))(askGold(offer.gold))

  /** 点自己交易栏的一格：拿着包裹里的东西就放进去；手机点东西看说明和「拿回」，电脑点东西直接拿回。 */
  private def clickMine(there: Option[ItemInstance], r: Rect): Unit = ui.hand match
    case Some(Hand(ItemPlace.Worn(_), _)) => ui.message("先把装备脱下来放进包裹", Tone.Bad)
    case Some(h) =>
      ui.hand = None
      put(h.item)
    case None =>
      there.foreach { it =>
        if ui.touch then
          ui.me.foreach(m => ui.itemMenu.show(ItemMenu(ui.describe(m, it), List(MenuAction("拿回", () => ui.send(GameCmd.DealTake(it.uid)))), r)))
        else ui.send(GameCmd.DealTake(it.uid))
      }

  private def showTheirs(it: ItemInstance, r: Rect): Unit =
    if ui.touch then ui.me.foreach(m => ui.itemMenu.show(ItemMenu(ui.describe(m, it), Nil, r)))

  /** 放进交易栏：一叠先问放几个（默认全部）。 */
  def put(it: ItemInstance): Unit =
    if offers(it.uid) then ui.message("已经放进交易栏了", Tone.Bad)
    else ui.askCount(it, s"放入交易 ${it.name.value}", _ => None, n => ui.send(GameCmd.DealPut(it.uid, Some(n))))

  /** 手机上点包裹、快捷栏里的物品：交易中加「放入交易」或「拿回」。 */
  def bagActions(it: ItemInstance): List[MenuAction] =
    if !isOpen then Nil
    else if offers(it.uid) then List(MenuAction("从交易栏拿回", () => ui.send(GameCmd.DealTake(it.uid))))
    else List(MenuAction("放入交易", () => put(it)))

  private def askGold(now: Gold): Unit =
    val have = ui.client.view.stats.fold(0)(_.gold.value)
    ui.ask(s"交易金币：输入要给的数量（你有 $have）", if now == Gold.zero then "" else now.value.toString) { text =>
      text.trim.replace(",", "").toIntOption.filter(n => n >= 0 && n <= have) match
        case Some(n) => ui.send(GameCmd.DealGold(Gold(n)))
        case None    => ui.message(s"金币数要在 0 到 $have 之间", Tone.Bad)
    }

  /** 电脑悬停：两边交易栏里的东西 */
  override def itemAt(m: Me, p: Pt): Option[ItemInstance] =
    deal.flatMap { d =>
      List(theirsAt -> d.theirs, mineAt -> d.mine).iterator.flatMap { (o, offer) =>
        offer.items.indices.find(i => cell(o, i).contains(p.x, p.y)).map(offer.items(_))
      }.nextOption()
    }

object DealWindow:
  /** 两个窗口的高（原版图 175） */
  val RemoteH = 175.0
  /** 包裹、快捷栏里已放进交易栏的东西盖一层暗色 */
  val Dim = "rgba(0,0,0,.55)"

  /** 发起交易的对象：锁定的玩家，否则身边（相邻）最近的玩家。 */
  def partner(ui: ClassicUi, m: Me): Option[ActorId] =
    def human(id: ActorId) = ui.client.actor(id).exists(a => a.id != m.actor.id && (a.look match
      case _: Look.Human => true
      case _             => false))
    ui.client.locked.filter(human).orElse(
      ui.client.actors.filter(a => human(a.id) && a.cell.chebyshev(m.actor.cell) <= TradeRules.OpenRange)
        .minByOption(a => a.cell.chebyshev(m.actor.cell)).map(_.id)
    )
