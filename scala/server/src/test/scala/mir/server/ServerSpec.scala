package mir.server

import mir.core.*
import mir.model.*
import mir.protocol.*
import mir.terrain.Terrain
import zio.*
import zio.test.*

import java.nio.file.{Files, Paths}

/**
 * 端到端：真起一个服务端（随机端口、临时数据目录、真实素材），两个机器人账号用 HTTP 登录、WebSocket 进游戏。
 * 验：开号与登录、素材要登录、建删角色的规则、同图互相看见、走和打对方看得到、断线重连状态不丢、重复登录踢旧连接、迁移命令。
 */
object ServerSpec extends ZIOSpecDefault:
  private val assets = Paths.get(sys.props.getOrElse("mir.assets", "../public/assets"))

  private def tempConfig(): ServerConfig =
    val dir = Files.createTempDirectory("mir-server-test")
    val web = Files.createDirectories(dir.resolve("web"))
    Files.writeString(web.resolve("index.html"), "<!doctype html><title>mir</title>")
    ServerConfig("127.0.0.1", 0, assets, web, dir.resolve("data"), "test-secret-0123456789")

  private def cli(cfg: ServerConfig, args: String*)(pw: String = ""): Int = Main.run(args.toList, cfg, () => Option(pw))

  /** 起服务跑 f，结束停服。 */
  private def withServer[A](cfg: ServerConfig)(f: Http => A): Task[A] =
    ZIO.scoped(Boot.start(cfg).flatMap(port => ZIO.attemptBlocking(f(Http(port)))))

  private def enter(bot: Bot, name: String, job: Job): (CharId, ActorId, Cell) =
    bot.await("LoggedIn") {
      case ServerMsg.LoggedIn(_, build, wire, data)
          if build == Build.commit && wire == Release.wire && data == Release.dataPrint(Files.readString(assets.resolve("data/game.json"))) => ()
    }
    bot.send(SessionCmd.CreateChar(name, job, Sex.Male))
    val id = bot.await("CharList") { case ServerMsg.CharList(cs) if cs.exists(_.name == name) => cs.find(_.name == name).get.id }
    bot.send(SessionCmd.EnterGame(id))
    bot.await("EnterMap") { case ServerMsg.EnterMap(you, _, cell, _, _) => (id, you, cell) }

  private lazy val town: Terrain =
    Terrain.parseBin(Files.readAllBytes(assets.resolve("map/0.bin"))).fold(e => throw AssertionError(e), identity)

  def spec = suite("服务端")(
    test("开号命令、登录、素材要登录、静态页面") {
      val cfg = tempConfig()
      val cmds = List(
        cli(cfg, "account", "add", "alice")("secret1"),
        cli(cfg, "account", "add", "alice")("secret1"), // 重名
        cli(cfg, "account", "add", "a!")("secret1"),    // 名字不合法
        cli(cfg, "account", "add", "eve")("123"),       // 密码太短
        cli(cfg, "account", "passwd", "alice")("secret2"),
        cli(cfg, "account", "list")()
      )
      withServer(cfg) { http =>
        val noAuth  = http.get("/assets/data/game.json").statusCode
        val bad     = http.login("alice", "secret1")
        val cookie  = http.login("ALICE", "secret2").get
        val me      = http.get("/api/me", "Cookie" -> cookie).body
        val asset   = http.get("/assets/data/game.json", "Cookie" -> cookie)
        val etag    = asset.headers.firstValue("etag").get
        val again   = http.get("/assets/data/game.json", "Cookie" -> cookie, "If-None-Match" -> etag).statusCode
        val page    = http.get("/").statusCode
        val escape  = http.get("/assets/../../etc/passwd", "Cookie" -> cookie).statusCode
        val wsNoAuth = http.get("/ws").statusCode
        (noAuth, bad, me, asset.statusCode, again, page, escape, wsNoAuth)
      }.map { case (noAuth, bad, me, asset, again, page, escape, wsNoAuth) =>
        assertTrue(cmds == List(0, 1, 1, 1, 0, 0)) &&
        assertTrue(noAuth == 401, bad.isEmpty, me.contains("alice"), asset == 200, again == 304, page == 200, escape != 200, wsNoAuth == 401)
      }
    },
    test("登录失败限流") {
      val cfg = tempConfig()
      cli(cfg, "account", "add", "carol")("secret1"): Unit
      withServer(cfg) { http =>
        (1 to 10).foreach(_ => http.login("carol", "wrong!"))
        http.post("/api/login", """{"account":"carol","password":"secret1"}""").statusCode
      }.map(code => assertTrue(code == 429))
    },
    test("自助注册：成功即登录、重名（不分大小写）、格式不对、限频；登录画面的素材不登录也给") {
      val cfg = tempConfig()
      cli(cfg, "account", "add", "alice")("secret1"): Unit
      def reg(http: Http, a: String, p: String) = http.post("/api/register", s"""{"account":"$a","password":"$p"}""")
      withServer(cfg) { http =>
        val ok       = reg(http, "dave_01", "secret1")
        val cookie   = ok.headers.firstValue("set-cookie").get.split(';').head
        val me       = http.get("/api/me", "Cookie" -> cookie).body
        val login    = http.login("DAVE_01", "secret1").isDefined
        val taken    = reg(http, "Alice", "secret9")
        val badName  = reg(http, "a!", "secret1")
        val badPw    = reg(http, "frank", "123")
        val longPw   = reg(http, "frank", "x" * 65)
        val garbage  = http.post("/api/register", "nope").statusCode
        val public   = List("/assets/version.json", "/assets/img/chrsel/0.json", "/assets/img/prguse/0_0.png", "/assets/sound/100.m4a").map(http.get(_).statusCode)
        val private_ = List("/assets/img/chrsel/1.json", "/assets/img/hum/0.json", "/assets/sound/101.m4a", "/assets/data/game.json").map(http.get(_).statusCode)
        // 上面已试 5 次（请求体解析不了的那次不算），再 5 次满 10 次，第 11 次挡住
        val more     = (1 to 5).map(i => reg(http, s"spam$i", "secret1").statusCode)
        val blocked  = reg(http, "spam9", "secret1").statusCode
        (ok.statusCode, me, login, taken, badName, badPw, longPw.statusCode, garbage, public, private_, more, blocked)
      }.map { case (ok, me, login, taken, badName, badPw, longPw, garbage, public, private_, more, blocked) =>
        assertTrue(
          ok == 200, me.contains("dave_01"), login,
          taken.statusCode == 409, taken.body.contains("已被注册"),
          badName.statusCode == 400, badName.body.contains("账号名"),
          badPw.statusCode == 400, badPw.body.contains("密码"), longPw == 400, garbage == 400,
          public == List(200, 200, 200, 200), private_ == List(401, 401, 401, 401),
          more == Vector(200, 200, 200, 200, 200), blocked == 429
        )
      }
    },
    test("掉线判定（#1238）：客户端不发指令、只靠协议层 pong 回应服务端的 ping，超过掉线时限也不被踢") {
      val cfg = tempConfig().copy(liveness = Liveness(pingEveryMs = 200, idleTimeoutMs = 1000))
      cli(cfg, "account", "add", "alice")("secret1"): Unit
      withServer(cfg) { http =>
        val a = Bot(http, http.login("alice", "secret1").get)
        enter(a, "阿狸", Job.Warrior)
        Thread.sleep(3000) // 三倍于掉线时限，期间一条指令都不发（像后台标签页的定时器被压住）
        val open = !a.closed
        a.send(SessionCmd.ListChars)
        val still = a.await("还在线") { case ServerMsg.CharList(cs) => cs.size }
        (open, still)
      }.map((open, still) => assertTrue(open, still == 1))
    },
    test("两个机器人：建角色规则、同图互相看见、走和打对方看得到、断线重连状态不丢、重复登录踢旧连接") {
      val cfg = tempConfig()
      cli(cfg, "account", "add", "alice")("secret1"): Unit
      cli(cfg, "account", "add", "bob")("secret1"): Unit
      withServer(cfg) { http =>
        val ca = http.login("alice", "secret1").get
        val cb = http.login("bob", "secret1").get
        val a  = Bot(http, ca)
        val b  = Bot(http, cb)
        val (aChar, aId, aCell) = enter(a, "阿狸", Job.Warrior)
        val (_, bId, bCell)     = enter(b, "小波", Job.Taoist)

        // 建角色规则：重名、超过两个
        b.send(SessionCmd.CreateChar("阿狸", Job.Wizard, Sex.Female))
        val dupName = b.await("重名被拒") { case ServerMsg.Refused(r) => r }
        a.send(SessionCmd.CreateChar("阿狸二号", Job.Wizard, Sex.Female))
        a.await("第二个角色") { case ServerMsg.CharList(cs) if cs.size == 2 => () }
        a.send(SessionCmd.CreateChar("阿狸三号", Job.Wizard, Sex.Female))
        val tooMany = a.await("第三个被拒") { case ServerMsg.Refused(r) => r }
        a.send(SessionCmd.DeleteChar(aChar))
        val delOnline = a.await("在线不能删") { case ServerMsg.Refused(r) => r }

        // 互相看见（谁先进图，就在后进的人出现时收到 Appear）
        val aSeesB = a.await("阿狸看见小波") { case ServerMsg.Appear(v) if v.id == bId => v.look }
        val bSeesA = b.await("小波看见阿狸") { case ServerMsg.Appear(v) if v.id == aId => v.look }

        // 阿狸走一步（挑一个可走、没人的方向），小波看到
        val dir  = Dir.values.find(d => town.walkable(aCell.step(d)) && aCell.step(d) != bCell).get
        val dest = aCell.step(dir)
        a.send(GameCmd.Walk(aCell, dir))
        a.await("MoveOk") { case ServerMsg.MoveOk => () }
        val walked = b.await("小波看到阿狸走") { case ServerMsg.Act(`aId`, c, _, act) if act.kind == ActKind.Walk => c }

        // 阿狸挥一刀，小波看到
        Thread.sleep(700)
        a.send(GameCmd.Attack(dir.opposite, None))
        val hit = b.await("小波看到阿狸攻击") { case ServerMsg.Act(`aId`, _, _, act) if act.kind == ActKind.Attack => act.kind }

        // 断线：小波看到阿狸消失；重连进同一角色，还在走过去的那一格
        a.drop()
        b.await("阿狸下线消失") { case ServerMsg.Disappear(`aId`) => () }
        val a2 = Bot(http, ca)
        a2.await("LoggedIn") { case ServerMsg.LoggedIn(n, _, _, _) => n }
        a2.send(SessionCmd.EnterGame(aChar))
        val (aId2, back) = a2.await("重连进图") { case ServerMsg.EnterMap(you, _, c, _, _) => (you, c) }
        b.await("阿狸回来了") { case ServerMsg.Appear(v) if v.id == aId2 => () }

        // 同一角色在另一个连接登录：旧连接被踢，新连接接着在原地
        val a3 = Bot(http, ca)
        a3.await("LoggedIn") { case ServerMsg.LoggedIn(_, _, _, _) => () }
        a3.send(SessionCmd.EnterGame(aChar))
        val kicked = a2.await("旧连接被踢") { case ServerMsg.Kicked(r) => r }
        val (aId3, again) = a3.await("新连接进图") { case ServerMsg.EnterMap(you, _, c, _, _) => (you, c) }
        Thread.sleep(300)

        // 换图：用传送菜单去盟重（teleports[1]），小波看到他消失；断线重连还在盟重
        a3.send(GameCmd.Teleport(TeleportRef(1)))
        val moved = a3.await("进盟重") { case ServerMsg.EnterMap(_, m, _, _, _) if m != MapId("0") => m }
        b.await("阿狸传走消失") { case ServerMsg.Disappear(`aId3`) => () }
        a3.drop()
        Thread.sleep(300)
        val a4 = Bot(http, ca)
        a4.await("LoggedIn") { case ServerMsg.LoggedIn(_, _, _, _) => () }
        a4.send(SessionCmd.EnterGame(aChar))
        val stillThere = a4.await("重连还在盟重") { case ServerMsg.EnterMap(_, m, _, _, _) => m }
        // 一个人在图上时传走（图上没人了也要把换图的人交出去）
        a4.send(GameCmd.Teleport(TeleportRef(0)))
        val home = a4.await("独自传回比奇") { case ServerMsg.EnterMap(_, m, _, _, _) if m == MapId("0") => m }

        (dupName, tooMany, delOnline, aSeesB, bSeesA, dest, walked, hit, back, kicked, again, a2.closed, moved, stillThere, home)
      }.map { case (dupName, tooMany, delOnline, aSeesB, bSeesA, dest, walked, hit, back, kicked, again, oldClosed, moved, stillThere, home) =>
        assertTrue(
          dupName.contains("已被使用"), tooMany.contains("最多"), delOnline.contains("在线"),
          aSeesB match { case Look.Human(n, Job.Taoist, _, _, _, _, _) => n == "小波"; case _ => false },
          bSeesA match { case Look.Human(n, Job.Warrior, _, _, _, _, _) => n == "阿狸"; case _ => false },
          walked == dest, hit == ActKind.Attack, back == dest, kicked.nonEmpty, again == dest, oldClosed,
          moved == MapId("3"), stillThere == MapId("3"), home == MapId("0")
        )
      }
    },
    test("组队（#1220）：要对方允许、跨地图建组、队伍聊天、下线解散、允许组队存档") {
      val cfg = tempConfig()
      cli(cfg, "account", "add", "carol")("secret1"): Unit
      cli(cfg, "account", "add", "dave")("secret1"): Unit
      withServer(cfg) { http =>
        val cc = http.login("carol", "secret1").get
        val cd = http.login("dave", "secret1").get
        val c  = Bot(http, cc)
        val d  = Bot(http, cd)
        enter(c, "阿甲", Job.Warrior)
        val (dChar, _, _) = enter(d, "阿乙", Job.Wizard)
        val start = d.await("进游戏时的组队状态") { case ServerMsg.Group(g) => g }
        c.send(SocialCmd.CreateGroup("阿乙"))
        val notAllowed = c.await("对方没允许") { case ServerMsg.Refused(r) => r }
        d.send(SocialCmd.AllowGroup(true))
        d.await("打开允许组队") { case ServerMsg.Group(g) if g.allowGroup => () }
        // 阿甲去盟重，隔着地图建组
        c.send(GameCmd.Teleport(TeleportRef(1)))
        c.await("进盟重") { case ServerMsg.EnterMap(_, m, _, _, _) if m != MapId("0") => () }
        c.send(SocialCmd.CreateGroup("阿乙"))
        val cg = c.await("阿甲的小组") { case ServerMsg.Group(g) if g.members.nonEmpty => g.members }
        val dg = d.await("阿乙的小组") { case ServerMsg.Group(g) if g.members.nonEmpty => g.members }
        d.send(SocialCmd.GroupSay("集合"))
        val heard = c.await("队伍聊天") { case ServerMsg.Chat(from, text, ChatKind.Group) => s"$from:$text" }
        // 阿乙掉线：只剩一人，小组解散；阿乙再上线时允许组队还开着（存档了）
        d.drop()
        c.await("小组解散") { case ServerMsg.Group(g) if g.members.isEmpty => () }
        Thread.sleep(300)
        val d2 = Bot(http, cd)
        d2.await("LoggedIn") { case ServerMsg.LoggedIn(_, _, _, _) => () }
        d2.send(SessionCmd.EnterGame(dChar))
        val back = d2.await("重新上线的组队状态") { case ServerMsg.Group(g) => g }
        (start, notAllowed, cg, dg, heard, back)
      }.map { case (start, notAllowed, cg, dg, heard, back) =>
        assertTrue(
          start == GroupView(), notAllowed.contains("没有允许组队"),
          cg == List("阿甲", "阿乙"), dg == cg, heard == "阿乙:集合",
          back == GroupView(allowGroup = true)
        )
      }
    },
    test("私聊、好友（#1221）：私聊送到、不在线提示；加不在线的好友、上线提示、名单存档") {
      val cfg = tempConfig()
      cli(cfg, "account", "add", "erin")("secret1"): Unit
      cli(cfg, "account", "add", "fred")("secret1"): Unit
      withServer(cfg) { http =>
        val ce = http.login("erin", "secret1").get
        val cf = http.login("fred", "secret1").get
        val e  = Bot(http, ce)
        val f  = Bot(http, cf)
        val (eChar, _, _) = enter(e, "小甲", Job.Taoist)
        val (fChar, _, _) = enter(f, "小乙", Job.Wizard)
        e.await("进游戏时的好友名单") { case ServerMsg.Friends(l) => l }
        e.send(SocialCmd.Whisper("小乙", "在吗"))
        val heard = f.await("收到私聊") { case ServerMsg.Whisper(p, t, WhisperWay.Received) => s"$p:$t" }
        val sent  = e.await("私聊已送到") { case ServerMsg.Whisper(p, t, WhisperWay.Sent) => s"$p:$t" }
        // 小乙下线后：私聊提示不在线，但能加好友（角色存在）
        f.drop()
        Thread.sleep(300)
        e.send(SocialCmd.Whisper("小乙", "在吗"))
        val offline = e.await("不在线") { case ServerMsg.Refused(r) => r }
        e.send(SocialCmd.AddFriend("小乙"))
        val added = e.await("加好友") { case ServerMsg.Friends(l) if l.nonEmpty => l }
        e.send(SocialCmd.AddFriend("没这人"))
        val nobody = e.await("查无此人") { case ServerMsg.Refused(r) => r }
        // 小乙上线：小甲收到提示和新名单
        val f2 = Bot(http, cf)
        f2.await("LoggedIn") { case ServerMsg.LoggedIn(_, _, _, _) => () }
        f2.send(SessionCmd.EnterGame(fChar))
        val up = e.await("好友上线") { case ServerMsg.Friends(l) if l.exists(_.online) => l }
        // 小甲下线再上线：名单还在（存档）
        e.drop()
        Thread.sleep(300)
        val e2 = Bot(http, ce)
        e2.await("LoggedIn") { case ServerMsg.LoggedIn(_, _, _, _) => () }
        e2.send(SessionCmd.EnterGame(eChar))
        val back = e2.await("重新上线的好友名单") { case ServerMsg.Friends(l) => l }
        (heard, sent, offline, added, nobody, up, back)
      }.map { case (heard, sent, offline, added, nobody, up, back) =>
        assertTrue(
          heard == "小甲:在吗", sent == "小乙:在吗", offline.contains("没有在线"),
          added == List(FriendView("小乙", false)), nobody.contains("没有叫"),
          up == List(FriendView("小乙", true)), back == List(FriendView("小乙", true))
        )
      }
    },
    test("全服公告（#1243）：命令行经管理入口发给全部连接（游戏里的和选人的），回话列出在线角色；空的、太长的不发；服务没跑时报错") {
      val cfg = tempConfig()
      cli(cfg, "account", "add", "gina")("secret1"): Unit
      cli(cfg, "account", "add", "hank")("secret1"): Unit
      val before = cli(cfg, "announce", "没开服")()
      def announce(stdin: String, words: String*): (Int, String) =
        val out = java.io.ByteArrayOutputStream()
        val code = scala.Console.withOut(out)(Main.run("announce" :: words.toList, cfg, () => None, () => stdin))
        (code, out.toString("UTF-8").trim)
      withServer(cfg) { http =>
        val g = Bot(http, http.login("gina", "secret1").get)
        enter(g, "公告甲", Job.Warrior)
        val h = Bot(http, http.login("hank", "secret1").get) // 停在选人
        h.await("LoggedIn") { case ServerMsg.LoggedIn(_, _, _, _) => () }
        val perms = java.nio.file.Files.getPosixFilePermissions(cfg.adminSocket)
        val sent  = announce("", "5 分钟后", "重启更新")
        val inGame = g.await("游戏里收到公告") { case ServerMsg.Announce(t) => t }
        val lobby  = h.await("选人画面收到公告") { case ServerMsg.Announce(t) => t }
        val piped  = announce("  从标准输入读\n")
        val viaIn  = g.await("标准输入的公告") { case ServerMsg.Announce(t) => t }
        val empty  = announce("  \n")._1
        val tooLong = announce("", "长" * (mir.server.admin.AdminCmd.MaxAnnounce + 1))._1
        (perms, sent, inGame, lobby, piped, viaIn, empty, tooLong)
      }.map { case (perms, sent, inGame, lobby, piped, viaIn, empty, tooLong) =>
        assertTrue(
          before == 1,
          perms == java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
          sent._1 == 0, sent._2 == "已发给 2 个连接，游戏里在线 1 人：公告甲",
          inGame == "5 分钟后 重启更新", lobby == "5 分钟后 重启更新",
          piped._1 == 0, viaIn == "从标准输入读",
          empty == 1, tooLong == 1,
          !java.nio.file.Files.exists(cfg.adminSocket) // 停服时删掉
        )
      }
    },
    test("部署试启动用的检查（#1288）：smoke 注册、建角色、进图；check 查出解不开的存档；连不上时 smoke 失败") {
      val cfg = tempConfig()
      for
        smoke <- withServer(cfg)(http => mir.server.tools.Smoke.run(http.uri("").toString))
        good  <- ZIO.attempt(cli(cfg, "check")())
        _     <- ZIO.attempt {
                   val db = mir.server.store.Db.open(cfg.dbFile)
                   try db.run(mir.server.store.Db.update(_, "UPDATE characters SET data = '{\"broken\":1}'")) finally db.close()
                 }
        bad   <- ZIO.attempt(cli(cfg, "check")())
        down  <- ZIO.attempt(mir.server.tools.Smoke.run("http://127.0.0.1:1"))
      yield assertTrue(
        smoke.exists(_.contains("进了地图")), good == 0, bad == 1,
        down.left.exists(_.startsWith("页面："))
      )
    },
    test("停服（#1290）：先停游戏存快照（在线的人还算在线），再给每个连接发关闭码 1012「服务器重启」") {
      val cfg = tempConfig()
      cli(cfg, "account", "add", "ivan")("secret1"): Unit
      cli(cfg, "account", "add", "judy")("secret1"): Unit
      withServer(cfg) { http =>
        val g = Bot(http, http.login("ivan", "secret1").get)
        enter(g, "停服甲", Job.Warrior)
        val h = Bot(http, http.login("judy", "secret1").get) // 停在选人
        h.await("LoggedIn") { case ServerMsg.LoggedIn(_, _) => () }
        (g, h)
      }.flatMap { (g, h) =>
        // 关闭帧在停服过程中就发出了，回调稍后才到：最多等 2 秒
        ZIO.attemptBlocking {
          val deadline = java.lang.System.currentTimeMillis() + 2000
          while (g.closeCode.isEmpty || h.closeCode.isEmpty) && java.lang.System.currentTimeMillis() < deadline do Thread.sleep(20)
          val store = Store(mir.server.store.Db.open(cfg.dbFile))
          val frozen = try store.frozen.take() finally store.db.close()
          (g.closeCode, h.closeCode, frozen.carried.keySet)
        }
      }.map { case (gc, hc, carried) =>
        assertTrue(gc == Some(CloseCode.Restarting), hc == Some(CloseCode.Restarting), carried == Set("停服甲"))
      }
    },
    test("只起一个服务端（#1292）：不带参数不起服务；同一数据目录再起一个被拒、不开库，在跑的那个的管理入口照常") {
      val cfg = tempConfig()
      cli(cfg, "account", "add", "ivan")("secret1"): Unit
      val bare = cli(cfg)()
      withServer(cfg) { _ =>
        val second = Unsafe.unsafe { implicit u => Runtime.default.unsafe.run(ZIO.scoped(Boot.start(cfg)).either).getOrThrow() }
        (second, Files.exists(cfg.adminSocket), cli(cfg, "announce", "还在")())
      }.map { case (second, sockKept, announced) =>
        assertTrue(bare == 2, second.left.exists(_.isInstanceOf[mir.server.store.ServeLock.Held]), sockKept, announced == 0)
      }
    },
    test("管理入口不抢别人的 socket（#1292）：已有服务在听时后来的不删它、退出时也不删") {
      import mir.server.admin.{AdminCmd, AdminReply, AdminSocket}
      val path = Files.createTempDirectory("mir-admin").resolve("admin.sock")
      def answer(who: String) = (_: AdminCmd) => AdminReply.Done(who)
      ZIO.scoped {
        for
          _ <- AdminSocket.serve(path, answer("第一个"))
          _ <- ZIO.scoped(AdminSocket.serve(path, answer("第二个")))
          r <- ZIO.attemptBlocking(AdminSocket.request(path, AdminCmd.Bosses()))
        yield r
      }.map(r => assertTrue(r == Right(AdminReply.Done("第一个")), !Files.exists(path)))
    },
    test("迁移旧单机存档") {
      val cfg  = tempConfig()
      val save = Files.createTempFile("save", ".json")
      Files.writeString(save, TsSaveSample)
      cli(cfg, "account", "add", "dave")("secret1"): Unit
      val ok    = cli(cfg, "migrate", "dave", save.toString)()
      val dup   = cli(cfg, "migrate", "dave", save.toString)()          // 名字已占用
      val named = cli(cfg, "migrate", "dave", save.toString, "老战士二")()
      val noAcc = cli(cfg, "migrate", "nobody", save.toString)()
      val store = Store(mir.server.store.Db.open(cfg.dbFile))
      val chars = try store.chars.list(store.accounts.find("dave").get.id) finally store.db.close()
      val d     = chars.head.data
      val uids  = chars.flatMap(_.data.allItems.map(_.uid))
      assertTrue(
        ok == 0, dup == 1, named == 0, noAcc == 1,
        chars.map(_.data.name) == List("老战士", "老战士二"),
        d.job == Job.Wizard, d.level == 30, d.place.map == MapId("D001"), d.place.cell == Cell(150, 359),
        d.worn.get(EquipSlot.Weapon).map(_.name) == Some(ItemName("木剑")),
        d.bag.size == Capacity.bag, d.bag.head.map(_.name) == Some(ItemName("鸡肉")),
        d.skills.map(s => (s.id, s.key)) == List((SkillId(1), Some(1)), (SkillId(11), None)),
        d.settings.autoHpPercent == 55,
        uids.distinct.size == uids.size
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)

  /** 旧单机版存档（game/src/save/save.ts，version 1）的小样本。 */
  private val TsSaveSample = """{
    "version": 1, "savedAt": 1,
    "player": {
      "name": "老战士", "job": 1, "sex": 0, "hair": 1, "level": 30, "exp": 12345, "gold": 999, "hp": 100, "mp": 50,
      "map": "D001", "x": 150, "y": 359, "home": {"map": "0", "x": 333, "y": 272},
      "bag": [{"uid": 9, "name": "鸡肉", "dura": 4000, "duraMax": 4000}, null],
      "belt": [null, {"uid": 11, "name": "金创药(中量)", "dura": 1, "duraMax": 1}],
      "storage": [{"uid": 12, "name": "木剑", "dura": 4000, "duraMax": 4000}],
      "equip": {"weapon": {"uid": 13, "name": "木剑", "dura": 4000, "duraMax": 4000}, "helmet": null},
      "skills": [{"id": 1, "level": 3, "train": 0, "key": 1}, {"id": 11, "level": 2, "train": 5, "key": 0}],
      "longHitOn": false, "wideHitOn": false
    },
    "settings": {"autoPickup": true, "autoHpPercent": 55, "showHpBars": true, "musicOn": false}
  }"""
