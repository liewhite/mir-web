package mir.client.ui

import mir.core.ItemUid
import mir.model.{ItemInstance, ItemPlace}
import mir.protocol.GameCmd

/**
 * 点物品格时用哪种操作（#1300）：平时是原版的拿起/放下（手机弹菜单）；批量卖出时点包裹里的物品直接整叠卖掉、不确认。
 * css 是电脑上鼠标的样子（空 = 默认箭头）；手机没有鼠标，靠「批量」按钮高亮表示。
 */
enum PointerMode(val css: String):
  case Normal extends PointerMode("")
  case Sell   extends PointerMode(BulkSell.Cursor)

object BulkSell:
  /** 金色 $ 光标（32×32，热点在中间） */
  val Cursor: String =
    val svg =
      "<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32'>" +
        "<circle cx='16' cy='16' r='14' fill='rgba(40,24,6,0.85)' stroke='%23c9a060' stroke-width='2'/>" +
        "<text x='16' y='23' font-size='22' font-weight='bold' font-family='Arial' text-anchor='middle' fill='%23ffd84a'>$</text>" +
        "</svg>"
    s"url(\"data:image/svg+xml;utf8,$svg\") 16 16, pointer"

  /** 进入批量卖出时的提示 */
  val Hint = "批量卖出：点包裹里的物品直接整叠卖掉，不再确认；再点「批量」、点右键、按 Esc 或关掉包裹退出。"

  /**
   * 批量卖出模式下点了身上一格：包裹里值钱的、没放进交易栏的整叠卖掉（服务端再判一次）；别处、空格不卖。
   * sellable：这件东西值不值钱；offered：是否已放进交易栏。
   */
  def click(place: ItemPlace, there: Option[ItemInstance], sellable: ItemInstance => Boolean, offered: ItemUid => Boolean): HandStep =
    (place, there) match
      case (ItemPlace.Bag(_), Some(it)) if offered(it.uid) => HandStep(None, note = Some("交易栏里的东西先拿回来再卖"))
      case (ItemPlace.Bag(_), Some(it)) if !sellable(it)   => HandStep(None, note = Some(s"${it.name.value}不值钱，卖不了"))
      case (ItemPlace.Bag(_), Some(it))                    => HandStep(None, List(GameCmd.QuickSell(it.uid)))
      case (ItemPlace.Bag(_), None)                        => HandStep(None)
      case _ => HandStep(None, note = Some("批量卖出只卖包裹里的东西"))
