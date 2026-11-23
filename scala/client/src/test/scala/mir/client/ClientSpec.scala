package mir.client

import mir.client.assets.MapData
import mir.client.game.*
import mir.client.input.{Joystick, Pt, Stick}
import mir.client.net.{LinkEvent, ServerClock, Transport}
import mir.client.render.Frames
import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.Rng
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
  private final class Rig(blocked: Set[Cell] = Set.empty):
    val md    = MapData.parse(testMap.id, "T", mapBin(40, 40, blocked)).fold(e => throw new AssertionError(e), identity)
    var now   = 0.0
    val world = World(catalog, testMap, md.terrain, Rng.seeded(3), UidSource.counter(1), Millis(0))
    val client = GameClient(catalog, ClientEnv(() => now, _ => Future.successful(md), (_, _, _) => ()))
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
      me = world.join(d.copy(place = Place(testMap.id, at)))
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
    test("点小地图走过去：寻路走到那一格停下") {
      val r = Rig(blocked = (8 to 14).map(y => Cell(12, y)).toSet)
      r.join(Cell(10, 10))
      r.ctl.goTo(Cell(16, 11))
      r.run(8000)
      assertTrue(r.serverCell == Cell(16, 11), r.ctl.intent == mir.client.game.Intent.Idle)
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
      val lines  = Describe.itemLines(catalog, bronze, None, Job.Warrior, me, Some(wood))
      val potion = Describe.itemLines(catalog, catalog.item(ItemName("金创药(小量)")).get, None, Job.Warrior, me, None)
      val stats  = Describe.statLines("甲", Job.Warrior, me, 15, 8)
      val fire   = catalog.skill(SkillId(1)).get
      assertTrue(
        lines.head.text == "青铜剑",
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
      val flipped = SettingRow.all.map {
        case SettingRow.GameToggle(_, get, set)  => set(s0, !get(s0)) != s0 && get(set(s0, !get(s0))) != get(s0)
        case SettingRow.GamePercent(_, get, set) => get(set(s0, 55)) == 55
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
