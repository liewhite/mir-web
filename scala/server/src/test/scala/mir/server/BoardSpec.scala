package mir.server

import mir.server.board.{BoardRules, Rejected}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.file.{Files, Paths}

/** 留言板（#1280）：正文规则；接口端到端——游客能看不能发、署名只能用自己的名字、回复顶帖、作者和管理员删、分页、限频。 */
object BoardSpec extends ZIOSpecDefault:
  private val assets = Paths.get(sys.props.getOrElse("mir.assets", "../public/assets"))

  private def tempConfig(): ServerConfig =
    val dir = Files.createTempDirectory("mir-board-test")
    val web = Files.createDirectories(dir.resolve("web"))
    Files.writeString(web.resolve("index.html"), "<!doctype html><title>mir</title>")
    ServerConfig("127.0.0.1", 0, assets, web, dir.resolve("data"), "test-secret-0123456789", boardAdmins = Set("Boss"))

  private def withServer[A](cfg: ServerConfig)(f: Http => A): Task[A] =
    ZIO.scoped(Boot.start(cfg).flatMap(port => ZIO.attemptBlocking(f(Http(port)))))

  private def parse(s: String): Json = s.fromJson[Json].fold(e => throw AssertionError(s"$e: $s"), identity)

  extension (j: Json)
    def at(path: String*): Json = path.foldLeft(j) { (cur, k) =>
      (cur, k.toIntOption) match
        case (Json.Arr(xs), Some(i)) => xs(i)
        case (Json.Obj(fs), _)       => fs.collectFirst { case (`k`, v) => v }.getOrElse(Json.Null)
        case _                       => Json.Null
    }
    def str: String   = j.asString.getOrElse("")
    def num: Long     = j.asNumber.map(_.value.longValue).getOrElse(-1L)
    def bool: Boolean = j.asBoolean.contains(true)
    def size: Int     = j.asArray.map(_.size).getOrElse(-1)

  def spec = suite("留言板")(
    test("正文规则：去首尾空白、去控制字符、压空行、按字数限长") {
      assertTrue(
        BoardRules.body("  你好\r\n\n\n\n世界 \u0007 ", 10) == Right("你好\n\n世界"),
        BoardRules.body(" \n\t ", 10) == Left(Rejected.Empty),
        BoardRules.body("一二三四五", 5) == Right("一二三四五"),
        BoardRules.body("一二三四五六", 5) == Left(Rejected.TooLong(5)),
        BoardRules.body("😀😀", 2) == Right("😀😀"),
        BoardRules.clampPage(0, 0) == 1, BoardRules.clampPage(9, 21) == 2, BoardRules.pages(40) == 2
      )
    },
    test("游客能看不能发；署名只能用自己的；回复顶帖；作者或管理员才能删；限频") {
      val cfg = tempConfig()
      List("alice", "bob", "boss").foreach(a => Main.run(List("account", "add", a), cfg, () => Some("secret1")))
      withServer(cfg) { http =>
        val a = "Cookie" -> http.login("alice", "secret1").get
        val b = "Cookie" -> http.login("bob", "secret1").get
        val m = "Cookie" -> http.login("boss", "secret1").get
        def post(body: String, h: (String, String)*)            = http.post("/api/board/topics", body, h*)
        def reply(t: Long, body: String, h: (String, String)*)  = http.post(s"/api/board/topics/$t/replies", body, h*)
        def page(h: (String, String)*)                          = parse(http.get("/api/board", h*).body)

        val guestEmpty = page()
        val guestPost  = post("""{"name":"x","body":"hi"}""").statusCode
        val alicePage  = page(a)
        val badName    = post("""{"name":"bob","body":"冒名"}""", a)
        val empty      = post("""{"name":"alice","body":"   "}""", a)
        val tooLong    = post(Map("name" -> "alice", "body" -> "长" * 1001).toJson, a)
        val first      = parse(post("""{"name":"alice","body":"第一帖\n有<b>标签</b>"}""", a).body).at("id").num
        val second     = parse(post("""{"name":"bob","body":"第二帖"}""", b).body).at("id").num
        val before     = page().at("topics", "0", "id").num
        val replied    = reply(first, """{"name":"bob","body":"回一句"}""", b).statusCode
        val noTopic    = reply(9999, """{"name":"bob","body":"回一句"}""", b).statusCode
        val guestView  = page()
        val bobView    = page(b)
        val replyId    = bobView.at("topics", "0", "replies", "0", "id").num
        val bobDelsA   = http.delete(s"/api/board/topics/$first", b).statusCode
        val bobDelsR   = http.delete(s"/api/board/replies/$replyId", b).statusCode
        val adminView  = page(m)
        val adminDel   = http.delete(s"/api/board/topics/$first", m).statusCode
        val again      = http.delete(s"/api/board/topics/$first", m).statusCode
        val guestDel   = http.delete(s"/api/board/topics/$second").statusCode
        val after      = page()
        // alice 已成功发 1 帖（失败的不算），再发 9 条满 10 条，第 11 条挡住
        val more       = (1 to 9).map(i => post(s"""{"name":"alice","body":"灌水$i"}""", a).statusCode)
        val blocked    = post("""{"name":"alice","body":"再来"}""", a)
        val p2         = page().at("pages").num
        (guestEmpty, guestPost, alicePage, badName, empty, tooLong.statusCode, first, second, before, replied, noTopic, guestView,
          bobView, bobDelsA, bobDelsR, adminView, adminDel, again, guestDel, after, more, blocked, p2)
      }.map { case (guestEmpty, guestPost, alicePage, badName, empty, tooLong, first, second, before, replied, noTopic, guestView,
          bobView, bobDelsA, bobDelsR, adminView, adminDel, again, guestDel, after, more, blocked, p2) =>
        assertTrue(
          guestEmpty.at("topics").size == 0, guestEmpty.at("me") == Json.Null, guestEmpty.at("pages").num == 1,
          guestPost == 401,
          alicePage.at("me", "account").str == "alice", alicePage.at("me", "names", "0").str == "alice", !alicePage.at("me", "admin").bool,
          badName.statusCode == 400, badName.body.contains("署名"),
          empty.statusCode == 400, tooLong == 400,
          first > 0, second > first,
          before == second,                 // 没回复前新帖在前
          replied == 200, noTopic == 404,
          guestView.at("topics", "0", "id").num == first, // 回复把旧帖顶到最前
          guestView.at("topics", "0", "body").str == "第一帖\n有<b>标签</b>",
          guestView.at("topics", "0", "replies", "0", "author").str == "bob",
          !guestView.at("topics", "0", "canDelete").bool,
          !bobView.at("topics", "0", "canDelete").bool, bobView.at("topics", "0", "replies", "0", "canDelete").bool,
          bobDelsA == 403, bobDelsR == 200,
          adminView.at("me", "admin").bool, adminView.at("topics", "0", "canDelete").bool,
          adminView.at("topics", "0", "replies").size == 0,
          adminDel == 200, again == 404, guestDel == 401,
          after.at("topics").size == 1, after.at("topics", "0", "id").num == second,
          more.forall(_ == 200), blocked.statusCode == 429, blocked.body.contains("太勤"),
          p2 == 1 // 1 + 9 + 删剩 1 = 11 帖，一页 20
        )
      }
    },
    test("微信群二维码：没图 404；放上图游客就能取；覆盖文件不重启就换了（ETag 变）") {
      val cfg = tempConfig()
      withServer(cfg) { http =>
        val missing = http.get("/api/board/qr").statusCode
        Files.createDirectories(cfg.boardQr.getParent)
        Files.writeString(cfg.boardQr, "not an image")
        val junk = http.get("/api/board/qr").statusCode
        Files.write(cfg.boardQr, Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1).map(_.toByte))
        val first = http.get("/api/board/qr")
        val tag1  = first.headers.firstValue("etag").orElse("")
        val same  = http.get("/api/board/qr", "If-None-Match" -> tag1).statusCode
        Files.write(cfg.boardQr, Array(0xff, 0xd8, 0xff, 0xe0, 0, 0x10).map(_.toByte))
        val second = http.get("/api/board/qr", "If-None-Match" -> tag1)
        (missing, junk, first.statusCode, first.headers.firstValue("content-type").orElse(""),
          first.headers.firstValue("cache-control").orElse(""), tag1, same, second.statusCode,
          second.headers.firstValue("etag").orElse(""), second.headers.firstValue("content-type").orElse(""))
      }.map { case (missing, junk, ok, ctype, cache, tag1, same, changed, tag2, ctype2) =>
        assertTrue(missing == 404, junk == 404, ok == 200, ctype == "image/png", cache == "no-cache", tag1.nonEmpty,
          same == 304, changed == 200, tag2 != tag1, ctype2 == "image/jpeg")
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
