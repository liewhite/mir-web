package mir.server.http

import mir.protocol.{ClientCmd, SessionCmd}
import mir.server.{Account, LinkId}
import mir.server.auth.{LoginLimiter, SessionTokens}
import mir.server.game.{GameServer, LinkEvent}
import mir.server.store.Accounts
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.{ZPipeline, ZStream}

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

/**
 * HTTP 与 WebSocket（接管旧 server/server.mjs 的职责）：
 * - `POST api/login {account,password}` 设会话 cookie（失败 401，同一 IP 10 分钟失败 10 次后 429）；`GET api/me`；`POST api/logout`。
 * - `GET ws`：登录了才升级；一帧一条 JSON（客户端发 ClientCmd，服务端发 ServerMsg）。
 * - `assets/…`：素材，登录后才能取，浏览器缓存一周（version.json 除外）；其余路径是客户端页面（WEB_DIR），不用登录。
 * - 都带 ETag（304），文本类大于 1KB 的 gzip。
 */
final class HttpServer(accounts: Accounts, tokens: SessionTokens, game: GameServer, assetsDir: Path, webDir: Path):
  private val limiter = LoginLimiter()
  private val linkIds = AtomicLong(0)

  val routes: Routes[Any, Nothing] =
    Routes(Method.ANY / trailing -> handler((_: zio.http.Path, req: Request) => dispatch(req)))

  private def dispatch(req: Request): UIO[Response] =
    val p = req.path.encode
    val m = req.method
    val result: Task[Response] = (m, p) match
      case (Method.POST, "/api/login")  => login(req)
      case (Method.POST, "/api/logout") => ZIO.succeed(json(Status.Ok, """{"ok":true}""").addHeader("set-cookie", s"${SessionTokens.Cookie}=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0"))
      case (Method.GET, "/healthz")     => ZIO.succeed(json(Status.Ok, """{"ok":true}"""))
      case (Method.GET, "/api/me") =>
        account(req).map(_.fold(unauthorized)(a => json(Status.Ok, Map("account" -> a.name).toJson)))
      case (Method.GET, "/ws") => account(req).flatMap(_.fold(ZIO.succeed(unauthorized))(webSocket))
      case (Method.GET | Method.HEAD, _) if p == "/assets" || p.startsWith("/assets/") =>
        if session(req).isEmpty then ZIO.succeed(unauthorized)
        else
          val rel = req.path.segments.drop(1)
          val cache = if rel == Chunk("version.json") then "private, no-cache" else "private, max-age=604800"
          StaticFiles.serve(req, assetsDir, rel, cache)
      case (Method.GET | Method.HEAD, _) =>
        val rel = if req.path.segments.isEmpty then Chunk("index.html") else req.path.segments
        StaticFiles.serve(req, webDir, rel, "private, no-cache")
      case _ => ZIO.succeed(json(Status.MethodNotAllowed, """{"error":"method"}"""))
    result.catchAllCause { c =>
      ZIO.logErrorCause(s"$m $p 出错", c).as(json(Status.InternalServerError, """{"error":"internal"}"""))
    }

  // ---------------- 登录 ----------------

  private final case class LoginReq(account: String, password: String)
  private given JsonDecoder[LoginReq] = DeriveJsonDecoder.gen

  private def login(req: Request): Task[Response] =
    val ip  = clientIp(req)
    val now = java.lang.System.currentTimeMillis()
    if limiter.blocked(ip, now) then ZIO.succeed(json(Status.TooManyRequests, """{"error":"too many attempts"}"""))
    else
      req.body.asString.flatMap { body =>
        body.fromJson[LoginReq] match
          case Left(_) => ZIO.succeed(json(Status.BadRequest, """{"error":"bad request"}"""))
          case Right(r) =>
            ZIO.attemptBlocking(accounts.authenticate(r.account, r.password)).map {
              case None =>
                limiter.failed(ip, now)
                json(Status.Unauthorized, """{"error":"bad account or password"}""")
              case Some(acc) =>
                val secure = if req.rawHeader("x-forwarded-proto").contains("https") then "; Secure" else ""
                val cookie = s"${SessionTokens.Cookie}=${tokens.issue(acc.id, now)}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${SessionTokens.Days * 86400}$secure"
                json(Status.Ok, Map("account" -> acc.name).toJson).addHeader("set-cookie", cookie)
            }
      }

  private def session(req: Request) =
    req.cookie(SessionTokens.Cookie).flatMap(c => tokens.verify(c.content, java.lang.System.currentTimeMillis()))

  /** 登录了且账号还在。 */
  private def account(req: Request): Task[Option[Account]] =
    session(req).fold(ZIO.none)(id => ZIO.attemptBlocking(accounts.get(id)))

  private def clientIp(req: Request): String =
    req.rawHeader("x-forwarded-for").map(_.split(',').head.trim)
      .orElse(req.remoteAddress.map(_.getHostAddress))
      .getOrElse("")

  // ---------------- WebSocket ----------------

  private def webSocket(acc: Account): UIO[Response] =
    val link   = WsLink(LinkId(linkIds.incrementAndGet()), acc)
    val closed = AtomicBoolean(false)
    def close  = ZIO.succeed(if closed.compareAndSet(false, true) then game.post(LinkEvent.Closed(link)))
    Handler.webSocket { channel =>
      ZIO.scoped {
        for
          _ <- link.pump(channel).ignore.forkScoped
          _ <- channel.receiveAll {
                 case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                   ZIO.succeed(game.post(LinkEvent.Opened(link)))
                 case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
                   text.fromJson[ClientCmd] match
                     case Right(cmd) => ZIO.succeed(game.post(LinkEvent.Received(link, cmd)))
                     case Left(e)    => ZIO.succeed(link.send(mir.protocol.ServerMsg.Refused(s"看不懂的指令：$e")))
                 case ChannelEvent.Unregistered => close
                 case _                         => ZIO.unit
               }.ensuring(close)
        yield ()
      }
    }.toResponse

  // ---------------- 小工具 ----------------

  private def json(status: Status, body: String): Response =
    Response(status = status, body = Body.fromString(body))
      .addHeader("content-type", "application/json")
      .addHeader("cache-control", "no-store")

  private def unauthorized = json(Status.Unauthorized, """{"error":"login required"}""")

/** 静态文件：ETag、304、gzip、缓存头。 */
object StaticFiles:
  private val types = Map(
    "html" -> "text/html; charset=utf-8", "css" -> "text/css; charset=utf-8", "js" -> "text/javascript; charset=utf-8",
    "map" -> "application/json", "json" -> "application/json", "png" -> "image/png", "jpg" -> "image/jpeg",
    "webp" -> "image/webp", "bin" -> "application/octet-stream", "wav" -> "audio/wav", "m4a" -> "audio/mp4",
    "mp3" -> "audio/mpeg", "ico" -> "image/x-icon", "webmanifest" -> "application/manifest+json", "wasm" -> "application/wasm"
  )
  private val compressible = Set("html", "css", "js", "map", "json", "bin", "webmanifest")

  def serve(req: Request, root: Path, segments: Chunk[String], cache: String): Task[Response] =
    val safe = segments.nonEmpty && segments.forall(s => s.nonEmpty && s != "." && s != ".." && !s.contains('/') && !s.contains('\\'))
    if !safe then ZIO.succeed(notFound)
    else
      val file = segments.foldLeft(root)(_.resolve(_))
      ZIO.attemptBlocking(Option.when(Files.isRegularFile(file))((Files.size(file), Files.getLastModifiedTime(file).toMillis))).map {
        case None => notFound
        case Some((size, mtime)) =>
          val ext  = file.getFileName.toString.split('.').lastOption.getOrElse("").toLowerCase
          val etag = s"\"${java.lang.Long.toString(size, 36)}-${java.lang.Long.toString(mtime, 36)}\""
          val base = Response(status = Status.Ok)
            .addHeader("content-type", types.getOrElse(ext, "application/octet-stream"))
            .addHeader("etag", etag)
            .addHeader("cache-control", cache)
            .addHeader("x-content-type-options", "nosniff")
          if req.rawHeader("if-none-match").contains(etag) then base.status(Status.NotModified)
          else
            val gzip = compressible(ext) && size > 1024 && req.rawHeader("accept-encoding").exists(_.contains("gzip"))
            val head = req.method == Method.HEAD
            if gzip then
              val r = base.addHeader("content-encoding", "gzip").addHeader("vary", "accept-encoding")
              if head then r else r.copy(body = Body.fromStreamChunked(ZStream.fromPath(file).via(ZPipeline.gzip())))
            else
              val r = base.addHeader("content-length", size.toString)
              if head then r else r.copy(body = Body.fromStreamChunked(ZStream.fromPath(file)))
      }

  private val notFound =
    Response(status = Status.NotFound, body = Body.fromString("""{"error":"not found"}""")).addHeader("content-type", "application/json")
