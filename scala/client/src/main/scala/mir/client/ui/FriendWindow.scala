package mir.client.ui

import mir.protocol.{FriendView, Look, SocialCmd, Tone}

/**
 * 好友窗口（#1221）。1.76 原版没有好友功能（素材里也没有好友窗口的图），画成和传送窗口同色调的面板，按触屏倍数放大。
 * 底栏「友」键或 F 键打开。名单在线的排前面（绿字「在线」），点一行选中；下面三个键：
 * 添加（输入框里先填好锁定的玩家）、删除（填好选中的人，回车才删）、私聊（聊天框填好「/名字 」）。
 * 名单太长分页（右上角 ‹ ›）。一切以服务端发来的名单（view.friends）为准。
 */
final class FriendWindow(ui: ClassicUi) extends ToggleWindow:
  import FriendWindow.*
  import ui.g

  /** 点选的好友 */
  private var picked = Option.empty[String]
  private var page   = 0

  override def show(): Unit =
    picked = None
    page = 0
    super.show()

  def draw(m: Me): Unit =
    val all    = sorted(m.view.friends)
    val pages  = math.max(1, (all.size + PageRows - 1) / PageRows)
    page = math.min(page, pages - 1)
    picked = picked.filter(p => all.exists(_.name == p))
    val shown  = all.slice(page * PageRows, (page + 1) * PageRows)
    val want   = ui.ctlScale
    val s      = math.min(want, math.min((ui.W - 8) / W, (ui.H - 8) / H))
    val o      = ui.dialogOrigin(W * s, H * s)
    def toUi(r: Rect) = Rect(o.x + r.x * s, o.y + r.y * s, r.w * s, r.h * s)
    ui.area(toUi(Rect(0, 0, W, H)))
    val online = all.count(_.online)
    val closeBtn = Rect(W - 50, 4, 46, 30)
    val keys: List[(Rect, String, () => Unit)] =
      List((closeBtn, "关闭", () => close())) ++
        Option.when(pages > 1)(List(
          (Rect(closeBtn.x - 76, 4, 34, 30), "‹", () => page = math.max(0, page - 1)),
          (Rect(closeBtn.x - 38, 4, 34, 30), "›", () => page = math.min(pages - 1, page + 1))
        )).toList.flatten ++
        List("添加" -> (() => add(m)), "删除" -> (() => remove()), "私聊" -> (() => whisper(all))).zipWithIndex.map { case ((label, act), i) =>
          (Rect(Pad + i * (BtnW + Gap), H - Pad - BtnH, BtnW, BtnH), label, act)
        }
    val rows = shown.zipWithIndex.map((f, i) => f -> Rect(Pad, Head + i * RowH, W - Pad * 2, RowH - 2))
    g.scaled(o.x, o.y, s) {
      g.frame(Rect(0, 0, W, H), "rgba(16,11,6,.96)", "#8a6a3a")
      val title = if all.isEmpty then "好友" else s"好友（在线 $online / ${all.size}）"
      g.text(title, Pad + 4, 13, "#ffe08a", bold = true)
      if all.isEmpty then Hints.zipWithIndex.foreach((t, i) => g.text(t, Pad + 4, Head + 6 + 18 * i, "#8a8a8a"))
      rows.foreach { (f, r) =>
        g.frame(r, if picked.contains(f.name) then "rgba(216,162,74,.35)" else "rgba(0,0,0,.35)", "#4a3a22")
        g.text(f.name, r.x + 8, r.y + 6, if f.online then "#fff" else "#9a9a9a")
        val (st, color) = if f.online then ("在线", "#9cff8a") else ("离线", "#7a7a7a")
        g.text(st, r.x + r.w - 8 - g.width(st), r.y + 6, color)
      }
      if pages > 1 then
        val p = s"${page + 1}/$pages"
        g.text(p, closeBtn.x - 76 - 6 - g.width(p), 13, "#c0c0c0")
      keys.foreach { (r, label, _) =>
        g.frame(r, if ui.isPressed(toUi(r)) then "#7a5a2a" else "#3a2a16", "#c9a060")
        g.text(label, r.x + (r.w - g.width(label)) / 2, r.y + (r.h - 14) / 2 + 1, "#ffe08a", bold = true)
      }
    }
    rows.foreach((f, r) => ui.hit(toUi(r)) { picked = if picked.contains(f.name) then None else Some(f.name) })
    keys.foreach((r, _, act) => ui.button(Button(toUi(r), act)))

  private def add(m: Me): Unit =
    ui.ask("加好友：输入对方的名字", lockedName(m).filterNot(n => m.view.friends.exists(_.name == n)).getOrElse(""))(n =>
      ui.social(SocialCmd.AddFriend(n))
    )

  private def remove(): Unit = picked match
    case Some(name) => ui.ask("删除好友：回车确认", name)(n => ui.social(SocialCmd.RemoveFriend(n)))
    case None       => ui.message("先点选要删除的好友。", Tone.Bad)

  private def whisper(all: List[FriendView]): Unit = picked.flatMap(p => all.find(_.name == p)) match
    case Some(f) if f.online =>
      close()
      ui.whisperTo(f.name)
    case Some(f) => ui.message(s"${f.name} 没有在线。", Tone.Bad)
    case None    => ui.message("先点选要私聊的好友。", Tone.Bad)

  /** 锁定的别的玩家的名字 */
  private def lockedName(m: Me): Option[String] =
    ui.client.locked.flatMap(ui.client.actor).map(_.look).collect { case h: Look.Human if h.name != m.name => h.name }

object FriendWindow:
  /** 在线的排前面，各自按加入先后 */
  def sorted(friends: List[FriendView]): List[FriendView] = friends.filter(_.online) ++ friends.filterNot(_.online)

  val PageRows = 8
  val RowH     = 26.0
  val Pad      = 10.0
  val Head     = 40.0
  val BtnW     = 72.0
  val BtnH     = 30.0
  val Gap      = 8.0
  val W        = Pad * 2 + BtnW * 3 + Gap * 2
  val H        = Head + PageRows * RowH + 8 + BtnH + Pad

  /** 名单空着时的说明 */
  val Hints = List(
    "还没有好友。",
    "锁定玩家后点信息卡上的「加好友」，",
    "或点下面「添加」输入名字（不用在线）。",
    "好友上线、下线时会提示你。",
    "聊天打「/名字 内容」是私聊。"
  )
