package mir.server.board

import mir.server.{Account, AccountId}

/**
 * 留言板（#1280）：玩家给服主反馈的渠道，全公开——谁都能看，登录了能发帖、回复。
 * 两层：帖子（Topic）和帖子下的回复（Reply），按最后活跃（发帖或最新回复）倒序分页。
 * 署名是发帖那一刻选的名字（自己号上的角色名或账号名），之后改名、删角色都不影响旧帖。
 * 删除只做标记（误删能从库里找回）：作者删自己的，管理员（ServerConfig.boardAdmins）删任何人的；删帖连带它的回复一起不显示。
 */
opaque type TopicId = Long
object TopicId:
  def apply(n: Long): TopicId             = n
  extension (id: TopicId) def value: Long = id

opaque type ReplyId = Long
object ReplyId:
  def apply(n: Long): ReplyId             = n
  extension (id: ReplyId) def value: Long = id

/** 署名：哪个账号发的、显示的名字。 */
final case class Author(account: AccountId, name: String)

final case class Reply(id: ReplyId, author: Author, body: String, at: Long)

final case class Topic(id: TopicId, author: Author, body: String, at: Long, bumpedAt: Long, replies: List[Reply])

/** 一页帖子：第 number 页（从 1 起），共 pages 页、total 帖。 */
final case class TopicPage(number: Int, pages: Int, total: Int, topics: List[Topic])

/** 要删的东西 */
enum Target:
  case OfTopic(id: TopicId)
  case OfReply(id: ReplyId)

/** 看留言板的人：没登录的游客，或登录了的玩家（能用哪些名字署名、是不是管理员）。 */
enum Viewer:
  case Guest
  case Member(account: Account, names: List[String], admin: Boolean)

  /** 能不能删这个作者的东西：作者本人或管理员。 */
  def canDelete(author: AccountId): Boolean = this match
    case Guest                 => false
    case Member(acc, _, admin) => admin || acc.id == author

/** 发帖、回复、删除被拒的原因（message 给玩家看）。 */
enum Rejected(val message: String):
  case Empty                extends Rejected("内容不能为空")
  case TooLong(max: Int)    extends Rejected(s"内容最多 $max 字")
  case BadName              extends Rejected("只能用自己的角色名或账号名署名")
  case NotFound             extends Rejected("帖子不存在或已删除")
  case Forbidden            extends Rejected("只能删自己发的")

/** 留言板的规则：字数、署名、每页几帖。 */
object BoardRules:
  val MaxTopic   = 1000
  val MaxReply   = 500
  val PageSize   = 20

  /** 正文：去掉首尾空白后非空、不超过 max 字（按 Unicode 字符数）；控制字符除换行、制表外都去掉，连续空行压成一行。 */
  def body(raw: String, max: Int): Either[Rejected, String] =
    val clean = raw
      .replace("\r\n", "\n")
      .filter(c => c == '\n' || c == '\t' || !c.isControl)
      .replaceAll("\n{3,}", "\n\n")
      .trim
    val len = clean.codePointCount(0, clean.length)
    if clean.isEmpty then Left(Rejected.Empty)
    else if len > max then Left(Rejected.TooLong(max))
    else Right(clean)

  /** 署名只能是自己能用的名字之一（游客发不了帖，HTTP 层先挡了）。 */
  def author(viewer: Viewer.Member, name: String): Either[Rejected, Author] =
    viewer.names.find(_ == name.trim).map(Author(viewer.account.id, _)).toRight(Rejected.BadName)

  /** 页数：至少 1 页；请求的页码夹到 [1, pages]。 */
  def pages(total: Int): Int = math.max(1, (total + PageSize - 1) / PageSize)
  def clampPage(asked: Int, total: Int): Int = math.min(math.max(1, asked), pages(total))
