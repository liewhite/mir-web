package mir.server

import mir.server.auth.RateLimiter
import mir.server.game.SlowSteps
import mir.server.http.ClientError
import zio.*
import zio.test.*

import java.nio.file.{Files, Paths}

/** 给巡检看的异常日志（#1228）：客户端报错上报接口、日志行的样子、一步卡顿的记录频率。 */
object DiagnosticsSpec extends ZIOSpecDefault:
  private val assets = Paths.get(sys.props.getOrElse("mir.assets", "../public/assets"))

  private def tempConfig(): ServerConfig =
    val dir = Files.createTempDirectory("mir-diag-test")
    val web = Files.createDirectories(dir.resolve("web"))
    Files.writeString(web.resolve("index.html"), "<!doctype html><title>mir</title>")
    ServerConfig("127.0.0.1", 0, assets, web, dir.resolve("data"), "test-secret-0123456789")

  def spec = suite("异常日志")(
    test("客户端报错上报：未登录 401、成功 204、请求体不对 400、太大 413、每账号每小时 30 条后 429") {
      val cfg = tempConfig()
      Main.run(List("account", "add", "alice"), cfg, () => Some("secret1")): Unit
      ZIO.scoped(Boot.start(cfg).flatMap(port => ZIO.attemptBlocking {
        val http   = Http(port)
        val report = """{"message":"TypeError: x is undefined","stack":"at a (main.js:1:2)\nat b (main.js:3:4)","char":"阿狸","version":"abc123"}"""
        val noAuth = http.post("/api/client-error", report).statusCode
        val cookie = http.login("alice", "secret1").get
        def post(body: String) = http.post("/api/client-error", body, "Cookie" -> cookie, "content-type" -> "application/json").statusCode
        val ok      = post(report)
        val minimal = post("""{"message":"只有消息"}""")
        val bad     = post("""{"stack":"没有消息"}""")
        val huge    = post(s"""{"message":"${"x" * (ClientError.MaxBody + 10)}"}""")
        // 已记 2 条，再 28 条满 30，第 31 条挡住
        val more    = (1 to 28).map(i => post(s"""{"message":"第 $i 条"}"""))
        val blocked = post(report)
        (noAuth, ok, minimal, bad, huge, more.distinct, blocked)
      })).map { case (noAuth, ok, minimal, bad, huge, more, blocked) =>
        assertTrue(noAuth == 401, ok == 204, minimal == 204, bad == 400, huge == 413, more == Vector(204), blocked == 429)
      }
    },
    test("报错日志：消息在前、去掉换行防伪造日志行、截短，堆栈每帧一行 `    at …`、最多 8 帧") {
      val e = ClientError(
        message = "坏了\nCRASH 伪造的一行" + "y" * 500,
        stack = Some((("Error: 坏了" +: (1 to 12).map(i => s"    at f$i (main.js:$i:1)")) :+ "g@main.js:9:9").mkString("\n")),
        char = Some("阿狸\n"),
        version = None
      )
      val lines = ClientError.render(e, "alice", "Mozilla/5.0 (iPhone)").split("\n").toList
      val head  = lines.head
      assertTrue(
        head.startsWith("客户端报错：坏了 CRASH 伪造的一行"),
        head.contains("｜ 账号 alice 角色 阿狸 版本 - ｜ Mozilla/5.0 (iPhone)"),
        head.count(_ == 'y') == ClientError.MaxMessage - "坏了 CRASH 伪造的一行".length,
        lines.tail.size == ClientError.MaxFrames,
        lines.tail.forall(_.startsWith("    at ")),
        lines(1) == "    at Error: 坏了",
        lines(2) == "    at f1 (main.js:1:1)"
      )
    },
    test("一步卡顿：超过 500 毫秒才记，同一分钟只记一行，下一行带上中间漏记的步数") {
      val s = SlowSteps()
      val logged = List(
        s.observe(120, 0, 3),      // 不慢
        s.observe(800, 1_000, 3),  // 记
        s.observe(900, 2_000, 3),  // 一分钟内，不记
        s.observe(700, 30_000, 3), // 不记
        s.observe(650, 61_000, 3)  // 记，带上中间 2 步
      )
      assertTrue(
        logged.map(_.isDefined) == List(false, true, false, false, true),
        logged(1).get == "游戏一步耗时 800 毫秒（在线 3 人）",
        logged(4).get.contains("另有 2 步")
      )
    },
    test("限频 take：满了返回 false 且不再记，窗口滑过后放行") {
      val r = RateLimiter(2, 1000)
      assertTrue(List(r.take("a", 0), r.take("a", 10), r.take("a", 20), r.take("b", 20), r.take("a", 1001)) == List(true, true, false, true, true))
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
