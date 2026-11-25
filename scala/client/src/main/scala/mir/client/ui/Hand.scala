package mir.client.ui

import mir.model.{ItemInstance, ItemPlace}
import mir.protocol.GameCmd
import mir.view.ViewState

/**
 * 手上拿着的物品（原版拖物品）：只是界面状态，物品一直留在原处（from），放下时才发指令，由服务端判定挪不挪得成。
 */
final case class Hand(from: ItemPlace, item: ItemInstance)

/** 点一下的结果：手上变成什么、要发的指令、要提示的话、要按哪件物品出声（ItemClickSound）。 */
final case class HandStep(hand: Option[Hand], cmds: List[GameCmd] = Nil, note: Option[String] = None, clink: Option[ItemInstance] = None)

object Hand:
  /** 身上某个位置现在的物品（按服务端发来的视野副本）。 */
  def at(view: ViewState, place: ItemPlace): Option[ItemInstance] = place match
    case ItemPlace.Bag(i)     => view.bag.lift(i).flatten
    case ItemPlace.Belt(i)    => view.belt.lift(i).flatten
    case ItemPlace.Worn(slot) => view.worn.get(slot)

  /** 手上的东西还在原处（服务端那边被用掉、挪走了就放手）。 */
  def valid(view: ViewState, h: Hand): Boolean = at(view, h.from).exists(_.uid == h.item.uid)

  /**
   * 点物品格（电脑原版操作）：没拿东西时点一下拿起，再点别的格子放下（服务端挪过去，有东西就互换），点回原处放回；
   * 双击使用；拿着装备点装备格就是穿上。（包裹关着时点快捷栏直接用，由底栏处理，不走这里。）
   * 出声照原版 FState.pas：拿起和放到装备位按物品出声，放回包裹、快捷栏不出声（原版那里的 ItemClickSound 注释掉了）。
   */
  def click(hand: Option[Hand], place: ItemPlace, there: Option[ItemInstance], double: Boolean): HandStep =
    (hand, place) match
      case (Some(h), _) if h.from == place =>
        if double then HandStep(None, List(GameCmd.UseItem(h.item.uid))).filterUsable(place) else HandStep(None)
      case (Some(h), ItemPlace.Worn(_)) if isWorn(h.from) => HandStep(None)
      case (Some(h), ItemPlace.Worn(_)) => HandStep(None, List(GameCmd.MoveItem(h.item.uid, place)), clink = Some(h.item))
      case (Some(h), _) => HandStep(None, List(GameCmd.MoveItem(h.item.uid, place)))
      case (None, _) => HandStep(there.map(Hand(place, _)), clink = there)

  /** 拿着东西点地面：扔掉（身上穿着的要先脱下来）。 */
  def dropOnGround(h: Hand): HandStep =
    if isWorn(h.from) then HandStep(None, note = Some("先把装备脱下来放进包裹"))
    else HandStep(None, List(GameCmd.DropItem(h.item.uid)))

  private def isWorn(p: ItemPlace): Boolean = p match
    case ItemPlace.Worn(_) => true
    case _                 => false

  extension (s: HandStep)
    /** 穿着的装备双击不算使用（原版不响应）。 */
    private def filterUsable(place: ItemPlace): HandStep = if isWorn(place) then HandStep(None) else s
