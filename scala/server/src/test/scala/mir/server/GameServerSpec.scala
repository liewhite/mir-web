package mir.server

import mir.core.*
import mir.data.Catalog
import mir.model.*
import mir.protocol.*
import mir.server.admin.{AdminCmd, AdminReply}
import mir.server.game.{BossReport, ChronicleLog, Farewell, GameServer, Headcount, Link, LinkEvent, LoadLog, Maps}
import mir.server.store.{Db, Freezer, Frozen, Snapshots}
import mir.social.Social
import mir.world.{BossAlive, BossSpot, CharacterData, Chronicle, DeathVerdict, Loot, Slayer}
import zio.test.*

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable

/**
 * 游戏主循环（不起网络，假连接 + 假时钟）：掉线超时（#1218）、交易成交双方立刻存档（#1222）。
 */
object GameServerSpec extends ZIOSpecDefault:
  private val assets = Paths.get(sys.props.getOrElse("mir.assets", "../public/assets"))
  private lazy val catalog =
    Catalog.decode(Files.readString(assets.resolve("data/game.json"))).fold(e => throw AssertionError(e), identity)

  /** 记下发来的消息和是否被关的连接。 */
  private final class FakeLink(val id: LinkId, val account: Account) extends Link:
    val got    = mutable.ArrayBuffer.empty[ServerMsg]
    var farewell = Option.empty[Farewell]
    def closed: Boolean            = farewell.isDefined
    def send(msg: ServerMsg): Unit = got += msg
    def close(why: Farewell): Unit = farewell = Some(why)

  /** 一套假时钟的游戏服务端 + 一个已有角色的账号。 */
  private final class Fixture(dbFile: Path = Files.createTempDirectory("mir-game-test").resolve("mir.db"), freezer: Freezer = Freezer.Off):
    var now   = Millis(1_000_000L)
    val store = Store(Db.open(dbFile))
    val acc   = store.accounts.find("alice").getOrElse(store.accounts.add("alice", "secret1").fold(e => throw AssertionError(e.toString), identity))
    val char  = store.chars.list(acc.id).headOption.getOrElse(
      store.chars.create(acc.id, CharacterData.newbie(catalog, "阿狸", Job.Warrior, Sex.Male, store.uids)).fold(e => throw AssertionError(e), identity))
    val social = Social()
    val maps  = Maps(catalog, assets, store.uids, () => now, social)
    val game  = GameServer(catalog, maps, store.chars, store.uids, None, () => now,
      social = social, headcount = Some(Headcount(() => store.accounts.count(), now)), freezer = freezer)
    def advance(ms: Long): Unit = { now = now + ms; game.step() }
    def connect(n: Long): FakeLink =
      val l = FakeLink(LinkId(n), acc)
      game.post(LinkEvent.Opened(l))
      game.step()
      l
    def say(l: Link, cmd: ClientCmd): Unit = { game.post(LinkEvent.Received(l, cmd)); game.step() }

  def spec = suite("游戏主循环")(
    test("页面报来的设备和加载耗时写成一行日志（#1327；#1345 人输入、选人和下载分开）；进游戏时不影响上线") {
      val fresh    = LoadLog.line("bob", None, Device.Phone, LoadStage.Connected(LoginPath.Typed(3_210L, 25_000L, 1_460L, register = true), 3_400L))
      val relogin  = LoadLog.line("bob", None, Device.Phone, LoadStage.Connected(LoginPath.Typed(900L, 8_000L, 1_000L, register = false), 3_400L))
      val back     = LoadLog.line("bob", None, Device.Phone, LoadStage.Connected(LoginPath.Remembered(200L), 800L))
      val playable = LoadLog.line("bob", Some("阿狸"), Device.Desktop, LoadStage.Playable(Some(20_000L), 5_100L, 40_260L))
      val resumed  = LoadLog.line("bob", Some("阿狸"), Device.Desktop, LoadStage.Playable(None, 900L, 2_000L))
      val f = Fixture()
      val a = f.connect(1)
      f.say(a, SessionCmd.Loaded(Device.Phone, LoadStage.Connected(LoginPath.Remembered(100L), 200L)))
      f.say(a, SessionCmd.EnterGame(f.char.id))
      assertTrue(
        fresh == "bob：手机，连上服务器；新号注册，打开页面到出登录框 3.2 秒（下载），输账号密码 25.0 秒，提交到开门完 1.5 秒；读游戏数据 3.4 秒",
        relogin == "bob：手机，连上服务器；没 cookie 登录，打开页面到出登录框 0.9 秒（下载），输账号密码 8.0 秒，提交到开门完 1.0 秒；读游戏数据 3.4 秒",
        back == "bob：手机，连上服务器；带 cookie 回来，打开页面到确认登录 0.2 秒；读游戏数据 0.8 秒",
        playable == "阿狸（bob）：电脑，进图可玩；选人画面停留 20.0 秒，点进游戏到可玩 5.1 秒，打开页面到可玩 40.3 秒",
        resumed == "阿狸（bob）：电脑，进图可玩；没经过选人（刷新后接着玩），点进游戏到可玩 0.9 秒，打开页面到可玩 2.0 秒",
        a.got.exists(_.isInstanceOf[ServerMsg.EnterMap]),
        !a.got.exists(_.isInstanceOf[ServerMsg.Refused])
      )
    },
    test("掉线超时：30 秒内有 Ping 或协议层 pong 不断；超过 30 秒没消息就关连接、存档下线；之后再来的事件忽略、能重新进游戏") {
      val f = Fixture()
      val a = f.connect(1)
      f.say(a, SessionCmd.EnterGame(f.char.id))
      val entered = a.got.exists(_.isInstanceOf[ServerMsg.EnterMap])
      // 每 10 秒一次 Ping，一分钟都不断
      (1 to 6).foreach { _ => f.advance(10_000); f.say(a, SessionCmd.Ping(0)) }
      val aliveWithPings = (!a.closed, f.game.onlineCount == 1)
      // 后台标签页：页面不发 Ping，只有网络层自动回的 pong（Alive），一分钟都不断（#1238）
      (1 to 6).foreach { _ => f.advance(10_000); f.game.post(LinkEvent.Alive(a)); f.game.step() }
      val aliveWithPongs = (!a.closed, f.game.onlineCount == 1)
      // 断网：30 秒内还在，超过 30 秒下线
      f.advance(30_000)
      val at30 = (!a.closed, f.game.onlineCount == 1)
      f.advance(100)
      val after = (a.farewell == Some(Farewell.Dropped), f.game.onlineCount == 0)
      // 迟到的消息和关闭事件都忽略
      val before = a.got.size
      f.say(a, SessionCmd.ListChars)
      f.game.post(LinkEvent.Closed(a)); f.game.step()
      val ignored = a.got.size == before
      // 大厅里的连接同样超时
      val lobby = f.connect(2)
      f.advance(30_001)
      val lobbyClosed = lobby.closed
      // 重连能接着进游戏
      val b = f.connect(3)
      f.say(b, SessionCmd.EnterGame(f.char.id))
      val back = (b.got.exists(_.isInstanceOf[ServerMsg.EnterMap]), f.game.onlineCount == 1)
      f.store.db.close()
      assertTrue(
        entered, aliveWithPings == (true, true), aliveWithPongs == (true, true), at30 == (true, true), after == (true, true),
        ignored, lobbyClosed, back == (true, true)
      )
    },
    test("页面刷新换新版（#1289）：先说要刷新再断开，角色不下线、小组还在，新页面进同一角色接上（旧连接不收踢下线）；没回来的 30 秒后下线") {
      val f  = Fixture()
      val c2 = f.store.chars.create(f.acc.id, CharacterData.newbie(catalog, "阿乙", Job.Taoist, Sex.Female, f.store.uids))
        .fold(e => throw AssertionError(e), identity)
      val a = f.connect(1)
      val b = f.connect(2)
      f.say(a, SessionCmd.EnterGame(f.char.id))
      f.say(b, SessionCmd.EnterGame(c2.id))
      f.say(b, SocialCmd.AllowGroup(true))
      f.say(a, SocialCmd.CreateGroup("阿乙"))
      val grouped = f.social.mates("阿狸")
      // 阿狸的页面刷新：说一声再断开
      f.say(a, SessionCmd.Reloading)
      f.game.post(LinkEvent.Closed(a)); f.game.step()
      f.advance(2_000)
      val waiting = (f.game.onlineCount, f.social.mates("阿狸"))
      val a2 = f.connect(3)
      f.say(a2, SessionCmd.EnterGame(f.char.id))
      val back = (
        f.game.onlineCount, f.social.mates("阿狸"), a2.got.exists(_.isInstanceOf[ServerMsg.EnterMap]),
        a.got.exists(_.isInstanceOf[ServerMsg.Kicked]), b.got.collect { case ServerMsg.Notice(t, _) => t }.exists(_.contains("离开"))
      )
      // 阿乙的页面也刷新，但一直没回来：照掉线超时下线
      f.say(b, SessionCmd.Reloading)
      f.game.post(LinkEvent.Closed(b)); f.game.step()
      f.advance(10_000)
      val stillThere = f.game.onlineCount
      (1 to 3).foreach { _ => f.game.post(LinkEvent.Alive(a2)); f.advance(10_000) }
      val gone = f.game.onlineCount
      f.store.db.close()
      assertTrue(
        grouped.toSet == Set("阿狸", "阿乙"), waiting == (2, grouped),
        back == (2, grouped, true, false, false),
        stillThere == 2, gone == 1
      )
    },
    test("交易成交：不等 30 秒定时存档，双方存档里就是交易后的样子") {
      val f  = Fixture()
      val c2 = f.store.chars.create(f.acc.id, CharacterData.newbie(catalog, "阿乙", Job.Taoist, Sex.Female, f.store.uids))
        .fold(e => throw AssertionError(e), identity)
      val a = f.connect(1)
      val b = f.connect(2)
      f.say(a, SessionCmd.EnterGame(f.char.id))
      f.say(b, SessionCmd.EnterGame(c2.id))
      def me(l: FakeLink)   = l.got.collect { case ServerMsg.EnterMap(you, _, _, _, _) => you }.last
      val pot = f.char.data.belt.flatten.head // 新人的 4 瓶小红
      f.say(a, GameCmd.DealTry(me(b)))
      f.say(b, GameCmd.DealTry(me(a)))
      val opened = b.got.exists { case ServerMsg.Deal(Some(_)) => true; case _ => false }
      f.say(a, GameCmd.DealPut(pot.uid, Some(3)))
      f.say(a, GameCmd.DealGold(Gold(100)))
      f.advance(1_100)
      f.say(a, GameCmd.DealConfirm)
      f.say(b, GameCmd.DealConfirm)
      def red(d: CharacterData) = (d.belt.flatten ++ d.bag.flatten).filter(_.name == pot.name).map(_.count).sum
      val sa = f.store.chars.get(f.char.id).get.data
      val sb = f.store.chars.get(c2.id).get.data
      f.store.db.close()
      assertTrue(opened, red(sa) == 1, red(sb) == 7, sa.gold == Gold(200), sb.gold == Gold(400))
    },
    test("无感重启（#1279）：停服写快照、开服接上：地上的东西还在且不复制，角色带的等重连领回，小组接上、过 2 分钟没回来的队员按下线处理；快照只用一次") {
      val dbFile = Files.createTempDirectory("mir-game-test").resolve("mir.db")
      /** 存进数据库，顺便记下存了什么 */
      final class Recording(db: Db) extends Freezer:
        val inner = Snapshots(db)
        var last  = Option.empty[Frozen]
        def put(f: Frozen): Unit = { last = Some(f); inner.put(f) }
        def take(): Frozen       = inner.take()
      val first = Fixture(dbFile)
      val rec   = Recording(first.store.db)
      val f1    = Fixture(dbFile, rec) // 同一个库上带快照的服务端
      val c2 = f1.store.chars.create(f1.acc.id, CharacterData.newbie(catalog, "阿乙", Job.Taoist, Sex.Female, f1.store.uids))
        .fold(e => throw AssertionError(e), identity)
      val a = f1.connect(1)
      val b = f1.connect(2)
      f1.say(a, SessionCmd.EnterGame(f1.char.id))
      f1.say(b, SessionCmd.EnterGame(c2.id))
      f1.say(b, SocialCmd.AllowGroup(true))
      f1.say(a, SocialCmd.CreateGroup("阿乙"))
      val pot = f1.char.data.belt.flatten.head
      f1.say(a, GameCmd.UpdateSettings(f1.char.data.settings.copy(autoPickup = false))) // 不然扔下就自动捡回来
      f1.say(a, GameCmd.DropItem(pot.uid))
      val map = f1.char.data.place.map
      val dropped = f1.maps.opened(map).exists(_.groundItems.exists(_.loot == Loot.Item(pot)))
      f1.game.shutdown()
      val saved = rec.last.get
      val stillHeld = f1.store.chars.get(f1.char.id).get.data.allItems.exists(_.uid == pot.uid)

      // 开服：新的游戏服务端（新 Maps、新 Social）接上快照
      val f2 = Fixture(dbFile, Snapshots(first.store.db))
      f2.game.resume()
      val onGround = f2.maps.opened(map).toVector.flatMap(_.groundItems).count(_.loot == Loot.Item(pot))
      val a2 = f2.connect(3)
      f2.say(a2, SessionCmd.EnterGame(f2.char.id))
      val regrouped = a2.got.collect { case ServerMsg.Group(g) => g.members }.lastOption
      // 阿乙一直没回来：2 分钟后按下线处理，小组只剩一人就散了（阿狸照常在线：每 10 秒回 pong）
      (1 to 13).foreach { _ => f2.game.post(LinkEvent.Alive(a2)); f2.advance(10_000) }
      val afterWait = (f2.game.onlineCount, f2.social.mates("阿狸"), a2.got.collect { case ServerMsg.Notice(t, _) => t }.contains("小组解散了。"))
      // 快照只用一次：再开一次什么也没有
      val again = Snapshots(first.store.db).take()
      first.store.db.close()
      assertTrue(
        dropped, !stillHeld,
        saved.carried.keySet == Set("阿狸", "阿乙"), saved.parties == Vector(Vector("阿狸", "阿乙")), saved.maps.contains(map),
        onGround == 1,
        regrouped.contains(List("阿狸", "阿乙")),
        afterWait == (1, Vector.empty, true),
        again.isEmpty
      )
    },
    test("定时人数公告（#1244）：每 5 分钟给所有连接发一行注册/在线人数；游戏里没人在线的那一轮不发") {
      val f = Fixture()
      f.store.accounts.add("bob", "secret2")
      def notices(l: FakeLink) = l.got.collect { case ServerMsg.Announce(t) => t }.toVector
      val links = mutable.ArrayBuffer.empty[FakeLink]
      /** 过 ms 毫秒：每 10 秒走一步，连接照常回 pong 免得按掉线超时关掉 */
      def wait(ms: Long): Unit =
        Iterator.iterate(ms)(_ - 10_000).takeWhile(_ > 0).foreach { left =>
          links.foreach(l => f.game.post(LinkEvent.Alive(l)))
          f.advance(left min 10_000)
        }
      // 只有人在选人画面：到点不发
      val lobby = f.connect(1)
      links += lobby
      wait(Headcount.DefaultEveryMs)
      val quietWhenNobody = notices(lobby).isEmpty && !lobby.closed
      // 进游戏后：不到 5 分钟不发，到点发一行，选人画面的连接也收到
      val a = f.connect(2)
      links += a
      f.say(a, SessionCmd.EnterGame(f.char.id))
      wait(Headcount.DefaultEveryMs - 1_000)
      val notYet = notices(a).isEmpty
      wait(1_000)
      val first = (notices(a), notices(lobby))
      // 再过 5 分钟再发一行，中间不多发
      wait(Headcount.DefaultEveryMs - 1_000)
      val between = notices(a).size
      wait(1_000)
      val second = notices(a).size
      f.store.db.close()
      val line = "本服已注册 2 人，当前在线 1 人"
      assertTrue(quietWhenNobody, notYet, first == (Vector(line), Vector(line)), between == 1, second == 2)
    },
    test("管理命令 bosses（#1277）：没开过的图写「没人进过」；开过的图写活着的坐标血量或死了几点刷（北京时间）") {
      val f = Fixture()
      def ask(): String =
        var got: Option[AdminReply] = None
        f.game.admin(AdminCmd.Bosses(), r => got = Some(r))
        f.game.step()
        got match
          case Some(AdminReply.Done(text)) => text
          case other                       => throw AssertionError(other.toString)
      val before = ask()
      f.store.db.close()
      val spot  = BossSpot(MonsterName("牛魔王"), Cell(52, 51), 1, Vector.empty, Some(Millis(1_764_050_400_000L))) // 2025-11-25 14:00 北京时间
      val alive = spot.copy(alive = Vector(BossAlive(Cell(53, 50), 100, 200)))
      def status(s: BossSpot, now: Long) =
        BossReport.status(catalog, id => Option.when(id == MapId("D2079"))(Vector(s)), Millis(now)).linesIterator.find(_.startsWith("牛魔王 牛魔寺庙大厅")).get // 幻境十层也刷牛魔王（#1317）
      assertTrue(
        before.linesIterator.contains("牛魔王 牛魔寺庙大厅(D2079) (52,51)：没人进过这张图，有人进图就刷"),
        status(spot, 1_764_050_400_000L - 30 * 60_000L) == "牛魔王 牛魔寺庙大厅(D2079) (52,51)：死了，11-25 14:00 刷（还有 30 分钟）",
        status(spot, 1_764_050_400_000L + 1) == "牛魔王 牛魔寺庙大厅(D2079) (52,51)：死了，到点时图上没人，有人进图就刷",
        status(alive, 0) == "牛魔王 牛魔寺庙大厅(D2079) (52,51)：活着 (53,50) 血 100/200",
        ChronicleLog.line(catalog, MapId("D2079"), Chronicle.BossSlain(MonsterName("牛魔王"), Cell(53, 50), Some("甲"), None, Some(Millis(1_764_050_400_000L))), _ => None) ==
          "boss 死亡：牛魔王 牛魔寺庙大厅(D2079) (53,50)，归属 甲，最后一下 无，下次刷新 11-25 14:00",
        ChronicleLog.line(catalog, MapId("D2079"), Chronicle.PlayerSlain("乙", 35, Cell(10, 12), Slayer.Pet(MonsterName("神兽"), Some("甲")), DeathVerdict.Murder, 0, Vector.empty), n => Option.when(n == "乙")("bob")) ==
          "玩家死亡：乙（账号 bob） 35 级 牛魔寺庙大厅(D2079) (10,12)，被甲的宝宝「神兽」杀死，算谋杀（凶手 PK 值涨），死时 PK 值 0",
        ChronicleLog.line(catalog, MapId("D2079"), Chronicle.PlayerSlain("丙", 40, Cell(1, 2), Slayer.Mob(MonsterName("带刀护卫")), DeathVerdict.Ordinary, 250, Vector(ItemName("金创药(中量)"), ItemName("裁决之杖"))), _ => None) ==
          "玩家死亡：丙 40 级 牛魔寺庙大厅(D2079) (1,2)，被怪物「带刀护卫」杀死，不算 PK，死时 PK 值 250，红名掉了：金创药(中量)、裁决之杖"
      )
    }
  )
