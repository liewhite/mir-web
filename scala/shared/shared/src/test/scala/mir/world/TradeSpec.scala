package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.{Rng, TradeRules}
import mir.terrain.Terrain
import mir.testkit.TestFiles
import mir.view.ViewState
import zio.test.*

/** 玩家交易（#1222）：开始、成交、取消、包裹满、下线、走开、交易栏里的东西被用掉；重点是不丢、不复制东西。 */
object TradeSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private val testMap = MapDef(MapId("T"), "T", "测试", Set.empty, 60, 60)

  private final class Sim:
    val w = World(catalog, testMap, Terrain.open(60, 60), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0))
    val views = scala.collection.mutable.Map.empty[ActorId, ViewState]
    var saved = Set.empty[ActorId]

    def flush(): Map[ActorId, Vector[ServerMsg]] =
      val out = w.flush()
      out.messages.foreach((id, ms) => views(id) = views.getOrElse(id, ViewState()).applyAll(ms))
      saved ++= out.checkpoints
      out.messages

    def run(ms: Int): Unit = (0 until ms by 50).foreach { _ =>
      w.tick(w.now + 50)
      flush()
    }

    def cmd(p: Player, c: GameCmd): Vector[ServerMsg] =
      w.handle(p.id, c)
      flush().getOrElse(p.id, Vector.empty)

    def join(name: String, at: Cell): Player =
      val d = CharacterData.newbie(catalog, name, Job.Warrior, Sex.Male, UidSource.counter(name.hashCode.abs.toLong * 1000))
      w.player(w.join(d.copy(place = Place(w.mapId, at)))).get

    def give(p: Player, name: String, count: Int = 1): ItemInstance =
      val it = w.newItem(ItemName(name)).get.copy(count = count)
      if !Inventory.add(catalog, p, it) then throw new AssertionError(s"放不进 $name")
      Inventory.carried(p, it.uid).fold(it)(_._2)

    def deal(p: Player): Option[DealView] = views.get(p.id).flatMap(_.deal)

    /** 两人开始交易 */
    def open(a: Player, b: Player): Unit =
      cmd(a, GameCmd.DealTry(b.id)): Unit
      cmd(b, GameCmd.DealTry(a.id)): Unit

    /** 都等够 1 秒再确认 */
    def confirmBoth(a: Player, b: Player): Unit =
      run(TradeRules.SettleMs.toInt + 100)
      cmd(a, GameCmd.DealConfirm): Unit
      cmd(b, GameCmd.DealConfirm): Unit

  /** 身上（包裹 + 快捷栏）全部物品：名字 → 总个数 */
  private def counts(ps: Player*): Map[ItemName, Int] =
    ps.flatMap(p => (p.bag.iterator ++ p.belt.iterator).flatten).groupMapReduce(_.name)(_.count)(_ + _)

  private def uids(ps: Player*): Vector[ItemUid] = ps.toVector.flatMap(p => (p.bag.iterator ++ p.belt.iterator).flatten.map(_.uid))

  private def has(p: Player, name: String): Int = counts(p).getOrElse(ItemName(name), 0)

  private def refused(r: Vector[ServerMsg]) = r.exists { case ServerMsg.Refused(_) => true; case _ => false }

  def spec = suite("交易")(
    test("一方请求、对方也点交易才打开；不相邻、和自己都不行") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      val c = s.join("丙", Cell(40, 30))
      val far  = refused(s.cmd(a, GameCmd.DealTry(c.id)))
      val self = refused(s.cmd(a, GameCmd.DealTry(a.id)))
      s.cmd(a, GameCmd.DealTry(b.id))
      val waiting = s.deal(a).isEmpty && s.deal(b).isEmpty
      s.cmd(b, GameCmd.DealTry(a.id))
      assertTrue(
        far, self, waiting,
        s.deal(a).map(_.partner).contains("乙"), s.deal(b).map(_.partner).contains("甲"),
        refused(s.cmd(c, GameCmd.DealConfirm))
      )
    },
    test("请求过了 30 秒就作废") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      s.cmd(a, GameCmd.DealTry(b.id))
      s.run(TradeRules.AskMs.toInt + 500)
      s.cmd(b, GameCmd.DealTry(a.id))
      assertTrue(s.deal(a).isEmpty, s.deal(b).isEmpty)
    },
    test("成交：东西和金币互换，一叠可以只给几个，不多不少；双方立刻存档") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      a.gold = Gold(1000)
      b.gold = Gold(50)
      val sword = s.give(a, "铁剑")
      val pots  = s.give(b, "金创药(中量)", 10)
      val before = counts(a, b)
      val goldBefore = a.gold.value + b.gold.value
      s.open(a, b)
      s.cmd(a, GameCmd.DealPut(sword.uid))
      s.cmd(a, GameCmd.DealGold(Gold(300)))
      s.cmd(b, GameCmd.DealPut(pots.uid, Some(4)))
      val seen = s.deal(a).map(_.theirs.items.map(i => i.name.value -> i.count))
      s.confirmBoth(a, b)
      val all = uids(a, b)
      assertTrue(
        seen.contains(List("金创药(中量)" -> 4)),
        s.deal(a).isEmpty, s.deal(b).isEmpty,
        has(a, "铁剑") == 0, has(b, "铁剑") == 1,
        has(a, "金创药(中量)") == 4, has(b, "金创药(中量)") == 6,
        a.gold == Gold(700), b.gold == Gold(350),
        counts(a, b) == before, a.gold.value + b.gold.value == goldBefore,
        all.distinct.size == all.size,
        s.saved == Set(a.id, b.id)
      )
    },
    test("改了交易栏双方确认都取消；对方刚改过 1 秒内不能确认") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      val sword = s.give(a, "铁剑")
      val pots  = s.give(b, "金创药(中量)", 3)
      s.open(a, b)
      s.cmd(a, GameCmd.DealPut(sword.uid))
      s.run(1100)
      s.cmd(b, GameCmd.DealConfirm)
      val bConfirmed = s.deal(a).exists(_.theirs.confirmed)
      s.cmd(a, GameCmd.DealTake(sword.uid))
      val cleared = s.deal(a).exists(d => !d.theirs.confirmed && d.mine.items.isEmpty)
      s.cmd(b, GameCmd.DealPut(pots.uid))
      val tooFast = refused(s.cmd(a, GameCmd.DealConfirm))
      assertTrue(bConfirmed, cleared, tooFast, has(a, "铁剑") == 1, has(b, "金创药(中量)") == 3)
    },
    test("取消：东西都还在各自身上") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      a.gold = Gold(500)
      val sword = s.give(a, "铁剑")
      val before = (counts(a), counts(b), a.gold, b.gold)
      s.open(a, b)
      s.cmd(a, GameCmd.DealPut(sword.uid))
      s.cmd(a, GameCmd.DealGold(Gold(500)))
      s.cmd(b, GameCmd.DealCancel)
      assertTrue(s.deal(a).isEmpty, s.deal(b).isEmpty, (counts(a), counts(b), a.gold, b.gold) == before)
    },
    test("包裹满放不下：不成交，两人包裹金币原样，交易栏留着") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      a.gold = Gold(100)
      val sword = s.give(a, "铁剑")
      s.give(a, "短剑")
      // 乙的包裹塞满不能叠的东西
      b.bag.indices.foreach(i => b.bag(i) = w0(s, "木剑"))
      val before = (a.bag.toVector, a.belt.toVector, b.bag.toVector, b.belt.toVector, a.gold, b.gold)
      s.open(a, b)
      s.cmd(a, GameCmd.DealPut(sword.uid))
      s.cmd(a, GameCmd.DealGold(Gold(100)))
      s.confirmBoth(a, b)
      assertTrue(
        (a.bag.toVector, a.belt.toVector, b.bag.toVector, b.belt.toVector, a.gold, b.gold) == before,
        s.deal(a).exists(d => d.mine.items.size == 1 && !d.mine.confirmed && !d.theirs.confirmed),
        s.saved.isEmpty
      )
    },
    test("包裹满但给出去的东西腾出了格子：能成交") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      val sword = s.give(a, "铁剑")
      b.bag.indices.foreach(i => b.bag(i) = w0(s, "木剑"))
      val give = b.bag(0).get
      s.open(a, b)
      s.cmd(a, GameCmd.DealPut(sword.uid))
      s.cmd(b, GameCmd.DealPut(give.uid))
      s.confirmBoth(a, b)
      assertTrue(s.deal(a).isEmpty, has(b, "铁剑") == 1, Inventory.carried(a, give.uid).isDefined)
    },
    test("一方下线：交易取消，两人的东西都在（下线的存档里有）") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      val sword = s.give(a, "铁剑")
      s.open(a, b)
      s.cmd(a, GameCmd.DealPut(sword.uid))
      s.cmd(b, GameCmd.DealConfirm)
      val data = s.w.leave(a.id).get
      s.run(100)
      assertTrue(s.deal(b).isEmpty, data.bag.flatten.exists(_.uid == sword.uid), has(b, "铁剑") == 0)
    },
    test("走开超过 2 格、死了都取消") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      s.open(a, b)
      s.w.grid.teleportActor(b, Cell(33, 30))
      s.run(100)
      val walked = s.deal(a).isEmpty
      s.open(a, b) // 太远，开不了
      val farOpen = s.deal(a).isEmpty
      s.w.grid.teleportActor(b, Cell(31, 31))
      s.open(a, b)
      val reopened = s.deal(a).isDefined
      s.w.vitals.damage(b, 1_000_000, None)
      s.run(100)
      assertTrue(walked, farOpen, reopened, s.deal(a).isEmpty)
    },
    test("交易栏里的药被喝掉、扔掉：交易栏跟着改、确认取消，成交时只给剩下的") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      val pots  = s.give(a, "金创药(中量)", 3)
      val sword = s.give(a, "铁剑")
      s.open(a, b)
      s.cmd(a, GameCmd.DealPut(pots.uid))
      s.cmd(a, GameCmd.DealPut(sword.uid))
      s.run(1100)
      s.cmd(b, GameCmd.DealConfirm)
      s.cmd(a, GameCmd.UseItem(pots.uid))
      val afterDrink = s.deal(b).map(d => (d.theirs.items.map(i => i.name.value -> i.count).toSet, d.mine.confirmed))
      s.cmd(a, GameCmd.DropItem(sword.uid))
      val afterDrop = s.deal(b).map(_.theirs.items.map(_.name.value))
      s.confirmBoth(a, b)
      assertTrue(
        afterDrink.contains((Set("金创药(中量)" -> 2, "铁剑" -> 1), false)),
        afterDrop.contains(List("金创药(中量)")),
        has(b, "金创药(中量)") == 2, has(a, "金创药(中量)") == 0, has(b, "铁剑") == 0
      )
    },
    test("不能放别人的、不存在的东西，不能放超过身上的金币，同一件放两次只算一次") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      a.gold = Gold(10)
      val bs = s.give(b, "铁剑")
      val as = s.give(a, "短剑")
      s.open(a, b)
      val notMine  = refused(s.cmd(a, GameCmd.DealPut(bs.uid)))
      val tooRich  = refused(s.cmd(a, GameCmd.DealGold(Gold(11))))
      val negative = refused(s.cmd(a, GameCmd.DealGold(Gold(-5))))
      s.cmd(a, GameCmd.DealPut(as.uid))
      s.cmd(a, GameCmd.DealPut(as.uid))
      s.confirmBoth(a, b)
      assertTrue(notMine, tooRich, negative, has(b, "短剑") == 1, has(a, "短剑") == 0, has(b, "铁剑") == 1)
    },
    test("视野副本：交易窗口和世界一致，换图清掉") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      val sword = s.give(a, "铁剑")
      s.open(a, b)
      s.cmd(a, GameCmd.DealPut(sword.uid))
      val mine = s.deal(a).map(_.mine.items.map(_.uid))
      val vs = s.views(a.id).apply(ServerMsg.EnterMap(a.id, MapId("0"), Cell(1, 1), Dir.Down, Millis(0)))
      assertTrue(mine.contains(List(sword.uid)), s.deal(b).map(_.theirs.items.map(_.uid)) == mine, vs.deal.isEmpty)
    }
  )

  /** 一件新物品（不放进身上） */
  private def w0(s: Sim, name: String): Option[ItemInstance] = s.w.newItem(ItemName(name))
