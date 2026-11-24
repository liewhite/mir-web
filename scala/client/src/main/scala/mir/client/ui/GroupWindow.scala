package mir.client.ui

import mir.client.input.Pt
import mir.protocol.{GroupView, Look, SocialCmd}

/**
 * 组队窗口（#1220，照原版 FState.pas DGroupDlg：prguse 120 底图，「允许组队」勾选框、成员名单、新建/添加/删除三个键）。
 * 底栏组队键或 P 键打开。原版三个键都弹框输入名字；这里输入框里先填好锁定的玩家（新建、添加）或点选的成员（删除），
 * 手机上不用打字。名单第一个是队长，其余两列排在下面（原版坐标）。一切以服务端发来的组队状态（view.group）为准。
 */
final class GroupWindow(ui: ClassicUi) extends ToggleWindow:
  import GroupWindow.*
  import ui.g

  /** 点选的成员（删除用） */
  private var picked = Option.empty[String]

  override def show(): Unit =
    picked = None
    super.show()

  def draw(m: Me): Unit =
    val group     = m.view.group
    val (sw, sh)  = g.size(Gfx.Main, 120).getOrElse((276, 242))
    val o         = ui.dialogOrigin(sw, sh)
    picked = picked.filter(group.has)
    ui.area(Rect(o.x, o.y, sw, sh))
    g.img(Gfx.Main, 120, o.x, o.y)
    ui.button(Button(Rect(o.x + 260, o.y, 16, 23), () => close(), img = Some(64)))
    // 允许组队：开着画勾（122），按下时画 121
    val allow = Rect(o.x + 20, o.y + 18, 20, 19)
    ui.hit(allow)(ui.social(SocialCmd.AllowGroup(!group.allowGroup)))
    if ui.isPressed(allow) then g.img(Gfx.Main, 121, allow.x, allow.y)
    else if group.allowGroup then g.img(Gfx.Main, 122, allow.x, allow.y)
    drawMembers(o, group)
    ui.button(Button(Rect(o.x + 22, o.y + 203, 72, 19), () => create(m, group), img = Some(123)))
    ui.button(Button(Rect(o.x + 97, o.y + 203, 72, 19), () => add(m, group), img = Some(124)))
    ui.button(Button(Rect(o.x + 172, o.y + 203, 72, 19), () => remove(m, group), img = Some(125)))

  private def drawMembers(o: Pt, group: GroupView): Unit =
    if group.members.isEmpty then
      Hints.zipWithIndex.foreach((t, i) => g.text(t, o.x + 28, o.y + 98 + 16 * i, "#8a8a8a"))
    else
      group.members.zipWithIndex.foreach { (name, i) =>
        val (x, y) = memberAt(i)
        val r      = Rect(o.x + x - 2, o.y + y - 1, 98, 15)
        if picked.contains(name) then g.rect(r.x, r.y, r.w, r.h, "rgba(216,162,74,.35)")
        g.text(if i == 0 then s"$name（队长）" else name, o.x + x, o.y + y, "#c0c0c0")
        ui.hit(r) { picked = if picked.contains(name) then None else Some(name) }
      }

  private def create(m: Me, group: GroupView): Unit =
    if group.members.isEmpty then ask("新建小组：输入对方的名字", lockedName(m))(n => ui.social(SocialCmd.CreateGroup(n)))
    else ui.message("你已经在小组里了。", mir.protocol.Tone.Bad)

  private def add(m: Me, group: GroupView): Unit =
    if group.members.nonEmpty then ask("添加队员：输入对方的名字", lockedName(m).filterNot(group.has))(n => ui.social(SocialCmd.AddMember(n)))
    else ui.message("还没有小组：先点「新建」。", mir.protocol.Tone.Bad)

  private def remove(m: Me, group: GroupView): Unit =
    if group.members.nonEmpty then
      val who = picked.orElse(lockedName(m).filter(group.has))
      ask("删除队员：输入名字（队长删自己 = 解散小组）", who)(n => ui.social(SocialCmd.RemoveMember(n)))

  private def ask(hint: String, prefill: Option[String])(send: String => Unit): Unit =
    ui.ask(hint, prefill.getOrElse(""))(send)

  /** 锁定的别的玩家的名字 */
  private def lockedName(m: Me): Option[String] =
    ui.client.locked.flatMap(ui.client.actor).map(_.look).collect { case h: Look.Human if h.name != m.name => h.name }

object GroupWindow:
  /** 第 i 个成员名字的位置（相对窗口）：队长一行，其余两列 */
  def memberAt(i: Int): (Double, Double) =
    if i == 0 then (28.0, 80.0) else (28.0 + ((i - 1) % 2) * 100, 96.0 + ((i - 1) / 2) * 16)

  /** 还没组队时名单区的说明（原版没有） */
  val Hints = List(
    "勾上「允许组队」才能被拉进组。",
    "锁定玩家后点信息卡上的「组队」，",
    "或点「新建」输入对方名字。",
    "聊天打「!!」开头是队伍聊天。",
    "同图 12 格内的队友分经验有加成。"
  )
