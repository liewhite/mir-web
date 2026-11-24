package mir.social

import mir.protocol.*

/**
 * 好友名单（#1221）。1.76 原版没有好友功能（客户端素材里也没有好友窗口的图），这里做一个简单的：
 * - 名单存在角色存档里（SocialPrefs.friends），单向，加谁不用对方同意；名字必须是已有的角色（不用在线）。
 * - 上线时收到整份名单和各自在不在线；名单上的人上线、下线时收到提示和新的名单。
 * - 最多 Friends.Max 人。
 */
final class Friends(book: Book):
  import Friends.*

  def viewOf(name: String): List[FriendView] = names(name).map(f => FriendView(f, book.isOnline(f)))

  /** 上线：自己的名单；announce 时（不是顶号、重连）告诉加了他的人 */
  def online(name: String, announce: Boolean): Vector[Letter] =
    Vector(list(name)) ++ (if announce then tell(name, "上线了") else Vector.empty)

  /** 下线（已经从 book 里去掉之后调）：告诉加了他的人 */
  def offline(name: String): Vector[Letter] = tell(name, "下线了")

  def add(me: String, raw: String, known: String => Option[String]): Vector[Letter] =
    val n   = raw.trim
    val cur = names(me)
    if n.isEmpty then Social.refuse(me, "请输入要加的好友名字。")
    else
      known(n) match
        case None                             => Social.refuse(me, s"没有叫 $n 的角色。")
        case Some(name) if name == me         => Social.refuse(me, "不能加自己为好友。")
        case Some(name) if cur.contains(name) => Social.refuse(me, s"$name 已经是你的好友了。")
        case Some(_) if cur.size >= Max       => Social.refuse(me, s"好友最多 $Max 个，先删掉几个再加。")
        case Some(name) =>
          book.update(me)(p => p.copy(friends = p.friends :+ name))
          Vector(list(me), Letter(me, ServerMsg.Notice(s"已把 $name 加为好友。", Tone.System)))

  def remove(me: String, raw: String): Vector[Letter] =
    names(me).find(_.equalsIgnoreCase(raw.trim)) match
      case Some(name) =>
        book.update(me)(p => p.copy(friends = p.friends.filterNot(_ == name)))
        Vector(list(me), Letter(me, ServerMsg.Notice(s"已把 $name 从好友里删掉。", Tone.System)))
      case None => Social.refuse(me, s"${raw.trim} 不在你的好友名单里。")

  // ---------------- 内部 ----------------

  private def names(name: String): List[String] = book.get(name).fold(Nil)(_.friends)

  private def list(name: String): Letter = Letter(name, ServerMsg.Friends(viewOf(name)))

  /** 告诉把 name 加为好友的在线的人：一句提示和新的名单 */
  private def tell(name: String, what: String): Vector[Letter] =
    book.all.collect { case (w, p) if w != name && p.friends.contains(name) => w }.toVector
      .flatMap(w => Vector(list(w), Letter(w, ServerMsg.Notice(s"好友 $name $what。", Tone.System))))

object Friends:
  /** 好友名单最多几人 */
  val Max = 50
