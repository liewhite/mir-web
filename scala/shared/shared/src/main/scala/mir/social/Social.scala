package mir.social

import mir.model.SocialPrefs
import mir.protocol.*
import mir.world.Company

import scala.collection.mutable

/** 一条要发的消息：发给哪个在线角色（名字全服唯一）。 */
final case class Letter(to: String, msg: ServerMsg)

/** 社交这边查得到的一个在线角色：规范的名字（大小写以角色名为准）、活着没有。 */
final case class Seen(name: String, alive: Boolean)

/** 社交这边查人的两种方式（由服务端接上）。 */
final case class Directory(
    /** 按名字（不分大小写）找在线角色 */
    online: String => Option[Seen],
    /** 按名字（不分大小写）找已有的角色（在不在线都算），给出规范的名字；加好友用 */
    known: String => Option[String]
)

object Directory:
  /** 只认在线的人（本机单人、测试） */
  def onlineOnly(online: String => Option[Seen]): Directory = Directory(online, n => online(n).map(_.name))

/**
 * 在线角色的社交偏好（存档里那份：允许组队、好友名单），组队、好友共用；上线时从存档读进来，存档时取当前值。
 * 有记录就是在线。只在一个线程里用。
 */
final class Book:
  private val prefs = mutable.LinkedHashMap.empty[String, SocialPrefs]

  /** 上线；已在线的（顶号、重连）保留现在的值 */
  def enter(name: String, saved: SocialPrefs): Unit = if !prefs.contains(name) then prefs(name) = saved
  def leave(name: String): Unit                     = prefs.remove(name): Unit
  def get(name: String): Option[SocialPrefs]        = prefs.get(name)
  def isOnline(name: String): Boolean               = prefs.contains(name)
  def update(name: String)(f: SocialPrefs => SocialPrefs): Unit = prefs.updateWith(name)(_.map(f)): Unit
  def all: Iterable[(String, SocialPrefs)]          = prefs

/**
 * 服务端的社交：牵涉别的在线玩家、不管在哪张图的事（组队、私聊、好友）。人按角色名字认（全服唯一）。
 * 只在一个线程里用；每个操作返回要发出去的消息（Letter），由调用方送到连接。
 * 世界只经 Company.mates 查队友来分经验。
 */
final class Social extends Company:
  private val book    = Book()
  private val groups  = Groups(book)
  private val friends = Friends(book)

  def mates(name: String): Vector[String] = groups.mates(name)

  /** 在线角色的社交偏好；不在线为空 */
  def prefsOf(name: String): Option[SocialPrefs] = book.get(name)

  def groupView(name: String): GroupView         = groups.viewOf(name)
  def friendsView(name: String): List[FriendView] = friends.viewOf(name)

  /** 上线（已在线的再进一次，如顶号、重连，保留现在的状态）：告诉他自己的组队状态、好友名单；第一次上线告诉加了他的人。 */
  def online(name: String, saved: SocialPrefs): Vector[Letter] =
    val fresh = !book.isOnline(name)
    book.enter(name, saved)
    groups.online(name) ++ friends.online(name, announce = fresh)

  /** 下线：退出小组（队长下线全队解散），告诉加了他的人，忘掉偏好。调用方要先存档（取 prefsOf）再调它。 */
  def offline(name: String): Vector[Letter] =
    val left = groups.offline(name)
    book.leave(name)
    (left ++ friends.offline(name)).filterNot(_.to == name)

  /** 处理一个社交指令。 */
  def handle(me: String, cmd: SocialCmd, dir: Directory): Vector[Letter] = cmd match
    case SocialCmd.AllowGroup(on)      => groups.allow(me, on)
    case SocialCmd.CreateGroup(name)   => groups.create(me, name, dir.online)
    case SocialCmd.AddMember(name)     => groups.add(me, name, dir.online)
    case SocialCmd.RemoveMember(name)  => groups.remove(me, name)
    case SocialCmd.GroupSay(text)      => groups.say(me, text)
    case SocialCmd.Whisper(to, text)   => Whispers.send(me, to, text, dir.online)
    case SocialCmd.AddFriend(name)     => friends.add(me, name, dir.known)
    case SocialCmd.RemoveFriend(name)  => friends.remove(me, name)

object Social:
  def refuse(me: String, why: String): Vector[Letter] = Vector(Letter(me, ServerMsg.Refused(why)))
