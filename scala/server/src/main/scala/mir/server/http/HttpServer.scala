package mir.server.http

import mir.protocol.{ClientCmd, SessionCmd}
import mir.server.{Account, LinkId}
import mir.server.auth.{RateLimiter, SessionTokens}
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
 * - `POST api/register {account,password}`：自助注册（完全开放，#1197），成功就已登录（设 cookie）；格式不对 400、重名 409，
 *   正文 `{error}` 是给玩家看的中文；同一 IP 1 小时试 10 次后 429。
 * - `GET ws`：登录了才升级；一帧一条 JSON（客户端发 ClientCmd，服务端发 ServerMsg）。解不开的指令回 Refused 并记日志
 *   「看不懂的指令」（多半是页面和服务端版本不一致；每账号每分钟最多记 5 条）。
 * - `POST api/client-error {message,stack?,char?,version?}`：登录了才收，玩家浏览器里的报错打进日志（见 `ClientError`）；
 *   成功 204，每账号每小时 30 条、每 IP 每小时 60 条（超了 429，不记），请求体超过 16KB 413。
 * - `assets/…`：素材，登录后才能取（登录画面本身要用的几个文件除外，见 `LoginScreenAssets`），
 *   浏览器缓存一周（version.json 除外）；其余路径是客户端页面（WEB_DIR），不用登录。
 * - 都带 ETag（304），文本类大于 1KB 的 gzip。
 */
final class HttpServer(
    accounts: Accounts,
    tokens: SessionTokens,
    game: GameServer,
    assetsDir: Path,
    webDir: Path,
    pingEveryMs: Long
):
  private val loginLimiter    = RateLimiter.login()
  private val registerLimiter = RateLimiter.register()
  private val reportByAccount = RateLimiter.clientErrorsPerAccount()
  private val reportByIp      = RateLimiter.clientErrorsPerIp()
  private val badCmdLimiter   = RateLimiter.badCommands()
  private val linkIds = AtomicLong(0)

  val routes: Routes[Any, Nothing] =
    Routes(Method.ANY / trailing -> handler((_: zio.http.Path, req: Request) => dispatch(req)))

  private def dispatch(req: Request): UIO[Response] =
    val p = req.path.encode
    val m = req.method
    val result: Task[Response] = (m, p) match
      case (Method.POST, "/api/login")    => login(req)
      case (Method.POST, "/api/register") => register(req)
      case (Method.POST, "/api/client-error") =>
        account(req).flatMap(_.fold(ZIO.succeed(unauthorized))(clientError(req, _)))
      case (Method.POST, "/api/logout") => ZIO.succeed(json(Status.Ok, """{"ok":true}""").addHeader("set-cookie", s"${SessionTokens.Cookie}=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0"))
      case (Method.GET, "/healthz")     => ZIO.succeed(json(Status.Ok, """{"ok":true}"""))
      case (Method.GET, "/api/me") =>
        account(req).map(_.fold(unauthorized)(a => json(Status.Ok, Map("account" -> a.name).toJson)))
      case (Method.GET, "/ws") => account(req).flatMap(_.fold(ZIO.succeed(unauthorized))(webSocket))
      case (Method.GET | Method.HEAD, _) if p == "/assets" || p.startsWith("/assets/") =>
        val rel = req.path.segments.drop(1)
        if session(req).isEmpty && !LoginScreenAssets.allows(rel) then ZIO.succeed(unauthorized)
        else
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

  /** 登录和注册的请求体 */
  private final case class Credentials(account: String, password: String)
  private given JsonDecoder[Credentials] = DeriveJsonDecoder.gen

  private def login(req: Request): Task[Response] =
    limited(req, loginLimiter) { (ip, now, c) =>
      ZIO.attemptBlocking(accounts.authenticate(c.account, c.password)).map {
        case None =>
          loginLimiter.hit(ip, now)
          json(Status.Unauthorized, """{"error":"bad account or password"}""")
        case Some(acc) => signedIn(req, acc, now)
      }
    }

  /** 注册：每次尝试都记一次限频（不管成不成），成功直接登录。 */
  private def register(req: Request): Task[Response] =
    limited(req, registerLimiter) { (ip, now, c) =>
      registerLimiter.hit(ip, now)
      ZIO.attemptBlocking(accounts.add(c.account.trim, c.password)).flatMap {
        case Right(acc) => ZIO.logInfo(s"注册账号 ${acc.name}（$ip）").as(signedIn(req, acc, now))
        case Left(r) =>
          val status = r match
            case Accounts.Rejected.Taken(_)                                => Status.Conflict
            case Accounts.Rejected.BadName | Accounts.Rejected.BadPassword => Status.BadRequest
          ZIO.succeed(json(status, Map("error" -> r.message).toJson))
      }
    }

  /** 先看这个 IP 有没有被限频、请求体对不对，再办事。 */
  private def limited(req: Request, limiter: RateLimiter)(f: (String, Long, Credentials) => Task[Response]): Task[Response] =
    val ip  = clientIp(req)
    val now = java.lang.System.currentTimeMillis()
    if limiter.blocked(ip, now) then ZIO.succeed(json(Status.TooManyRequests, """{"error":"too many attempts"}"""))
    else
      req.body.asString.flatMap { body =>
        body.fromJson[Credentials] match
          case Left(_)  => ZIO.succeed(json(Status.BadRequest, """{"error":"bad request"}"""))
          case Right(c) => f(ip, now, c)
      }

  /** 登录成功：发会话 cookie。 */
  private def signedIn(req: Request, acc: Account, now: Long): Response =
    val secure = if req.rawHeader("x-forwarded-proto").contains("https") then "; Secure" else ""
    val cookie = s"${SessionTokens.Cookie}=${tokens.issue(acc.id, now)}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${SessionTokens.Days * 86400}$secure"
    json(Status.Ok, Map("account" -> acc.name).toJson).addHeader("set-cookie", cookie)

  private def session(req: Request) =
    req.cookie(SessionTokens.Cookie).flatMap(c => tokens.verify(c.content, java.lang.System.currentTimeMillis()))

  /** 登录了且账号还在。 */
  private def account(req: Request): Task[Option[Account]] =
    session(req).fold(ZIO.none)(id => ZIO.attemptBlocking(accounts.get(id)))

  private def clientIp(req: Request): String =
    req.rawHeader("x-forwarded-for").map(_.split(',').head.trim)
      .orElse(req.remoteAddress.map(_.getHostAddress))
      .getOrElse("")

  // ---------------- 客户端报错 ----------------

  private def clientError(req: Request, acc: Account): Task[Response] =
    val ip   = clientIp(req)
    val key  = acc.id.value.toString
    val now  = java.lang.System.currentTimeMillis()
    val tooBig = req.rawHeader("content-length").flatMap(_.toLongOption).exists(_ > ClientError.MaxBody)
    if tooBig then ZIO.succeed(json(Status.RequestEntityTooLarge, """{"error":"too large"}"""))
    else if reportByAccount.blocked(key, now) || reportByIp.blocked(ip, now) then
      ZIO.succeed(json(Status.TooManyRequests, """{"error":"too many reports"}"""))
    else
      req.body.asString.map { body =>
        if body.length > ClientError.MaxBody then json(Status.RequestEntityTooLarge, """{"error":"too large"}""")
        else
          body.fromJson[ClientError] match
            case Left(_) => json(Status.BadRequest, """{"error":"bad request"}""")
            case Right(e) =>
              reportByAccount.hit(key, now)
              reportByIp.hit(ip, now)
              java.lang.System.err.println(ClientError.render(e, acc.name, req.rawHeader("user-agent").getOrElse("")))
              Response(status = Status.NoContent)
      }

  // ---------------- WebSocket ----------------

  private def webSocket(acc: Account): UIO[Response] =
    val link   = WsLink(LinkId(linkIds.incrementAndGet()), acc)
    val closed = AtomicBoolean(false)
    def close  = ZIO.succeed(if closed.compareAndSet(false, true) then game.post(LinkEvent.Closed(link)))
    Handler.webSocket { channel =>
      ZIO.scoped {
        for
          _ <- link.pump(channel).ignore.forkScoped
          // 协议层 ping（#1238）：浏览器自动回 pong，后台标签页也照回；pong 交给游戏线程刷新「最后听到」的时刻
          _ <- ZIO.succeed(link.ping()).repeat(Schedule.spaced(pingEveryMs.millis)).delay(pingEveryMs.millis).forkScoped
          _ <- channel.receiveAll {
                 case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                   ZIO.succeed(game.post(LinkEvent.Opened(link)))
                 case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
                   text.fromJson[ClientCmd] match
                     case Right(cmd) => ZIO.succeed(game.post(LinkEvent.Received(link, cmd)))
                     case Left(e)    => ZIO.succeed(badCommand(link, e, text))
                 case ChannelEvent.Read(WebSocketFrame.Pong) =>
                   ZIO.succeed(game.post(LinkEvent.Alive(link)))
                 case ChannelEvent.Unregistered => close
                 case _                         => ZIO.unit
               }.ensuring(close)
        yield ()
      }
    }.withConfig(WebSocketConfig.default.forwardPongFrames(true)).toResponse

  /** 解不开的指令：回给客户端，并记日志（限频，版本不一致时客户端每走一步都会来一条）。 */
  private def badCommand(link: WsLink, error: String, text: String): Unit =
    val now = java.lang.System.currentTimeMillis()
    val key = link.account.id.value.toString
    if badCmdLimiter.take(key, now) then
      java.lang.System.err.println(
        s"看不懂的指令 账号 ${link.account.name}：${ClientError.oneLine(error, 200)} ｜ ${ClientError.oneLine(text, 200)}"
      )
    link.send(mir.protocol.ServerMsg.Refused(s"看不懂的指令：$error"))

  // ---------------- 小工具 ----------------

  private def json(status: Status, body: String): Response =
    Response(status = status, body = Body.fromString(body))
      .addHeader("content-type", "application/json")
      .addHeader("cache-control", "no-store")

  private def unauthorized = json(Status.Unauthorized, """{"error":"login required"}""")

/**
 * 不登录也给的素材：原版登录画面要用的图和声音（#1197）——
 * 素材版本号、ChrSel 图库第 0 块（石门背景 22、开门 23-32）、prguse 第 0 块（登录框 60、按钮 53/61/62/64）、开门声 100。
 * 图集是整块下载的（SpriteStore），所以放开的是这两块的全部页。
 */
object LoginScreenAssets:
  private val Blocks = Set("chrsel", "prguse")
  private val Page   = "0_[0-9]+\\.png".r

  def allows(rel: Chunk[String]): Boolean = rel.toList match
    case List("version.json")                           => true
    case List("sound", "100.m4a")                       => true
    case List("img", lib, file) if Blocks.contains(lib) => file == "0.json" || Page.matches(file)
    case _                                              => false

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
