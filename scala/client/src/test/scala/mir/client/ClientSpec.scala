package mir.client

import mir.client.assets.MapData
import mir.client.game.*
import mir.client.input.{Input, Joystick, KeyMove, KeyStick, MoveKey, Pt, Stick}
import mir.client.net.{LinkEvent, ServerClock, Transport}
import mir.client.render.{Frames, Hit, Picking}
import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.Rng
import mir.terrain.Terrain
import mir.world.*
import zio.json.*
import zio.test.*

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.{ArrayBuffer, DataView}

@js.native
@JSImport("node:fs", JSImport.Namespace)
private object NodeFs extends js.Object:
  def readFileSync(path: String, encoding: String): String = js.native

@js.native
@JSImport("node:process", JSImport.Namespace)
private object NodeProcess extends js.Object:
  val env: js.Dictionary[String] = js.native

/**
 * 客户端纯逻辑：帧号、摇杆、对时、本地先走，以及客户端核心 + 意图接在真世界（mir.world.World，同步跑）上：
 * 摇杆走路时本地画的位置和服务端一致、被拒能拉回、锁怪攻击能打死怪。
 */
object ClientSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    val p = NodeProcess.env.get("MIR_GAME_JSON").getOrElse(sys.error("缺环境变量 MIR_GAME_JSON（用 sbt test 跑）"))
    Catalog.decode(NodeFs.readFileSync(p, "utf8")).fold(e => throw new AssertionError(e), identity)

  /** 造一个 w×h 的地图文件（M176 格式），blocked 里的格子不能走。 */
  private def mapBin(w: Int, h: Int, blocked: Set[Cell]): ArrayBuffer =
    val n   = w * h
    val buf = new ArrayBuffer(8 + n * 9)
    val dv  = new DataView(buf)
    "M176".zipWithIndex.foreach((c, i) => dv.setUint8(i, c.toByte))
    dv.setUint16(4, w, true)
    dv.setUint16(6, h, true)
    blocked.foreach(c => dv.setUint8(8 + c.y * w + c.x, 1))
    buf

  /** 已下好的 0 号图：进 0 号图时不等下载，马上进入画面 */
  private def map0: MapData = MapData.parse(MapId("0"), "0", mapBin(40, 40, Set.empty)).fold(e => throw new AssertionError(e), identity)

  private val testMap = MapDef(MapId("T"), "T", "测试", Set.empty, 40, 40)

  /** 世界 + 客户端，同步推进：每 50 毫秒 tick 一次，消息立刻交给客户端。 */
  private final class Rig(blocked: Set[Cell] = Set.empty, size: Int = 40):
    val mapDef = testMap.copy(width = size, height = size)
    val md    = MapData.parse(testMap.id, "T", mapBin(size, size, blocked)).fold(e => throw new AssertionError(e), identity)
    var now   = 0.0
    val world = World(catalog, mapDef, md.terrain, Rng.seeded(3), UidSource.counter(1), Millis(0))
    /** 客户端的数据表里也要有这张测试图（安全区看地图标记，PK 判定要查 MapDef） */
    val client = GameClient(Catalog(catalog.data.copy(maps = mapDef :: catalog.data.maps)), ClientEnv(() => now, _ => Future.successful(md), (_, _, _) => ()))
    val ctl    = Controller(client)
    var me     = ActorId(0)
    val sent   = scala.collection.mutable.ArrayBuffer.empty[ClientCmd]
    client.map = Some(md) // 测试里不等下载

    client.connect(new Transport:
      def send(cmd: ClientCmd): Unit =
        sent += cmd
        cmd match
          case g: GameCmd => world.handle(me, g)
          case _          => ()
      def close(): Unit = ()
    )

    def join(at: Cell): Unit =
      val d = CharacterData.newbie(catalog, "甲", Job.Warrior, Sex.Male, UidSource.counter(1000))
      me = world.join(d.copy(place = Place(mapDef.id, at)))
      deliver()

    def deliver(): Unit =
      // 走一遍 JSON，和网络上一样
      world.flush().messages.getOrElse(me, Vector.empty).foreach { m =>
        client.receive(LinkEvent.Received(m.toJson.fromJson[ServerMsg].fold(e => throw new AssertionError(e), identity)))
      }

    /** 推进 ms 毫秒：每 50 毫秒客户端走一帧、世界 tick 一次。 */
    def run(ms: Int): Unit = (0 until ms by 50).foreach { _ =>
      now += 50
      ctl.update()
      world.tick(Millis(now.toLong))
      deliver()
    }

    def serverCell: Cell = world.player(me).get.currentCell

  def spec = suite("客户端")(
    test("人物帧号照原版动作表：走路第 3 帧、站立循环、死后停在最后一帧") {
      val walk = ActionView(ActKind.Walk, Millis(0), 600, from = Some(Cell(0, 0)))
      assertTrue(
        Frames.human(walk, Dir.Right, Millis(250), dead = false) == 64 + 2 * 8 + 2,
        Frames.human(walk, Dir.Up, Millis(5000), dead = false) == (5000 / 200) % 4,
        Frames.human(ActionView(ActKind.Die, Millis(0), 480), Dir.Down, Millis(9000), dead = true) == 536 + 4 * 8 + 3,
        Frames.npcOffset(24) == 1470,
        Frames.goldLook(500) == 115
      )
    },
    test("怪物帧号：石像停在 ActDeath 第一帧，埋着的食人花钻出用走路帧") {
      val m     = catalog.monster(MonsterName("祖玛雕像")).get
      val t     = catalog.actions(m).get
      val stand = ActionView(ActKind.Stand, Millis(0), 0)
      val herb  = catalog.monster(MonsterName("食人花")).get
      val ht    = catalog.actions(herb).get
      val dig   = ActionView(ActKind.DigUp, Millis(0), ht.walk.frames * 100)
      assertTrue(
        Frames.monster(t, MonsterLook.of(m), stand, Dir.Up, Millis(100), dead = false, Cover.Stone) == t.death.start,
        Frames.monster(ht, MonsterLook.of(herb), dig, Dir.Left, Millis(0), dead = false, Cover.Open) == ht.walk.start
      )
    },
    test("点选角色（#1284）：像素命中优先，大图的透明边不抢点击；再退到正中 40×70；自己不算；手机放宽兜底") {
      type Body = (Int, Int) => Boolean
      val full: Body = (_, _) => true
      // 神兽：200×200 的大图，只有右下 (120..200, 120..200) 是实的，站在人前一行
      val beastBody: Body = (x, y) => x >= 120 && y >= 120
      val man   = Hit[Body](ActorId(1), 100, 100, 40, 70, row = 10, full)
      val beast = Hit[Body](ActorId(2), 0, 0, 200, 200, row = 11, beastBody)
      val me    = Hit[Body](ActorId(9), 0, 0, 300, 300, row = 12, full)
      val hits  = Vector(man, beast, me)
      def at(x: Double, y: Double, slop: Double = 0) = Picking.pick(hits, x, y, slop, ActorId(9))((b, x, y) => b(x, y))
      assertTrue(
        at(110, 110).contains(ActorId(1)), // 人身上：神兽框盖着但那里透明
        at(150, 150).contains(ActorId(2)), // 神兽实处，且盖在人前面
        at(119, 150).contains(ActorId(1)), // 实处边缘一格（左邻点透明）不算神兽
        at(85, 95).contains(ActorId(2)),   // 都没按像素点中：神兽正中 40×70（80..120, 65..135）
        at(10, 10).isEmpty,                // 神兽透明边、不在正中：谁也不算（自己也不算）
        at(10, 10, slop = 20).isEmpty,     // 放宽也从正中算起，透明边不算
        at(70, 60, slop = 14).contains(ActorId(2)), // 离神兽正中 10 以内
        Picking.pick(Vector(man), 145, 100, 10, ActorId(9))((b, x, y) => b(x, y)).contains(ActorId(1))
      )
    },
    test("摇杆：死区不动、8 方向取整、推远了跑") {
      val j = Joystick()
      j.start(1, Pt(100, 100))
      val still = j.move(Pt(105, 103))
      val right = j.move(Pt(130, 102))
      val upFar = j.move(Pt(100, 20))
      val knob  = j.knob
      val diag  = j.move(Pt(70, 130))
      assertTrue(
        still.isEmpty,
        right.contains(Stick(Dir.Right, run = false)),
        upFar.contains(Stick(Dir.Up, run = true)),
        knob == Pt(100, 100 - Joystick.Radius),
        diag.map(_.dir).contains(Dir.DownLeft)
      )
    },
    test("底栏血球当摇杆（#1278）：按在摇杆区算摇杆、也算界面占用；被上层窗口或按钮盖住的地方不算；区外照旧") {
      import mir.client.ui.{Button, Hits, Rect}
      val h = Hits()
      h.area(Rect(0, 349, 800, 251))  // 底栏
      h.stick(Rect(0, 349, 200, 251)) // 血/魔球一块
      h.area(Rect(150, 300, 100, 100)) // 盖在上面的窗口
      h.button(Button(Rect(20, 560, 20, 20), () => ()))
      assertTrue(
        h.stickAt(60, 480),
        h.covers(60, 480),
        !h.stickAt(160, 380), // 窗口盖住
        !h.stickAt(30, 570),  // 上层按钮
        !h.stickAt(300, 480), // 区外（聊天框那边）
        !h.stickAt(60, 300)
      )
    },
    test("键盘摇杆：单键 4 方向、组合斜走、默认跑 Shift 走、斜走松一键先等等、全松开停") {
      val k     = KeyStick()
      val w     = k.press(MoveKey.W)
      val wd    = k.press(MoveKey.D)
      val again = k.press(MoveKey.D)
      val slow  = k.shift(true)
      val fast  = k.shift(false)
      val wOff  = k.release(MoveKey.W) // 斜走松一个：等一下
      val left  = k.current
      val dOff  = k.release(MoveKey.D)
      val sa    = (k.press(MoveKey.S), k.press(MoveKey.A))._2
      val ad    = k.press(MoveKey.D) // A+D 抵消只剩 S
      val lost  = k.clear()
      val idle  = k.clear()
      val ws    = (k.press(MoveKey.W), k.press(MoveKey.S))._2 // 相对两键抵消：停
      assertTrue(
        w == KeyMove.Go(Stick(Dir.Up, run = true)),
        wd == KeyMove.Go(Stick(Dir.UpRight, run = true)),
        again == KeyMove.Same,
        slow == KeyMove.Go(Stick(Dir.UpRight, run = false)),
        fast == KeyMove.Go(Stick(Dir.UpRight, run = true)),
        wOff == KeyMove.Settle,
        left.contains(Stick(Dir.Right, run = true)),
        dOff == KeyMove.Stop,
        sa == KeyMove.Go(Stick(Dir.DownLeft, run = true)),
        ad == KeyMove.Go(Stick(Dir.Down, run = true)),
        lost == KeyMove.Stop,
        idle == KeyMove.Same,
        ws == KeyMove.Stop,
        MoveKey.of("KeyW").contains(MoveKey.W),
        MoveKey.of("KeyQ").isEmpty
      )
    },
    test("按键事件（#1298）：浏览器自动填充发的不带 key/code 的事件不算真按键；F 键照常认") {
      def ev(fields: (String, js.Any)*) = js.Dynamic.literal(fields*).asInstanceOf[org.scalajs.dom.KeyboardEvent]
      assertTrue(
        Input.realKey(ev("key" -> "w", "code" -> "KeyW")),
        !Input.realKey(ev()),
        !Input.realKey(ev("key" -> "w")),
        !Input.realKey(ev("code" -> "KeyW")),
        !Input.realKey(ev("key" -> null, "code" -> null)),
        Input.fKey("F3").contains(3),
        Input.fKey("F9").isEmpty,
        Input.fKey("f").isEmpty
      )
    },
    test("对时：按往返的一半校正，往返太慢的不采信") {
      val c = ServerClock()
      c.roughly(Millis(10_000), 100)
      c.pong(sentLocal = 1000, server = Millis(11_000), local = 1100) // 往返 100：服务器此刻 ≈ 11050
      val good = c.now(1100)
      c.pong(sentLocal = 2000, server = Millis(50_000), local = 2500) // 往返 500，不采信
      assertTrue(good == Millis(11_050), c.now(1200) == Millis(11_150))
    },
    test("地图文件解析：宽高、能不能走和共用的 Terrain.parseBin 一致") {
      val buf = mapBin(5, 4, Set(Cell(2, 1)))
      val md  = MapData.parse(MapId("x"), "x", buf).toOption.get
      val bad = MapData.parse(MapId("x"), "x", new ArrayBuffer(12))
      assertTrue(md.width == 5, md.height == 4, !md.terrain.walkable(Cell(2, 1)), md.terrain.walkable(Cell(1, 1)), md.objectAt(0, 0).isEmpty, bad.isLeft)
    },
    test("摇杆一直推：本地先走，每一步服务端都同意，停下后本地和服务端在同一格") {
      val r = Rig()
      r.join(Cell(10, 10))
      r.ctl.stick(Dir.Right, run = false)
      r.run(3000)
      r.ctl.stickReleased()
      r.run(1000)
      val walks  = r.sent.count(_.isInstanceOf[GameCmd.Walk])
      val denied = r.client.motion.blockedUntil > Millis.zero
      assertTrue(walks >= 4, !denied, r.client.self.map(_.cell).contains(r.serverCell), r.serverCell == Cell(10 + walks, 10))
    },
    test("推着跑：两格一步，撞墙前绕开，不被拉回") {
      val r = Rig(blocked = (0 until 40).map(y => Cell(20, y)).filter(_.y != 25).toSet)
      r.join(Cell(10, 10))
      r.ctl.stick(Dir.Right, run = true)
      r.run(4000)
      val runs = r.sent.count(_.isInstanceOf[GameCmd.Run])
      assertTrue(runs >= 2, r.serverCell.x < 20, r.client.self.map(_.cell).contains(r.serverCell))
    },
    test("服务端不同意（本地以为能走、其实被挡）：拉回到服务端的位置") {
      val r = Rig()
      r.join(Cell(10, 10))
      // 服务端先把人挪走（模拟自动功能移动），客户端还按旧位置发
      val stale = r.client.self.get
      r.client.motion = r.client.motion.step(stale, Dir.Down, run = false, r.client.serverNow)._1
      r.client.send(GameCmd.Walk(Cell(9, 9), Dir.Down))
      r.deliver()
      r.run(100)
      assertTrue(r.client.self.map(_.cell).contains(Cell(10, 10)), r.serverCell == Cell(10, 10))
    },
    test("点怪锁定、按攻击键：走过去打死它，拿到经验") {
      val r = Rig()
      r.join(Cell(10, 10))
      val chick = WorldAccess.spawn(r.world, catalog.monster(MonsterName("鸡")).get, Cell(14, 10)).get
      r.deliver()
      r.ctl.tapActor(chick.id)
      val locked = r.client.locked
      r.ctl.attack()
      r.run(15000)
      assertTrue(
        locked.contains(chick.id),
        r.sent.exists(_.isInstanceOf[GameCmd.Attack]),
        r.world.actor(chick.id).forall(_.isDead),
        r.client.view.stats.exists(_.exp.value > 0)
      )
    },
    test("点宝宝锁定后放治愈术：目标是宝宝（不是旁边的怪）；攻击键不打宝宝、改打怪；没锁自己人就治自己") {
      val r = Rig()
      r.join(Cell(10, 10))
      val chick = WorldAccess.spawn(r.world, catalog.monster(MonsterName("鸡")).get, Cell(11, 10)).get
      val pet   = WorldAccess.spawn(r.world, catalog.monster(MonsterName("蛤蟆")).get, Cell(14, 10)).get
      WorldAccess.adopt(r.world, pet, r.me)
      r.deliver()
      def lastCast = r.sent.collect { case c: GameCmd.Cast => c }.lastOption
      r.ctl.tapActor(pet.id)
      r.ctl.castSkill(mir.rules.Skill.Healing.id)
      val onPet = lastCast
      r.ctl.attack()
      val afterAttack = r.client.locked
      r.ctl.castSkill(mir.rules.Skill.Healing.id)
      val onSelf = lastCast
      assertTrue(
        onPet.exists(c => c.target.contains(pet.id) && r.client.actor(pet.id).exists(_.cell == c.cell)),
        afterAttack.contains(chick.id),
        onSelf.exists(c => c.target.isEmpty && c.cell == Cell(10, 10))
      )
    },
    test("PK（#1225）：锁定别的玩家按攻击键就走过去打他，自己名字变灰；没锁定时攻击键只找怪") {
      val r = Rig()
      r.join(Cell(10, 10))
      val d     = CharacterData.newbie(catalog, "乙", Job.Taoist, Sex.Male, UidSource.counter(5000))
      val other = r.world.join(d.copy(place = Place(r.mapDef.id, Cell(13, 10))))
      r.run(3000) // 过了进图保护（Pk.ArrivalGraceMs）
      r.ctl.attack()
      val noMonster = r.client.locked.isEmpty
      r.ctl.tapActor(other)
      val attackable = r.client.actor(other).exists(r.client.canAttack)
      r.ctl.attack()
      r.run(3000)
      val hurt = r.world.actor(other).exists(a => a.isDead || a.currentHp < a.maxHp)
      val gray = r.client.self.map(_.look).collect { case h: Look.Human => h.pk }.contains(mir.rules.PkStanding.Brawler)
      assertTrue(noMonster, attackable, !r.client.isEnemy(r.client.actor(other).get), hurt, gray)
    },
    test("同队（#1274）：队友不算能打的，点了只锁定") {
      val r = Rig()
      r.join(Cell(10, 10))
      val d     = CharacterData.newbie(catalog, "乙", Job.Taoist, Sex.Male, UidSource.counter(5000))
      val other = r.world.join(d.copy(place = Place(r.mapDef.id, Cell(13, 10))))
      r.run(3000)
      val before = r.client.actor(other).exists(r.client.canAttack)
      r.client.view = r.client.view.copy(group = mir.protocol.GroupView(members = List("乙")))
      val mate = r.client.actor(other).get
      assertTrue(before, r.client.teammate(mate), !r.client.canAttack(mate))
    },
    test("连点攻击键（关着按住攻击）：每刀都被服务端收下，间隔等于攻击间隔（#1321：原来 600 毫秒发一刀、一半被丢、实际 1200）") {
      val r = Rig()
      r.join(Cell(10, 10))
      WorldAccess.settings(r.world, r.me, _.copy(stickyAttack = false))
      val dummy = WorldAccess.spawn(r.world, catalog.monster(MonsterName("鸡")).get, Cell(11, 10)).get
      r.run(500)
      r.ctl.tapActor(dummy.id)
      val hits = scala.collection.mutable.LinkedHashSet.empty[Millis]
      (0 until 200).foreach { _ =>
        WorldAccess.setHp(dummy, 1_000_000)
        if r.ctl.intent == mir.client.game.Intent.Idle then r.ctl.attack()
        r.run(50)
        r.client.view.self.map(_.action).filter(a => a.kind == ActKind.Attack || a.kind == ActKind.Heavy).foreach(a => hits += a.startedAt)
      }
      val sent = r.sent.count(_.isInstanceOf[GameCmd.Attack])
      val gaps = hits.toVector.sliding(2).collect { case Vector(a, b) => b - a }.toVector
      val want = r.client.view.stats.get.stats.hitInterval.toLong
      assertTrue(sent == hits.size, hits.size >= 9, gaps.forall(g => g >= want && g <= want + 50))
    },
    test("砍完马上推摇杆走开（按住攻击开着、服务端替你接着砍）：不被拉回（#1321）") {
      val r = Rig()
      r.join(Cell(10, 10))
      val dummy = WorldAccess.spawn(r.world, catalog.monster(MonsterName("鸡")).get, Cell(9, 10)).get
      r.run(500)
      r.ctl.tapActor(dummy.id)
      r.ctl.attack()
      (0 until 40).foreach { _ =>
        WorldAccess.setHp(dummy, 1_000_000)
        r.run(50)
      }
      val attacking = r.client.view.self.exists(a => a.action.kind == ActKind.Attack || a.action.kind == ActKind.Heavy)
      r.ctl.stick(Dir.Right, run = false)
      (0 until 40).foreach { _ =>
        WorldAccess.setHp(dummy, 1_000_000)
        r.run(50)
      }
      assertTrue(attacking, r.client.motion.blockedUntil == Millis.zero, r.serverCell.x > 10)
    },
    test("自己下一个动作的时刻（#1321）：自己发的按发出时刻+服务端的忙；视野里的动作按开始时刻+忙−允许早到+误差余地，取晚的") {
      import mir.rules.Pace
      def act(kind: ActKind, at: Long, dur: Int) = ActionView(kind, Millis(at), dur)
      val own     = SelfPace().sent(Millis(1000), Pace.hitBusyMs(900))
      val ownHit  = own.readyAt(act(ActKind.Attack, 1030, Pace.HitAnimMs), 900)
      val autoHit = SelfPace().readyAt(act(ActKind.Attack, 1000, Pace.HitAnimMs), 900)
      val autoMag = SelfPace().readyAt(act(ActKind.Spell, 1000, Pace.SpellAnimMs), 900)
      val fast    = SelfPace().readyAt(act(ActKind.Attack, 1000, Pace.HitAnimMs), 300)
      val walking = SelfPace().readyAt(act(ActKind.Walk, 1000, Pace.WalkMs), 900)
      assertTrue(
        ownHit == Millis(1900), autoHit == Millis(1000 + 900 - Pace.EarlyMs + SelfPace.LagMs), autoMag == Millis(1000 + 800 - Pace.EarlyMs + SelfPace.LagMs),
        fast == Millis(1510), walking == Millis.zero
      )
    },
    test("点全景地图走过去：寻路走到那一格停下") {
      val r = Rig(blocked = (8 to 14).map(y => Cell(12, y)).toSet)
      r.join(Cell(10, 10))
      r.ctl.goTo(Cell(16, 11))
      r.run(8000)
      assertTrue(r.serverCell == Cell(16, 11), r.ctl.intent == mir.client.game.Intent.Idle)
    },
    test("全景地图点长墙对面：先算好整条路绕过去（只靠就近寻路会卡在墙前），点墙里走到最近处") {
      val wall = (0 to 110).map(y => Cell(60, y)).toSet
      val r    = Rig(blocked = wall, size = 120)
      r.join(Cell(55, 10))
      r.ctl.goTo(Cell(65, 10))
      r.run(120000)
      val far = r.serverCell
      r.ctl.goTo(Cell(60, 5)) // 墙里
      r.run(10000)
      assertTrue(far == Cell(65, 10), r.serverCell.chebyshev(Cell(60, 5)) == 1, r.ctl.intent == mir.client.game.Intent.Idle)
    },
    test("沿路走：走过的去掉、朝前面第 8 格走，到了算到，被挤离路要重算") {
      import mir.client.game.Travel
      val route = Travel.plan(_.y == 0, Cell(0, 0), Cell(20, 0)).get
      val on    = Travel.follow(route, Cell(3, 0))
      val near  = Travel.follow(route, Cell(3, 1))
      assertTrue(
        route.cells.size == 20, route.dest == Cell(20, 0),
        on == Travel.Step.Next(Travel.Route.of((4 to 20).map(Cell(_, 0)).toVector).get, Cell(11, 0)),
        near match { case Travel.Step.Next(rest, to) => rest.cells.head == Cell(4, 0) && to == Cell(11, 0); case _ => false },
        Travel.follow(route, Cell(20, 0)) == Travel.Step.Arrived,
        Travel.follow(route, Cell(10, 5)) == Travel.Step.OffRoute,
        Travel.plan(_ => false, Cell(0, 0), Cell(5, 5)).isEmpty,
        Travel.snap(c => c.x != 5, Cell(5, 3), Cell(9, 3)) == Cell(6, 3), Travel.snap(c => c.x != 5, Cell(5, 3), Cell(0, 3)) == Cell(4, 3)
      )
    },
    test("素材下载缓存（#1251）：失败不记死，过了重试间隔再要就重下；404 记成没有不再下；同一块同时只下一份") {
      import mir.client.assets.{Assets, LoadCache, Loaded}
      import zio.ZIO
      var clock    = 0.0
      var failures = List.empty[String]
      var calls    = 0
      val cache    = LoadCache[String, Int](5000, (k, _) => failures = k :: failures, () => clock)
      def flaky(n: Int): Future[Int] =
        calls += 1
        if calls <= n then Future.failed(Exception("断网")) else Future.successful(42)
      for
        first   <- ZIO.succeed(cache.get("a")(flaky(1)))
        again   <- ZIO.succeed(cache.get("a")(flaky(1))) // 还在下：不另起一份
        _       <- ZIO.fromFuture(_ => cache.settled("a")(flaky(1)))
        failed  <- ZIO.succeed(cache.get("a")(flaky(1))) // 刚失败：等着，不马上重下
        before  = calls
        _       <- ZIO.succeed { clock = 5000 }
        retried <- ZIO.succeed(cache.get("a")(flaky(1)))
        _       <- ZIO.fromFuture(_ => cache.settled("a")(flaky(1)))
        ready   <- ZIO.succeed(cache.get("a")(flaky(1)))
        _       <- ZIO.fromFuture(_ => cache.settled("m")(Future.failed(Assets.Missing("m"))))
        _       <- ZIO.succeed { clock = 1e9 }
        absent  <- ZIO.succeed(cache.get("m")(Future.failed(Exception("不该再下"))))
      yield assertTrue(
        first == Loaded.Pending, again == Loaded.Pending, failed == Loaded.Pending, before == 1,
        retried == Loaded.Pending, calls == 2, ready == Loaded.Ready(42),
        absent == Loaded.Absent, failures == List("a")
      )
    },
    test("全景地图：图片等比缩放居中、格子和屏幕互换、出口格合并成门、名字重叠的不写") {
      import mir.client.ui.{Rect, WorldMap}
      import mir.client.input.Pt
      // 600×400 的图放进 800×600：宽撑满，放大 4/3，上下居中
      val fit = WorldMap.Fit(600, 400, Rect(0, 0, 800, 600), 3)
      val c   = Cell(100, 200)
      val big = WorldMap.Fit(60, 40, Rect(0, 0, 800, 600), 3)
      val gates = WorldMap.exits(List(
        RouteDef(MapId("0"), 10, 10, MapId("A"), 0, 0), RouteDef(MapId("0"), 11, 10, MapId("A"), 0, 0),
        RouteDef(MapId("0"), 30, 10, MapId("A"), 0, 0), RouteDef(MapId("0"), 12, 10, MapId("B"), 0, 0)
      ))
      val marks = List(
        WorldMap.Mark(Pt(100, 100), "甲门", "#fff"), WorldMap.Mark(Pt(100, 102), "乙", "#fff"),
        WorldMap.Mark(Pt(101, 101), "丁", "#fff"), WorldMap.Mark(Pt(395, 100), "丙", "#fff")
      )
      val placed = WorldMap.placeLabels(marks, Rect(0, 0, 400, 300), _.length * 12.0, 14, 6)
      assertTrue(
        fit.scale == 800.0 / 600, fit.y == (600 - 400 * fit.scale) / 2,
        fit.toCell(fit.toScreen(c)).contains(c), fit.toCell(Pt(1, 1)).isEmpty,
        big.scale == 3.0,
        gates == List(WorldMap.Exit(Cell(10, 10), MapId("A")), WorldMap.Exit(Cell(30, 10), MapId("A")), WorldMap.Exit(Cell(12, 10), MapId("B"))),
        placed.map(_._1.text) == List("甲门", "乙", "丙"), placed(1)._2.x == 100 - 6 - 12, placed(2)._2.x == 395 - 6 - 12
      )
    },
    test("高级物品提醒：换下身上同部位那件后主属性净提升就算，不看等级/属性要求（#1215），性别不符不算，戒指和较弱那只比、空位算 0（#1200）") {
      import mir.rules.{Stats, Upgrade}
      import mir.client.ui.{Describe, Line, Tint}
      def item(n: String)                      = catalog.item(ItemName(n)).get
      var uid                                  = 0L
      def inst(n: String, dura: Int = 9000, bonus: Option[ItemBonus] = None) =
        uid += 1
        ItemInstance(ItemUid(uid), ItemName(n), dura, 9000, 1, bonus)
      val stats = Stats(100, 100, Span.zero, Span.zero, Span(0, 30), Span(0, 30), Span(0, 30), 5, 5, 1000, 0, 0, 0, 0, 0)
      def who(job: Job, worn: (EquipSlot, ItemInstance)*) = Upgrade.Wearer(job, Sex.Male, worn.toMap)
      def gain(w: Upgrade.Wearer, n: String, bonus: Option[ItemBonus] = None) = Upgrade.gain(w, item(n), bonus, catalog.item)
      val warrior = who(Job.Warrior, EquipSlot.Weapon -> inst("木剑"))
      // 道士：左戒指天尊（道术 2-7）、右戒指玻璃（0-1）；左手镯戴护身符、右手镯道士手镯（0-1）
      val taoist = who(Job.Taoist,
        EquipSlot.RingL -> inst("天尊戒指"), EquipSlot.RingR -> inst("玻璃戒指"),
        EquipSlot.BraceletL -> inst("护身符"), EquipSlot.BraceletR -> inst("道士手镯"))
      val bothStrong = who(Job.Taoist, EquipSlot.RingL -> inst("天尊戒指"), EquipSlot.RingR -> inst("天尊戒指"))
      val me   = SelfStats(30, Exp(0), None, Hp(1), Mp(1), Gold(0), stats)
      val up   = gain(warrior, "青铜剑")
      val desc = Describe.itemLines(catalog, item("青铜剑"), Some(inst("青铜剑")), Job.Warrior, me, Some(item("木剑") -> None), up)
      val taoUp   = gain(who(Job.Taoist, EquipSlot.Weapon -> inst("降魔")), "无极棍")
      val weakTao = me.copy(level = 26, stats = stats.copy(sc = Span(0, 16)))
      val taoDesc = Describe.itemLines(catalog, item("无极棍"), Some(inst("无极棍")), Job.Taoist, weakTao, Some(item("降魔") -> None), taoUp)
      assertTrue(
        // 战士比攻击：青铜剑 3-7 比木剑 2-5，下限+上限多 3
        up.contains(Upgrade.Gain("攻击", Some(ItemName("木剑")), 3)),
        gain(warrior, "匕首").map(_.amount).contains(2),
        // 道士不看攻击
        gain(who(Job.Taoist, EquipSlot.Weapon -> inst("木剑")), "青铜剑").isEmpty,
        // 穿不上（无极棍要道术 25）也提示：道术 3-5 比降魔 1-2 多 5（#1215）
        taoUp.contains(Upgrade.Gain("道术", Some(ItemName("降魔")), 5)),
        taoDesc(1) == Line("比身上的降魔道术高 5　下限+上限合计", Tint.Upgrade),
        taoDesc.contains(Line("需要道术上限 25（你现在 16）", Tint.Bad)),
        // 性别不符的衣服永远穿不上，不提示
        gain(who(Job.Taoist), "幽灵战衣(男)").map(_.amount).contains(5),
        gain(who(Job.Taoist), "幽灵战衣(女)").isEmpty,
        // 同名但极品多攻击上限 2：算提升
        gain(who(Job.Warrior, EquipSlot.Weapon -> inst("青铜剑")), "青铜剑", Some(ItemBonus.Weapon(dc = 2))).map(_.amount).contains(2),
        gain(who(Job.Warrior, EquipSlot.Weapon -> inst("青铜剑")), "青铜剑").isEmpty,
        // 身上那件持久 0 不加属性，按 0 算
        gain(who(Job.Warrior, EquipSlot.Weapon -> inst("铁剑", dura = 0)), "木剑").map(_.amount).contains(7),
        // 空位：有主属性就算
        gain(who(Job.Taoist), "珍珠戒指").contains(Upgrade.Gain("道术", None, 2)),
        gain(who(Job.Taoist), "古铜戒指").isEmpty,
        // 双戒指和较弱那只（玻璃 0-1）比；两只都比它强就不算
        gain(taoist, "珍珠戒指").contains(Upgrade.Gain("道术", Some(ItemName("玻璃戒指")), 1)),
        gain(bothStrong, "珍珠戒指").isEmpty,
        // 护身符占着的手镯位不算，和道士手镯比：三眼手镯 1-3 比 0-1 多 3
        gain(taoist, "三眼手镯").contains(Upgrade.Gain("道术", Some(ItemName("道士手镯")), 3)),
        // 说明：标题换提升色，第二行写比身上的高多少
        desc.head == Line("青铜剑", Tint.Upgrade),
        desc(1) == Line("比身上的木剑攻击高 3　下限+上限合计", Tint.Upgrade)
      )
    },
    test("拖物品：脱下武器放进包裹、双击穿回、扔掉快捷栏的药，都以服务端的结果为准") {
      import mir.client.ui.{Hand, HandStep}
      val r = Rig()
      r.join(Cell(10, 10))
      def view = r.client.view
      def apply(step: HandStep): Option[Hand] =
        step.cmds.foreach(r.client.send)
        r.run(100)
        step.hand
      // 关掉自动捡物，扔下的药才留在地上
      r.client.send(GameCmd.UpdateSettings(CharSettings(autoPickup = false)))
      val sword = view.worn.get(EquipSlot.Weapon)
      // 拿起武器（只是界面状态，还穿着），放到包裹第 3 格
      val held   = apply(Hand.click(None, ItemPlace.Worn(EquipSlot.Weapon), sword, double = false))
      val stillOn = view.worn.contains(EquipSlot.Weapon)
      val placed = apply(Hand.click(held, ItemPlace.Bag(3), None, double = false))
      val inBag  = view.bag(3).map(_.uid) == sword.map(_.uid) && !view.worn.contains(EquipSlot.Weapon)
      // 包裹里双击：第一下拿起、第二下使用（穿上）
      val again = apply(Hand.click(None, ItemPlace.Bag(3), view.bag(3), double = false))
      apply(Hand.click(again, ItemPlace.Bag(3), view.bag(3), double = true))
      val wornBack = view.worn.get(EquipSlot.Weapon).map(_.uid) == sword.map(_.uid)
      // 穿着的不能直接扔；快捷栏的药扔到脚下
      val wornDrop = Hand.dropOnGround(Hand(ItemPlace.Worn(EquipSlot.Weapon), sword.get))
      val potion   = view.belt(0).get
      apply(Hand.dropOnGround(Hand(ItemPlace.Belt(0), potion)))
      assertTrue(
        held.exists(_.item.uid == sword.get.uid), stillOn, placed.isEmpty, inBag, wornBack,
        wornDrop.cmds.isEmpty && wornDrop.note.isDefined,
        view.belt(0).isEmpty, view.ground.values.exists(_.thing == GroundThing.Item(potion.name)),
        // 手上的东西被服务端挪走了就不再有效
        !Hand.valid(view, Hand(ItemPlace.Belt(0), potion))
      )
    },
    test("批量卖出（#1300）：点包裹里的物品直接整叠卖、金币到账，不值钱的、交易栏里的、包裹外的不卖") {
      import mir.client.ui.{BulkSell, Hand, HandStep}
      val r = Rig()
      r.join(Cell(10, 10))
      def view = r.client.view
      def gold = view.stats.get.gold.value
      val sword = view.worn.get(EquipSlot.Weapon).get
      // 先把武器脱进包裹第 3 格
      r.client.send(GameCmd.MoveItem(sword.uid, ItemPlace.Bag(3)))
      r.run(100)
      val before = gold
      val price  = mir.rules.Pricing.sell(catalog.item(sword.name).get)
      def click(place: ItemPlace, offered: Boolean = false, worthless: Boolean = false): HandStep =
        BulkSell.click(place, Hand.at(view, place), _ => !worthless, _ => offered)
      val inDeal  = click(ItemPlace.Bag(3), offered = true)
      val cheap   = click(ItemPlace.Bag(3), worthless = true)
      val belt    = click(ItemPlace.Belt(0))
      val empty   = click(ItemPlace.Bag(20))
      val sell    = click(ItemPlace.Bag(3))
      sell.cmds.foreach(r.client.send)
      r.run(100)
      assertTrue(
        inDeal.cmds.isEmpty && inDeal.note.isDefined, cheap.cmds.isEmpty && cheap.note.isDefined,
        belt.cmds.isEmpty && belt.note.isDefined, empty.cmds.isEmpty && empty.note.isEmpty,
        sell.cmds == List(GameCmd.QuickSell(sword.uid)), sell.hand.isEmpty,
        view.bag(3).isEmpty, price > 0, gold == before + price
      )
    },
    test("物品出声照原版 ItemClickSound：拿起、放到装备位按物品种类出声，放回包裹不出声") {
      import mir.client.ui.Hand
      val r = Rig()
      r.join(Cell(10, 10))
      val sword  = r.client.view.worn.get(EquipSlot.Weapon).get
      val pickUp = Hand.click(None, ItemPlace.Worn(EquipSlot.Weapon), Some(sword), double = false)
      val held   = pickUp.hand.get
      val toBag  = Hand.click(Some(held), ItemPlace.Bag(3), None, double = false)
      val toWorn = Hand.click(Some(held.copy(from = ItemPlace.Bag(3))), ItemPlace.Worn(EquipSlot.Weapon), None, double = false)
      def sound(name: String) = mir.rules.ItemSounds.click(catalog.item(ItemName(name)).get)
      assertTrue(
        pickUp.clink.contains(sword), toBag.clink.isEmpty, toWorn.clink.contains(sword),
        sound("木剑") == 111, sound("金创药(小量)") == 108, sound("铁手镯") == 117, sound("金项链") == 115,
        sound("青铜头盔") == 116, sound("古铜戒指") == 113, sound("布衣(男)") == 112, sound("鹤嘴锄") == 111, sound("回城卷") == 118, sound("铁矿") == 118
      )
    },
    test("物品、属性、技能说明：装备写清加成和穿戴要求，换下旧装备的差值，药写回多少") {
      import mir.client.ui.{Describe, Tint}
      val r = Rig()
      r.join(Cell(10, 10))
      val me     = r.client.view.stats.get
      val wood   = catalog.item(ItemName("木剑")).get
      val bronze = catalog.item(ItemName("青铜剑")).get
      val lines  = Describe.itemLines(catalog, bronze, None, Job.Warrior, me, Some(wood -> None))
      val potion = Describe.itemLines(catalog, catalog.item(ItemName("金创药(小量)")).get, None, Job.Warrior, me, None)
      val stats  = Describe.statLines("甲", Job.Warrior, me, 15, 8)
      val fire   = catalog.skill(SkillId(1)).get
      val jp     = mir.model.ItemInstance(ItemUid(5L), bronze.name, 9000, 9000, 1, Some(mir.model.ItemBonus.Weapon(dc = 2, strength = 2)))
      val jpDesc = Describe.itemLines(catalog, bronze, Some(jp), Job.Warrior, me, None)
      assertTrue(
        lines.head.text == "青铜剑",
        // 极品：标题换色、总值已含附加、单列多了什么（#1191）
        jpDesc.head == mir.client.ui.Line("青铜剑（极品）", Tint.Special),
        jpDesc.exists(_.text.startsWith(s"攻击 +${bronze.dc.lo}-${bronze.dc.hi + 2}")),
        jpDesc.exists(_.text == "极品：攻击上限 +2、强度 +2（比普通的青铜剑）"),
        lines.exists(_.text.startsWith("攻击 +")),
        lines.exists(l => l.text.startsWith("换下 木剑")),
        // 新人 10 级穿不了八荒（15 级）：要求标红；青铜剑（5 级）穿得上不标红
        Describe.itemLines(catalog, catalog.item(ItemName("八荒")).get, None, Job.Warrior, me, None)
          .exists(l => l.text.startsWith("需要等级") && l.tint == Tint.Bad),
        lines.exists(l => l.text.startsWith("需要等级") && l.tint != Tint.Bad),
        // 药写清每跳回多少、几跳、共回多少（金创药(小量) 体力 20：每 0.4 秒 4 点，5 次）
        potion.exists(_.text == "每 0.4 秒回复 体力 4，共 5 次"),
        potion.exists(_.text.contains("共回 体力 20")),
        lines.exists(_.text.startsWith("持久")) == false, // 没有实例时不写持久
        stats.exists(_.text.startsWith("体力")) && stats.exists(_.text.contains("自然回")),
        stats.head.text.contains("战士"),
        Describe.skillLines(catalog, fire, LearnedSkill(fire.id, 0, 3, Some(1)), me).exists(_.text.startsWith("耗魔法")),
        // 每个技能都有具体数值（各级对照或效果行里带数字），不是空话
        catalog.data.skills.forall { d =>
          val ls = Describe.skillLines(catalog, d, LearnedSkill(d.id, 1, 0, None), me)
          ls.size >= 4 && ls.drop(2).exists(_.text.exists(_.isDigit)) && !ls.exists(_.text.contains("未算出"))
        }
      )
    },
    test("技能说明排版（#1295）：一件事一行、不靠自动折行，各级每级一行、当前级绿字；折行断在标点后") {
      import mir.client.ui.{Describe, Gfx, Tint}
      import mir.rules.Stats
      // 宋体 12 像素：汉字和全角符号 12，半角 6
      def w(s: String): Double = s.map(c => if c < '\u2e80' then 6 else 12).sum.toDouble
      val stats = Stats(300, 200, Span(5, 20), Span(5, 15), Span(10, 40), Span(8, 35), Span(8, 35), 15, 15, 1000, 0, 0, 0, 0, 0)
      val me    = SelfStats(35, Exp(0), None, Hp(1), Mp(1), Gold(0), stats)
      val all   = catalog.data.skills.map(d => d -> Describe.skillLines(catalog, d, LearnedSkill(d.id, 1, 0, None), me))
      val fire  = all.find(_._1.id == SkillId(1)).get._2
      val levelRows = fire.filter(_.text.matches("\\d 级：.*"))
      assertTrue(
        // 说明框最宽 360 像素：普通属性下每行都放得下
        all.forall((_, ls) => ls.forall(l => w(l.text) <= 360)),
        // 手机横屏（如 667×375）说明框按触屏倍数放大后约放 25 行，再多就要缩小字
        all.forall((_, ls) => ls.size <= 25),
        levelRows.map(_.text.take(1)) == List("0", "1", "2", "3"),
        levelRows.filter(_.tint == Tint.Good).map(_.text.take(1)) == List("1"),
        levelRows(1).text.startsWith("1 级：耗魔法 "),
        // 折行：断在后半行的标点后，续行缩进；没有合适标点就按字硬断；空行保留
        Gfx.breakText("一二三四，五六七八", 60, w) == List("一二三四，", "　五六七八"),
        Gfx.breakText("一，二三四五六七", 60, w) == List("一，二三四", "　五六七"),
        Gfx.breakText("威力 18-55", 360, w) == List("威力 18-55"),
        Gfx.breakText("", 60, w) == List("")
      )
    },
    test("设置：百分比每次 5，卡在 0–95；每一行改的是自己那一项") {
      import mir.client.render.ViewOptions
      import mir.client.ui.SettingRow
      val s0 = CharSettings()
      val o0 = ViewOptions()
      /** 切换一个小勾选只动它自己那一项，再切回来复原 */
      def get1(b: mir.client.ui.SettingCheck, s: CharSettings): Boolean =
        val t = b.set(s, !b.get(s))
        t != s && b.get(t) != b.get(s) && b.set(t, b.get(s)) == s
      val flipped = SettingRow.all.map {
        case SettingRow.GameToggle(_, get, set)  => set(s0, !get(s0)) != s0 && get(set(s0, !get(s0))) != get(s0)
        case SettingRow.GamePercent(_, get, set) => get(set(s0, 55)) == 55
        case SettingRow.GameChecks(_, boxes)     => boxes.forall(b => get1(b, s0))
        case SettingRow.LocalToggle(_, get, set) => get(set(o0, !get(o0))) != get(o0)
        // 档位滑条（#1301）：开着自动捡、勾了装备才显示，关了总开关或去掉装备就藏起来
        case row @ SettingRow.GameTier(_) =>
          row.shown(s0) && !row.shown(s0.copy(autoPickup = false)) && !row.shown(s0.copy(pickupKinds = Set(PickupKind.Gold)))
      }
      assertTrue(
        flipped.forall(identity),
        SettingRow.stepPercent(0, up = false) == 0,
        SettingRow.stepPercent(95, up = true) == 95,
        SettingRow.stepPercent(40, up = true) == 45
      )
    },
    test("页面和服务端版本（#1242、#1289）：同一版照常；只有提交不同能接着玩；通讯格式或数据不同马上刷新；为这一版刷新过还对不上就不再刷") {
      val mine = Release("aaa", "w1", "d1")
      def server(commit: String, wire: String = "w1", data: String = "d1") = Release(commit, wire, data)
      assertTrue(
        Freshness.judge(mine, server("aaa"), None) == Freshness.Current,
        Freshness.judge(mine, server("aaa"), Some("aaa")) == Freshness.Current,
        Freshness.judge(mine, server("bbb"), None) == Freshness.Stale,
        Freshness.judge(mine, server("bbb", wire = "w2"), None) == Freshness.Reload,
        Freshness.judge(mine, server("bbb", data = "d2"), None) == Freshness.Reload,
        Freshness.judge(mine, server("bbb", wire = "w2"), Some("ccc")) == Freshness.Reload,
        Freshness.judge(mine, server("bbb", wire = "w2"), Some("bbb")) == Freshness.Stuck,
        Freshness.judge(mine, server("bbb"), Some("bbb")) == Freshness.Stuck
      )
    },
    test("连上后先比版本再进游戏（#1289）：要马上刷新就不进游戏也不进选人，免得把停服时留着的宝宝领了又丢；同一版照常") {
      var logins = 0
      def run(verdict: Freshness, resume: Option[CharId]): (Int, Boolean) =
        logins = 0
        val c = GameClient(catalog, ClientEnv(() => 0.0, _ => Future.never, (_, _, _) => (), _ => verdict))
        c.ui = new UiEvents:
          export UiEvents.Ignore.{loggedIn as _, *}
          def loggedIn(account: String): Unit = logins += 1
        resume.foreach(c.resume)
        c.receive(LinkEvent.Up)
        val early = c.recent.exists(_.isInstanceOf[SessionCmd.EnterGame])
        c.receive(LinkEvent.Received(ServerMsg.LoggedIn("me", "x", "w", "d")))
        (logins, !early && c.recent.contains(SessionCmd.EnterGame(CharId(7))))
      val playing = Some(CharId(7))
      assertTrue(
        run(Freshness.Current, None) == (1, false), run(Freshness.Reload, None) == (0, false), run(Freshness.Stuck, None) == (1, false),
        run(Freshness.Current, playing) == (0, true), run(Freshness.Reload, playing) == (0, false),
        run(Freshness.Stale, playing) == (0, true), run(Freshness.Stuck, playing) == (0, true)
      )
    },
    test("地上出现达到档位的装备在聊天栏提示一次（#1301）：不分职业，「全部」按沃玛，进图后重新算") {
      var notes = Vector.empty[String]
      val c = GameClient(catalog, ClientEnv(() => 0.0, _ => Future.never, (_, _, _) => ()))
      c.map = Some(map0)
      c.ui = new UiEvents:
        export UiEvents.Ignore.{notice as _, *}
        def notice(text: String, tone: Tone): Unit = notes :+= text
      def got(m: ServerMsg) = c.receive(LinkEvent.Received(m))
      def drop(id: Int, n: String) = got(ServerMsg.GroundAppear(GroundView(GroundId(id), Cell(12, 34), GroundThing.Item(ItemName(n)))))
      got(ServerMsg.EnterMap(ActorId(1), MapId("0"), Cell(10, 10), Dir.Down, Millis(0)))
      got(ServerMsg.Settings(CharSettings(pickupTier = PickupFloor.All)))
      drop(1, "铁剑"); drop(2, "炼狱"); drop(2, "炼狱")
      got(ServerMsg.Settings(CharSettings(pickupTier = PickupFloor.Zuma)))
      drop(3, "炼狱"); drop(4, "裁决之杖")
      got(ServerMsg.EnterMap(ActorId(1), MapId("0"), Cell(10, 10), Dir.Down, Millis(0)))
      drop(4, "裁决之杖")
      assertTrue(notes == Vector("[掉落] 炼狱（沃玛）在 (12,34)", "[掉落] 裁决之杖（祖玛）在 (12,34)", "[掉落] 裁决之杖（祖玛）在 (12,34)"))
    },
    test("boss 进入视野在聊天栏红字提示一次（#1323）：写方向和坐标，宝宝、普通怪、死的不提示，进图后重新算，地图没下完不提示") {
      var notes = Vector.empty[(String, Tone)]
      val c = GameClient(catalog, ClientEnv(() => 0.0, _ => Future.never, (_, _, _) => ()))
      c.map = Some(map0)
      c.ui = new UiEvents:
        export UiEvents.Ignore.{notice as _, *}
        def notice(text: String, tone: Tone): Unit = notes :+= (text -> tone)
      def got(m: ServerMsg) = c.receive(LinkEvent.Received(m))
      def actor(id: Int, look: Look, at: Cell, dead: Boolean = false) =
        got(ServerMsg.Appear(ActorView(ActorId(id), look, at, Dir.Down, Hp(1), Hp(1), ActionView(ActKind.Stand, Millis(0), 0), dead)))
      def mon(n: String, pet: Option[PetView] = None) = Look.Monster(MonsterName(n), Cover.Open, pet)
      val pv = PetView(ActorId(1), Span(0, 0), Span(0, 0), Span(0, 0))
      def enter() = got(ServerMsg.EnterMap(ActorId(1), MapId("0"), Cell(10, 10), Dir.Down, Millis(0)))
      enter()
      actor(1, Look.Human("我", Job.Warrior, Sex.Male, 0, None, None), Cell(10, 10))
      actor(2, mon("沃玛教主"), Cell(20, 0)); actor(2, mon("沃玛教主"), Cell(20, 0))
      actor(3, mon("沃玛卫士"), Cell(11, 11)); actor(4, mon("祖玛教主", Some(pv)), Cell(11, 11)); actor(5, mon("牛魔王"), Cell(1, 1), dead = true)
      enter(); actor(2, mon("沃玛教主"), Cell(10, 30))
      // 新图还没下完（界面还在选人）时来的 boss 不提示，进入画面时再补
      got(ServerMsg.EnterMap(ActorId(1), MapId("1"), Cell(10, 10), Dir.Down, Millis(0))); actor(6, mon("祖玛教主"), Cell(10, 10))
      assertTrue(
        notes == Vector("[BOSS] 沃玛教主出现在你东北方 (20,0)" -> Tone.Bad, "[BOSS] 沃玛教主出现在你南边 (10,30)" -> Tone.Bad),
        GameClient.bearing(Cell(10, 10), Cell(30, 12)) == "东边",
        GameClient.bearing(Cell(10, 10), Cell(10, 10)) == "身边",
        GameClient.bearing(Cell(10, 10), Cell(0, 0)) == "西北方",
        mir.client.ui.MiniMark.of(mon("沃玛教主")) == mir.client.ui.MiniMark.Boss,
        mir.client.ui.MiniMark.of(mon("沃玛教主", Some(pv))) == mir.client.ui.MiniMark.Pet,
        mir.client.ui.MiniMark.of(mon("沃玛卫士")) == mir.client.ui.MiniMark.Monster
      )
    },
    test("能接着玩的旧页面（#1289）：重连回原处不刷新，下一次进图（换图、回城、复活）先告诉服务端再刷新，只刷一次") {
      var refreshed = Vector.empty[String]
      val c = GameClient(catalog, ClientEnv(() => 0.0, _ => Future.never, (_, _, _) => (), _ => Freshness.Stale, commit => refreshed :+= commit))
      def enterMap(id: String) = c.receive(LinkEvent.Received(ServerMsg.EnterMap(ActorId(1), MapId(id), Cell(10, 10), Dir.Down, Millis(0))))
      c.resume(CharId(7))
      c.receive(LinkEvent.Up)
      c.receive(LinkEvent.Received(ServerMsg.LoggedIn("me", "bbb", "w", "d")))
      enterMap("0") // 重连回到原处
      val afterRejoin = (refreshed, c.view.map)
      enterMap("3") // 换图
      val afterCut = (refreshed, c.recent.lastOption)
      enterMap("0")
      assertTrue(
        afterRejoin == (Vector.empty, Some(MapId("0"))),
        afterCut == (Vector("bbb"), Some(SessionCmd.Reloading)),
        refreshed == Vector("bbb")
      )
    },
    test("断线重连（#1290）：服务器说在重启就每 0.5 秒试、重启期间连不上也不变，1 分钟没回来按普通断线；普通断线退避到 5 秒") {
      import mir.client.net.{Outage, Reconnect}
      val bye      = Reconnect.classify(CloseCode.Restarting, None, 1000)
      val stillUp  = Reconnect.classify(1006, Some(bye), 20_000)          // 新服还没起来，连不上
      val gaveUp   = Reconnect.classify(1006, Some(bye), 62_000)
      val lost     = Reconnect.classify(1006, None, 1000)
      val lostWaits = (0 to 6).map(Reconnect.delayMs(lost, _, 1000)).toList
      assertTrue(
        bye == Outage.Restarting(1000), stillUp == bye, gaveUp == Outage.Lost,
        Reconnect.delayMs(stillUp, 30, 20_000) == 500, Reconnect.notice(bye).contains("更新中"),
        Reconnect.classify(4401, None, 0) == Outage.Unauthorized,
        lostWaits == List(500, 1000, 2000, 4000, 5000, 5000, 5000)
      )
    }
  )
