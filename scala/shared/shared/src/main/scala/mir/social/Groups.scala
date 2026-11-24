package mir.social

import mir.model.SocialPrefs
import mir.protocol.*
import mir.rules.GroupRules
import mir.world.Company

import scala.collection.mutable

/** 一条要发的消息：发给哪个在线角色（名字全服唯一）。 */
final case class Letter(to: String, msg: ServerMsg)

/** 社交这边查得到的一个在线角色：规范的名字（大小写以角色名为准）、活着没有。 */
final case class Seen(name: String, alive: Boolean)

/**
 * 组队（#1220，照原版 M2Server ObjPlay.pas ClientCreateGroup / ClientAddGroupMember / ClientDelGroupMember / ClientGroupClose、
 * ObjBase.pas DelMember / LeaveGroup / CancelGroup）。
 *
 * 队伍跨地图，所以归服务端而不在某张图的世界里；世界只经 `Company.mates` 查队友来分经验。
 * 人按角色名字认（全服唯一）。只在一个线程里用；每个操作返回要发出去的消息（Letter），由调用方送到连接。
 *
 * 规则（原版）：
 * - 「允许组队」关着的人不能被拉进组；建组的人自动打开。队员关掉等于退出小组；队长关不掉（要「删除」自己）。
 * - 建组：自己不在组里，对方在线、活着、不在组里、允许组队；自己当队长。加人：只有队长能加，最多 GroupRules.MaxMembers 人。
 * - 删人：只有队长能删；删自己 = 解散全队。删完只剩一人也解散。
 * - 下线：队员下线就退出；队长下线全队解散（原版 Disappear → DelMember(Self)，不转让队长）。
 */
final class Groups extends Company:
  /** 在线角色的社交偏好（上线时从存档读进来，存档时取当前值） */
  private val prefs = mutable.Map.empty[String, SocialPrefs]
  /** 队长 → 全队（第一个是队长，按入队先后） */
  private val groups   = mutable.Map.empty[String, Vector[String]]
  /** 队员 → 队长 */
  private val leaderOf = mutable.Map.empty[String, String]

  def mates(name: String): Vector[String] = leaderOf.get(name).flatMap(groups.get).getOrElse(Vector.empty)

  /** 在线角色的社交偏好；不在线为空 */
  def prefsOf(name: String): Option[SocialPrefs] = prefs.get(name)

  def viewOf(name: String): GroupView = GroupView(prefs.get(name).exists(_.allowGroup), mates(name).toList)

  /** 上线（已在线的再进一次，如顶号、重连，保留现在的状态）：告诉他自己的组队状态。 */
  def online(name: String, saved: SocialPrefs): Vector[Letter] =
    if !prefs.contains(name) then prefs(name) = saved
    Vector(state(name))

  /** 下线：退出小组（队长下线全队解散），忘掉偏好。调用方要先存档（取 prefsOf）再调它。 */
  def offline(name: String): Vector[Letter] =
    val out = leaderOf.get(name).fold(Vector.empty[Letter])(l => remove(l, name))
    prefs.remove(name)
    out.filterNot(_.to == name)

  /** 处理一个组队指令；find 查在线角色（按名字，查不到为空）。 */
  def handle(me: String, cmd: SocialCmd, find: String => Option[Seen]): Vector[Letter] =
    def refuse(why: String) = Vector(Letter(me, ServerMsg.Refused(why)))
    /** 能拉进组的人：在线、活着、不是自己、不在组里、允许组队 */
    def recruit(name: String)(join: String => Vector[Letter]): Vector[Letter] =
      find(name.trim) match
        case Some(s) if s.alive && s.name != me =>
          if leaderOf.contains(s.name) then refuse(s"${s.name} 已经在别的小组里了。")
          else if !prefs.get(s.name).exists(_.allowGroup) then refuse(s"${s.name} 没有允许组队。")
          else join(s.name)
        case _ => refuse(s"${name.trim} 不在线或已经死了，加不进小组。")
    cmd match
      case SocialCmd.AllowGroup(true) =>
        setAllow(me, true)
        Vector(state(me))
      case SocialCmd.AllowGroup(false) =>
        leaderOf.get(me) match
          case Some(l) if l == me =>
            refuse("你是队长：要退出小组，请在组队窗口里「删除」自己（全队解散）。") :+ state(me)
          case Some(l) =>
            setAllow(me, false)
            remove(l, me)
          case None =>
            setAllow(me, false)
            Vector(state(me))
      case SocialCmd.CreateGroup(name) =>
        if leaderOf.contains(me) then refuse("你已经在小组里了。")
        else
          recruit(name) { other =>
            groups(me) = Vector(me, other)
            leaderOf(me) = me
            leaderOf(other) = me
            setAllow(me, true)
            everyone(me, s"$me 建立了小组，$other 加入了小组。")
          }
      case SocialCmd.AddMember(name) =>
        if !leaderOf.get(me).contains(me) then refuse("只有队长才能加人。")
        else if mates(me).size >= GroupRules.MaxMembers then refuse(s"小组已经满了（最多 ${GroupRules.MaxMembers} 人）。")
        else
          recruit(name) { other =>
            groups(me) = groups(me) :+ other
            leaderOf(other) = me
            everyone(me, s"$other 加入了小组。")
          }
      case SocialCmd.RemoveMember(name) =>
        if !leaderOf.get(me).contains(me) then refuse("只有队长才能删人。")
        else
          mates(me).find(_.equalsIgnoreCase(name.trim)) match
            case Some(who) => remove(me, who)
            case None      => refuse(s"${name.trim} 不在本小组里。")
      case SocialCmd.GroupSay(text) =>
        val t = text.trim
        if t.isEmpty then Vector.empty
        else if !leaderOf.contains(me) then refuse("你还没有加入小组（「!!」开头是队伍聊天）。")
        else mates(me).map(Letter(_, ServerMsg.Chat(me, t, ChatKind.Group)))

  // ---------------- 内部 ----------------

  private def setAllow(name: String, on: Boolean): Unit =
    prefs.updateWith(name)(_.map(_.copy(allowGroup = on))): Unit

  private def state(name: String): Letter = Letter(name, ServerMsg.Group(viewOf(name)))

  /** 告诉全队一件事并刷新各人的组队状态。 */
  private def everyone(leader: String, note: String): Vector[Letter] =
    groups.getOrElse(leader, Vector.empty).flatMap(n => Vector(state(n), Letter(n, ServerMsg.Notice(note, Tone.System))))

  /** 队长那组去掉 who（是队长自己就全队散）；剩不到 2 人也散。 */
  private def remove(leader: String, who: String): Vector[Letter] =
    val all     = groups.getOrElse(leader, Vector.empty)
    val leaving = if who == leader then all else all.filter(_ == who)
    val rest    = all.filterNot(leaving.contains)
    if rest.size <= 1 then
      groups.remove(leader)
      all.foreach(leaderOf.remove)
      all.flatMap(n => Vector(state(n), Letter(n, ServerMsg.Notice("小组解散了。", Tone.System))))
    else
      groups(leader) = rest
      leaving.foreach(leaderOf.remove)
      leaving.flatMap(n => Vector(state(n), Letter(n, ServerMsg.Notice("你离开了小组。", Tone.System)))) ++
        rest.flatMap(n => Vector(state(n), Letter(n, ServerMsg.Notice(s"${leaving.mkString("、")} 离开了小组。", Tone.System))))
