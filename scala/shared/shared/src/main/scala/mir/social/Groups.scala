package mir.social

import mir.protocol.*
import mir.rules.GroupRules

import scala.collection.mutable

/**
 * 组队（#1220，照原版 M2Server ObjPlay.pas ClientCreateGroup / ClientAddGroupMember / ClientDelGroupMember / ClientGroupClose、
 * ObjBase.pas DelMember / LeaveGroup / CancelGroup）。
 *
 * 队伍跨地图，所以归服务端的 Social 而不在某张图的世界里；世界只经 `Company.mates` 查队友来分经验。
 * 「允许组队」开关在 book（在线角色的社交偏好）里。只在一个线程里用；每个操作返回要发出去的消息（Letter）。
 *
 * 规则（原版）：
 * - 「允许组队」关着的人不能被拉进组；建组的人自动打开。队员关掉等于退出小组；队长关不掉（要「删除」自己）。
 * - 建组：自己不在组里，对方在线、活着、不在组里、允许组队；自己当队长。加人：只有队长能加，最多 GroupRules.MaxMembers 人。
 * - 删人：只有队长能删；删自己 = 解散全队。删完只剩一人也解散。
 * - 下线：队员下线就退出；队长下线全队解散（原版 Disappear → DelMember(Self)，不转让队长）。
 */
final class Groups(book: Book):
  /** 队长 → 全队（第一个是队长，按入队先后） */
  private val groups   = mutable.Map.empty[String, Vector[String]]
  /** 队员 → 队长 */
  private val leaderOf = mutable.Map.empty[String, String]

  def mates(name: String): Vector[String] = leaderOf.get(name).flatMap(groups.get).getOrElse(Vector.empty)

  def viewOf(name: String): GroupView = GroupView(book.get(name).exists(_.allowGroup), mates(name).toList)

  /** 上线（偏好已进 book）：告诉他自己的组队状态。 */
  def online(name: String): Vector[Letter] = Vector(state(name))

  /** 下线：退出小组（队长下线全队解散）。 */
  def offline(name: String): Vector[Letter] =
    leaderOf.get(name).fold(Vector.empty[Letter])(l => removeFrom(l, name))

  /** 允许组队开关 */
  def allow(me: String, on: Boolean): Vector[Letter] =
    if on then
      setAllow(me, true)
      Vector(state(me))
    else
      leaderOf.get(me) match
        case Some(l) if l == me =>
          refuse(me, "你是队长：要退出小组，请在组队窗口里「删除」自己（全队解散）。") :+ state(me)
        case Some(l) =>
          setAllow(me, false)
          removeFrom(l, me)
        case None =>
          setAllow(me, false)
          Vector(state(me))

  /** 建组；find 查在线角色（按名字，查不到为空） */
  def create(me: String, name: String, find: String => Option[Seen]): Vector[Letter] =
    if leaderOf.contains(me) then refuse(me, "你已经在小组里了。")
    else
      recruit(me, name, find) { other =>
        groups(me) = Vector(me, other)
        leaderOf(me) = me
        leaderOf(other) = me
        setAllow(me, true)
        everyone(me, s"$me 建立了小组，$other 加入了小组。")
      }

  /** 队长拉人 */
  def add(me: String, name: String, find: String => Option[Seen]): Vector[Letter] =
    if !leaderOf.get(me).contains(me) then refuse(me, "只有队长才能加人。")
    else if mates(me).size >= GroupRules.MaxMembers then refuse(me, s"小组已经满了（最多 ${GroupRules.MaxMembers} 人）。")
    else
      recruit(me, name, find) { other =>
        groups(me) = groups(me) :+ other
        leaderOf(other) = me
        everyone(me, s"$other 加入了小组。")
      }

  /** 队长删人；删自己就解散 */
  def remove(me: String, name: String): Vector[Letter] =
    if !leaderOf.get(me).contains(me) then refuse(me, "只有队长才能删人。")
    else
      Social.pick(mates(me), name) match
        case Some(who) => removeFrom(me, who)
        case None      => refuse(me, s"${name.trim} 不在本小组里。")

  /** 全部小组（每组第一个是队长）：停服快照用（#1279） */
  def all: Vector[Vector[String]] = groups.values.toVector

  /** 开服接上停服前的小组（队员还没重连）。和已有小组重人的、不到 2 人的不要。 */
  def restore(parties: Seq[Vector[String]]): Unit =
    parties.filter(g => g.size >= 2 && g.distinct.size == g.size && !g.exists(leaderOf.contains)).foreach { g =>
      groups(g.head) = g
      g.foreach(leaderOf(_) = g.head)
    }

  /** 在小组里的全部人 */
  def members: Iterable[String] = leaderOf.keys

  /** 队伍聊天 */
  def say(me: String, text: String): Vector[Letter] =
    val t = text.trim
    if t.isEmpty then Vector.empty
    else if !leaderOf.contains(me) then refuse(me, "你还没有加入小组（「!!」开头是队伍聊天）。")
    else mates(me).map(Letter(_, ServerMsg.Chat(me, t, ChatKind.Group)))

  // ---------------- 内部 ----------------

  private def refuse(me: String, why: String) = Social.refuse(me, why)

  /** 能拉进组的人：在线、活着、不是自己、不在组里、允许组队 */
  private def recruit(me: String, name: String, find: String => Option[Seen])(join: String => Vector[Letter]): Vector[Letter] =
    find(name.trim) match
      case Some(s) if s.alive && s.name != me =>
        if leaderOf.contains(s.name) then refuse(me, s"${s.name} 已经在别的小组里了。")
        else if !book.get(s.name).exists(_.allowGroup) then refuse(me, s"${s.name} 没有允许组队。")
        else join(s.name)
      case _ => refuse(me, s"${name.trim} 不在线或已经死了，加不进小组。")

  private def setAllow(name: String, on: Boolean): Unit = book.update(name)(_.copy(allowGroup = on))

  private def state(name: String): Letter = Letter(name, ServerMsg.Group(viewOf(name)))

  /** 告诉全队一件事并刷新各人的组队状态。 */
  private def everyone(leader: String, note: String): Vector[Letter] =
    groups.getOrElse(leader, Vector.empty).flatMap(n => Vector(state(n), Letter(n, ServerMsg.Notice(note, Tone.System))))

  /** 队长那组去掉 who（是队长自己就全队散）；剩不到 2 人也散。 */
  private def removeFrom(leader: String, who: String): Vector[Letter] =
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
