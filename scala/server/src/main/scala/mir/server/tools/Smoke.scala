package mir.server.tools

import mir.core.Build
import mir.model.{Job, Sex}
import mir.protocol.*
import zio.json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.time.Duration
import java.util.concurrent.{CompletionStage, LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.util.Try

/**
 * 冒烟检查（#1288）：像玩家一样走一遍「打开页面 → 注册 → 取素材 → 连 WebSocket → 建角色 → 进图」，
 * 任何一步不对就给出卡在哪。部署时对着试启动的新版（数据库是线上的副本）跑，不通过就不换下旧服。
 * 会注册一个一次性账号 smk<随机数>，所以只对副本库跑，不对线上跑。
 */
object Smoke:
  private val StepMs = 15_000L

  def run(base: String): Either[String, String] =
    val root   = base.stripSuffix("/")
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    val suffix = (100000 + scala.util.Random.nextInt(900000)).toString
    val account = s"smk$suffix"
    val password = s"pw-${java.util.UUID.randomUUID()}"
    def get(path: String, cookie: Option[String]) =
      val b = HttpRequest.newBuilder(URI.create(root + path)).timeout(Duration.ofMillis(StepMs)).GET()
      cookie.foreach(b.header("Cookie", _))
      client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    def step[A](what: String)(f: => Either[String, A]): Either[String, A] =
      Try(f).toEither.left.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}").flatten.left.map(e => s"$what：$e")
    def ok(what: String, r: HttpResponse[String]): Either[String, Unit] =
      Either.cond(r.statusCode == 200, (), s"$what 返回 ${r.statusCode}")

    for
      _ <- step("页面")(ok("GET /", get("/", None)))
      cookie <- step("注册") {
        val req = HttpRequest.newBuilder(URI.create(root + "/api/register")).timeout(Duration.ofMillis(StepMs))
          .POST(HttpRequest.BodyPublishers.ofString(Map("account" -> account, "password" -> password).toJson)).build()
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        val c = r.headers.firstValue("set-cookie")
        Either.cond(r.statusCode == 200 && c.isPresent, c.get.split(';').head, s"返回 ${r.statusCode} ${r.body.take(200)}")
      }
      _ <- step("素材")(ok("GET /assets/data/game.json", get("/assets/data/game.json", Some(cookie))))
      entered <- step("进游戏")(withBot(client, root, cookie)(enter(_, account)))
    yield s"冒烟通过：$entered"

  /** 连上 → LoggedIn（版本要是本 jar 的）→ 建角色 → 进图。 */
  private def enter(bot: SmokeBot, name: String): Either[String, String] =
    for
      build <- bot.await("LoggedIn") { case ServerMsg.LoggedIn(_, b, _, _) => b }
      _     <- Either.cond(build == Build.commit, (), s"服务端版本 $build，不是这个 jar 的 ${Build.commit}")
      _      = bot.send(SessionCmd.CreateChar(name, Job.Warrior, Sex.Male))
      id    <- bot.await("建角色后的 CharList") { case ServerMsg.CharList(cs) if cs.exists(_.name == name) => cs.find(_.name == name).get.id }
      _      = bot.send(SessionCmd.EnterGame(id))
      place <- bot.await("EnterMap") { case ServerMsg.EnterMap(_, map, cell, _, _) => s"版本 $build，进了地图 ${map.value} (${cell.x},${cell.y})" }
    yield place

  private def withBot[A](client: HttpClient, root: String, cookie: String)(f: SmokeBot => Either[String, A]): Either[String, A] =
    val b = SmokeBot(client, root.replaceFirst("^http", "ws") + "/ws", cookie)
    try f(b)
    finally b.close()

  /** 一个 WebSocket 客户端：收到的消息排队，按条件等；被拒（Refused、Kicked）或断开就直接失败。 */
  private final class SmokeBot(client: HttpClient, url: String, cookie: String):
    private val inbox = LinkedBlockingQueue[Either[String, ServerMsg]]()
    private val ws: WebSocket = client.newWebSocketBuilder()
      .header("Cookie", cookie)
      .buildAsync(URI.create(url), new WebSocket.Listener:
        private val buf = StringBuilder()
        override def onText(w: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
          buf.append(data)
          if last then
            inbox.add(buf.toString.fromJson[ServerMsg].left.map(e => s"解不开服务端消息（$e）：${buf.take(200)}"))
            buf.clear()
          w.request(1)
          null
        override def onClose(w: WebSocket, code: Int, reason: String): CompletionStage[?] =
          inbox.add(Left(s"连接被关闭（$code $reason）"))
          null
        override def onError(w: WebSocket, e: Throwable): Unit =
          inbox.add(Left(s"连接出错（${e.getMessage}）")): Unit
      ).get(StepMs, TimeUnit.MILLISECONDS)

    def send(cmd: ClientCmd): Unit = ws.sendText((cmd: ClientCmd).toJson, true).get(StepMs, TimeUnit.MILLISECONDS): Unit

    /** 等第一条满足 f 的消息，其余的丢掉。 */
    def await[A](what: String)(f: PartialFunction[ServerMsg, A]): Either[String, A] =
      val deadline = System.currentTimeMillis() + StepMs
      @tailrec def loop(): Either[String, A] =
        val left = deadline - System.currentTimeMillis()
        Option(inbox.poll(math.max(1, left), TimeUnit.MILLISECONDS)) match
          case None                                 => Left(s"${StepMs / 1000} 秒等不到 $what")
          case Some(Left(e))                        => Left(s"等 $what 时$e")
          case Some(Right(ServerMsg.Refused(why)))  => Left(s"等 $what 时被拒：$why")
          case Some(Right(ServerMsg.Kicked(why)))   => Left(s"等 $what 时被踢：$why")
          case Some(Right(m)) if f.isDefinedAt(m)   => Right(f(m))
          case Some(Right(_))                       => loop()
      loop()

    def close(): Unit = Try(ws.abort()): Unit
