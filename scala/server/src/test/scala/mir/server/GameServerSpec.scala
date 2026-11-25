package mir.server

import mir.core.*
import mir.data.Catalog
import mir.model.*
import mir.protocol.*
import mir.server.admin.{AdminCmd, AdminReply}
import mir.server.game.{BossReport, GameServer, Headcount, Link, LinkEvent, Maps}
import mir.server.store.Db
import mir.world.{BossAlive, BossSpot, CharacterData, Chronicle}
import zio.test.*

import java.nio.file.{Files, Paths}
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
    var closed = false
    def send(msg: ServerMsg): Unit = got += msg
    def close(): Unit              = closed = true

  /** 一套假时钟的游戏服务端 + 一个已有角色的账号。 */
  private final class Fixture:
    var now   = Millis(1_000_000L)
    val store = Store(Db.open(Files.createTempDirectory("mir-game-test").resolve("mir.db")))
    val acc   = store.accounts.add("alice", "secret1").fold(e => throw AssertionError(e.toString), identity)
    val char  = store.chars.create(acc.id, CharacterData.newbie(catalog, "阿狸", Job.Warrior, Sex.Male, store.uids))
      .fold(e => throw AssertionError(e), identity)
    val game  = GameServer(catalog, Maps(catalog, assets, store.uids, () => now), store.chars, store.uids, None, () => now,
      headcount = Some(Headcount(() => store.accounts.count(), now)))
    def advance(ms: Long): Unit = { now = now + ms; game.step() }
    def connect(n: Long): FakeLink =
      val l = FakeLink(LinkId(n), acc)
      game.post(LinkEvent.Opened(l))
      game.step()
      l
    def say(l: Link, cmd: ClientCmd): Unit = { game.post(LinkEvent.Received(l, cmd)); game.step() }

  def spec = suite("游戏主循环")(
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
      val after = (a.closed, f.game.onlineCount == 0)
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
        BossReport.status(catalog, id => Option.when(id == MapId("D2079"))(Vector(s)), Millis(now)).linesIterator.find(_.startsWith("牛魔王 ")).get
      assertTrue(
        before.linesIterator.contains("牛魔王 牛魔寺庙大厅(D2079) (52,51)：没人进过这张图，有人进图就刷"),
        status(spot, 1_764_050_400_000L - 30 * 60_000L) == "牛魔王 牛魔寺庙大厅(D2079) (52,51)：死了，11-25 14:00 刷（还有 30 分钟）",
        status(spot, 1_764_050_400_000L + 1) == "牛魔王 牛魔寺庙大厅(D2079) (52,51)：死了，到点时图上没人，有人进图就刷",
        status(alive, 0) == "牛魔王 牛魔寺庙大厅(D2079) (52,51)：活着 (53,50) 血 100/200",
        BossReport.line(catalog, MapId("D2079"), Chronicle.BossSlain(MonsterName("牛魔王"), Cell(53, 50), Some("甲"), None, Some(Millis(1_764_050_400_000L)))) ==
          "boss 死亡：牛魔王 牛魔寺庙大厅(D2079) (53,50)，归属 甲，最后一下 无，下次刷新 11-25 14:00"
      )
    }
  )
