package mir.server

import mir.core.*
import mir.data.Catalog
import mir.model.*
import mir.protocol.*
import mir.server.game.{GameServer, Link, LinkEvent, Maps}
import mir.server.store.Db
import mir.world.CharacterData
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
    val game  = GameServer(catalog, Maps(catalog, assets, store.uids, () => now), store.chars, store.uids, None, () => now)
    def advance(ms: Long): Unit = { now = now + ms; game.step() }
    def connect(n: Long): FakeLink =
      val l = FakeLink(LinkId(n), acc)
      game.post(LinkEvent.Opened(l))
      game.step()
      l
    def say(l: Link, cmd: ClientCmd): Unit = { game.post(LinkEvent.Received(l, cmd)); game.step() }

  def spec = suite("游戏主循环")(
    test("掉线超时：30 秒内有 Ping 不断；超过 30 秒没消息就关连接、存档下线；之后再来的事件忽略、能重新进游戏") {
      val f = Fixture()
      val a = f.connect(1)
      f.say(a, SessionCmd.EnterGame(f.char.id))
      val entered = a.got.exists(_.isInstanceOf[ServerMsg.EnterMap])
      // 每 10 秒一次 Ping，一分钟都不断
      (1 to 6).foreach { _ => f.advance(10_000); f.say(a, SessionCmd.Ping(0)) }
      val aliveWithPings = (!a.closed, f.game.onlineCount == 1)
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
        entered, aliveWithPings == (true, true), at30 == (true, true), after == (true, true),
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
    }
  )
