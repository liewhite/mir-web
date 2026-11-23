package mir.server

import mir.core.*
import mir.model.*
import mir.protocol.*
import mir.world.Terrain
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
    bot.await("LoggedIn") { case ServerMsg.LoggedIn(_) => () }
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
        a2.await("LoggedIn") { case ServerMsg.LoggedIn(n) => n }
        a2.send(SessionCmd.EnterGame(aChar))
        val (aId2, back) = a2.await("重连进图") { case ServerMsg.EnterMap(you, _, c, _, _) => (you, c) }
        b.await("阿狸回来了") { case ServerMsg.Appear(v) if v.id == aId2 => () }

        // 同一角色在另一个连接登录：旧连接被踢，新连接接着在原地
        val a3 = Bot(http, ca)
        a3.await("LoggedIn") { case ServerMsg.LoggedIn(_) => () }
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
        a4.await("LoggedIn") { case ServerMsg.LoggedIn(_) => () }
        a4.send(SessionCmd.EnterGame(aChar))
        val stillThere = a4.await("重连还在盟重") { case ServerMsg.EnterMap(_, m, _, _, _) => m }
        // 一个人在图上时传走（图上没人了也要把换图的人交出去）
        a4.send(GameCmd.Teleport(TeleportRef(0)))
        val home = a4.await("独自传回比奇") { case ServerMsg.EnterMap(_, m, _, _, _) if m == MapId("0") => m }

        (dupName, tooMany, delOnline, aSeesB, bSeesA, dest, walked, hit, back, kicked, again, a2.closed, moved, stillThere, home)
      }.map { case (dupName, tooMany, delOnline, aSeesB, bSeesA, dest, walked, hit, back, kicked, again, oldClosed, moved, stillThere, home) =>
        assertTrue(
          dupName.contains("已被使用"), tooMany.contains("最多"), delOnline.contains("在线"),
          aSeesB match { case Look.Human(n, Job.Taoist, _, _, _, _) => n == "小波"; case _ => false },
          bSeesA match { case Look.Human(n, Job.Warrior, _, _, _, _) => n == "阿狸"; case _ => false },
          walked == dest, hit == ActKind.Attack, back == dest, kicked.nonEmpty, again == dest, oldClosed,
          moved == MapId("3"), stillThere == MapId("3"), home == MapId("0")
        )
      }
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
