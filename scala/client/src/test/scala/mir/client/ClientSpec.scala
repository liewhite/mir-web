package mir.client

import mir.client.assets.MapData
import mir.client.game.*
import mir.client.input.{Joystick, KeyMove, KeyStick, MoveKey, Pt, Stick}
import mir.client.net.{LinkEvent, ServerClock, Transport}
import mir.client.render.Frames
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
        // 1 级穿不了青铜剑：要求标红
        lines.exists(l => l.text.startsWith("需要等级") && l.tint == Tint.Bad),
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
      }
      assertTrue(
        flipped.forall(identity),
        SettingRow.stepPercent(0, up = false) == 0,
        SettingRow.stepPercent(95, up = true) == 95,
        SettingRow.stepPercent(40, up = true) == 45
      )
    }
  )
