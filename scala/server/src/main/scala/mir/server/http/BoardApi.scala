package mir.server.http

import mir.server.Account
import mir.server.auth.RateLimiter
import mir.server.board.*
import mir.server.store.{BoardStore, Characters}
import zio.*
import zio.http.*
import zio.json.*

/**
 * 留言板接口（#1280），页面是 WEB_DIR 里的 board.html：
 * - `GET api/board?page=N`：不登录也能看。回 `{page,pages,total,me,topics}`；me 是登录了的人能用的署名和是不是管理员（游客为 null），
 *   每帖、每条回复带 canDelete（看的人能不能删它）。
 * - `POST api/board/topics {name,body}`、`POST api/board/topics/<帖>/replies {name,body}`：要登录，name 是署名（自己的角色名或账号名）。
 * - `DELETE api/board/topics/<帖>`、`DELETE api/board/replies/<回复>`：作者或管理员。
 * 出错 `{error}` 是给玩家看的中文：没登录 401、内容不对 400、不能删 403、没有 404、发得太勤 429。
 * 发帖和回复每账号 10 分钟最多 10 条（RateLimiter.boardWrites）；发、删都打日志「留言板：…」。
 */
final class BoardApi(store: BoardStore, chars: Characters, admins: Set[String]):
  import BoardApi.*

  private val writes = RateLimiter.boardWrites()
  private val adminNames = admins.map(_.toLowerCase)

  /** rest 是 `api/board` 后面的路径段。 */
  def route(req: Request, rest: List[String], account: Option[Account]): Task[Response] =
    (req.method, rest) match
      case (Method.GET, Nil) =>
        val asked = req.url.queryParams.getAll("page").headOption.flatMap(_.toIntOption).getOrElse(1)
        viewer(account).flatMap(v => ZIO.attemptBlocking(store.page(asked)).map(p => JsonReply(Status.Ok, render(p, v).toJson)))
      case (Method.POST, List("topics")) =>
        member(account) { m =>
          written(req, m, BoardRules.MaxTopic) { (author, text, now) =>
            ZIO.attemptBlocking(store.post(author, text, now)).flatMap { id =>
              ZIO.logInfo(s"留言板：${author.name}（账号 ${m.account.name}）发帖 #${id.value}：${preview(text)}").as(Right(id.value))
            }
          }
        }
      case (Method.POST, List("topics", Num(topic), "replies")) =>
        member(account) { m =>
          written(req, m, BoardRules.MaxReply) { (author, text, now) =>
            ZIO.attemptBlocking(store.reply(TopicId(topic), author, text, now)).tap {
              case Right(id) => ZIO.logInfo(s"留言板：${author.name}（账号 ${m.account.name}）回复帖 #$topic（回复 #${id.value}）：${preview(text)}")
              case Left(_)   => ZIO.unit
            }.map(_.map(_.value))
          }
        }
      case (Method.DELETE, List("topics", Num(id)))  => member(account)(delete(Target.OfTopic(TopicId(id)), _))
      case (Method.DELETE, List("replies", Num(id))) => member(account)(delete(Target.OfReply(ReplyId(id)), _))
      case _ => ZIO.succeed(JsonReply(Status.NotFound, """{"error":"not found"}"""))

  /** 登录了的人：能用的署名（没删的角色名，最后是账号名）、是不是管理员。 */
  private def viewer(account: Option[Account]): Task[Viewer] = account match
    case None      => ZIO.succeed(Viewer.Guest)
    case Some(acc) =>
      ZIO.attemptBlocking(chars.list(acc.id).map(_.data.name)).map(names =>
        Viewer.Member(acc, names :+ acc.name, adminNames.contains(acc.name.toLowerCase))
      )

  private def member(account: Option[Account])(f: Viewer.Member => Task[Response]): Task[Response] =
    viewer(account).flatMap {
      case m: Viewer.Member => f(m)
      case Viewer.Guest     => ZIO.succeed(JsonReply(Status.Unauthorized, Map("error" -> "请先登录").toJson))
    }

  /** 发帖、回复共用：限频 → 请求体 → 署名 → 正文规则 → 写库（write 回新编号或被拒）。 */
  private def written(req: Request, m: Viewer.Member, max: Int)(
      write: (Author, String, Long) => Task[Either[Rejected, Long]]
  ): Task[Response] =
    val now = java.lang.System.currentTimeMillis()
    val key = m.account.id.value.toString
    if writes.blocked(key, now) then ZIO.succeed(JsonReply(Status.TooManyRequests, Map("error" -> "发得太勤了，过几分钟再来").toJson))
    else
      req.body.asString.flatMap { raw =>
        if raw.length > MaxBody then ZIO.succeed(JsonReply(Status.RequestEntityTooLarge, Map("error" -> "内容太长").toJson))
        else
          raw.fromJson[Draft] match
            case Left(_) => ZIO.succeed(JsonReply(Status.BadRequest, Map("error" -> "请求格式不对").toJson))
            case Right(d) =>
              val checked = for
                author <- BoardRules.author(m, d.name)
                text   <- BoardRules.body(d.body, max)
              yield (author, text)
              checked match
                case Left(r) => ZIO.succeed(rejected(r))
                case Right((author, text)) =>
                  writes.hit(key, now)
                  write(author, text, now).map {
                    case Right(id) => JsonReply(Status.Ok, Map("id" -> id).toJson)
                    case Left(r)   => rejected(r)
                  }
      }

  private def delete(target: Target, m: Viewer.Member): Task[Response] =
    ZIO.attemptBlocking(store.delete(target, m, java.lang.System.currentTimeMillis())).flatMap {
      case Left(r) => ZIO.succeed(rejected(r))
      case Right(author) =>
        val what = target match
          case Target.OfTopic(t) => s"帖 #${t.value}"
          case Target.OfReply(r) => s"回复 #${r.value}"
        ZIO.logInfo(s"留言板：账号 ${m.account.name} 删了 ${author.name} 的$what").as(JsonReply(Status.Ok, """{"ok":true}"""))
    }

object BoardApi:
  /** 请求体上限（字符）：正文最多 1000 字，留足 JSON 转义的余量 */
  val MaxBody = 16 * 1024

  /** 发帖、回复的请求体 */
  private final case class Draft(name: String, body: String)
  private given JsonDecoder[Draft] = DeriveJsonDecoder.gen

  private object Num:
    def unapply(s: String): Option[Long] = s.toLongOption.filter(_ > 0)

  private def rejected(r: Rejected): Response =
    val status = r match
      case Rejected.Empty | Rejected.TooLong(_) | Rejected.BadName => Status.BadRequest
      case Rejected.NotFound                                       => Status.NotFound
      case Rejected.Forbidden                                      => Status.Forbidden
    JsonReply(status, Map("error" -> r.message).toJson)

  private def preview(text: String): String = ClientError.oneLine(text, 60)

  // ---- 回给页面的 JSON ----

  final case class ReplyView(id: Long, author: String, body: String, at: Long, canDelete: Boolean)
  final case class TopicView(id: Long, author: String, body: String, at: Long, canDelete: Boolean, replies: List[ReplyView])
  final case class MeView(account: String, names: List[String], admin: Boolean)
  final case class PageView(page: Int, pages: Int, total: Int, me: Option[MeView], topics: List[TopicView])
  given JsonEncoder[ReplyView] = DeriveJsonEncoder.gen
  given JsonEncoder[TopicView] = DeriveJsonEncoder.gen
  given JsonEncoder[MeView]    = DeriveJsonEncoder.gen
  given JsonEncoder[PageView]  = DeriveJsonEncoder.gen

  def render(p: TopicPage, v: Viewer): PageView =
    val me = v match
      case Viewer.Member(acc, names, admin) => Some(MeView(acc.name, names, admin))
      case Viewer.Guest                     => None
    PageView(p.number, p.pages, p.total, me, p.topics.map { t =>
      TopicView(t.id.value, t.author.name, t.body, t.at, v.canDelete(t.author.account),
        t.replies.map(r => ReplyView(r.id.value, r.author.name, r.body, r.at, v.canDelete(r.author.account))))
    })
