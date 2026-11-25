package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.{ItemEffects, ItemKind, Rng, TradeRules}
import mir.terrain.Terrain
import mir.testkit.TestFiles
import mir.view.ViewState
import zio.json.*
import zio.test.*

/**
 * 腰带、靴子两个装备位（#1311）：数据表里还没有这两类物品，测试往物品表里临时加一条腰带、一条靴子，
 * 看分类、穿脱、属性、旧存档、交易。
 */
object BeltBootsSpec extends ZIOSpecDefault:
  private lazy val base: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private def temp(id: Int, name: String, mode: Int, ac: Span, mac: Span): ItemDef =
    ItemDef(
      id = id, name = ItemName(name), mode = mode, shape = 0, weight = 1, ani = 0, source = 0, look = 0, dura = 5000,
      ac = ac, mac = mac, dc = Span.zero, mc = Span.zero, sc = Span.zero, need = 0, needLevel = 1, price = 100
    )

  private lazy val belt  = temp(base.data.items.map(_.id).max + 1, "测试腰带", ItemKind.BeltMode, Span(1, 2), Span(0, 1))
  private lazy val boots = temp(base.data.items.map(_.id).max + 2, "测试靴子", ItemKind.BootsMode, Span(0, 3), Span(1, 1))
  private lazy val catalog: Catalog = Catalog(base.data.copy(items = base.data.items ++ List(belt, boots)))

  private val testMap = MapDef(MapId("T"), "T", "测试", Set.empty, 60, 60)

  private final class Sim:
    val w = World(catalog, testMap, Terrain.open(60, 60), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0))
    val views = scala.collection.mutable.Map.empty[ActorId, ViewState]

    def flush(): Unit =
      w.flush().messages.foreach((id, ms) => views(id) = views.getOrElse(id, ViewState()).applyAll(ms))

    def cmd(p: Player, c: GameCmd): Unit =
      w.handle(p.id, c)
      flush()

    def run(ms: Int): Unit = (0 until ms by 50).foreach { _ =>
      w.tick(w.now + 50)
      flush()
    }

    def join(name: String, at: Cell, job: Job = Job.Taoist): Player =
      val d = CharacterData.newbie(catalog, name, job, Sex.Male, UidSource.counter(name.hashCode.abs.toLong * 1000))
      val p = w.player(w.join(d.copy(place = Place(w.mapId, at)))).get
      flush()
      p

    def give(p: Player, d: ItemDef): ItemInstance =
      val it = w.newItem(d.name).get
      if !Inventory.add(catalog, p, it) then throw new AssertionError(s"放不进 ${d.name}")
      it

  private def has(p: Player, n: ItemName): Boolean = (p.bag.iterator ++ p.belt.iterator).flatten.exists(_.name == n)

  def spec = suite("腰带、靴子（#1311）")(
    test("StdMode 54 是腰带、52 是靴子，各占自己的位置；防御魔防照字面加；自动捡物算装备") {
      assertTrue(
        ItemKind.of(belt) == ItemKind.Equip(List(EquipSlot.Belt)),
        ItemKind.of(boots) == ItemKind.Equip(List(EquipSlot.Boots)),
        ItemEffects.of(belt) == ItemEffects(ac = Span(1, 2), mac = Span(0, 1)),
        ItemEffects.of(boots) == ItemEffects(ac = Span(0, 3), mac = Span(1, 1)),
        ItemKind.pickupKind(belt) == PickupKind.Equip,
        !ItemKind.stacks(belt)
      )
    },
    test("穿上两件，属性加上两件的防御魔防；视野里的身上装备对得上；脱下回包裹，属性还原") {
      val s       = Sim()
      val p       = s.join("甲", Cell(30, 30))
      val before  = p.stats
      val b1      = s.give(p, belt)
      val b2      = s.give(p, boots)
      s.cmd(p, GameCmd.Equip(b1.uid, None))
      s.cmd(p, GameCmd.Equip(b2.uid, None))
      val worn    = p.stats
      val seen    = s.views(p.id).worn
      val wrongSlot = { s.cmd(p, GameCmd.Unequip(EquipSlot.Belt)); s.cmd(p, GameCmd.Equip(b1.uid, Some(EquipSlot.Boots))); p.worn.contains(EquipSlot.Boots) && !p.worn.contains(EquipSlot.Belt) && has(p, belt.name) }
      s.cmd(p, GameCmd.Unequip(EquipSlot.Boots))
      assertTrue(
        worn.ac == Span(before.ac.lo + 1, before.ac.hi + 5), worn.mac == Span(before.mac.lo + 1, before.mac.hi + 2),
        seen.get(EquipSlot.Belt).map(_.uid).contains(b1.uid), seen.get(EquipSlot.Boots).map(_.uid).contains(b2.uid),
        wrongSlot,
        p.stats == before, !p.worn.contains(EquipSlot.Belt), !p.worn.contains(EquipSlot.Boots),
        has(p, belt.name), has(p, boots.name)
      )
    },
    test("旧存档（身上没有这两个位置）照常读进来、两格为空；带着两件的存档原样读回") {
      val d       = CharacterData.newbie(catalog, "甲", Job.Warrior, Sex.Male, UidSource.counter(1))
      val old     = d.toJson
      val decoded = old.fromJson[CharacterData]
      val full    = d.copy(worn = d.worn ++ Map(
        EquipSlot.Belt  -> ItemInstance.fresh(ItemUid(900L), belt),
        EquipSlot.Boots -> ItemInstance.fresh(ItemUid(901L), boots)
      ))
      assertTrue(
        !old.contains("\"boots\""),
        decoded.map(_.worn) == Right(d.worn), decoded.exists(x => !x.worn.contains(EquipSlot.Belt) && !x.worn.contains(EquipSlot.Boots)),
        full.toJson.fromJson[CharacterData] == Right(full)
      )
    },
    test("腰带、靴子能交易") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      val b1 = s.give(a, belt)
      val b2 = s.give(a, boots)
      s.cmd(a, GameCmd.DealTry(b.id))
      s.cmd(b, GameCmd.DealTry(a.id))
      s.cmd(a, GameCmd.DealPut(b1.uid))
      s.cmd(a, GameCmd.DealPut(b2.uid))
      s.run(TradeRules.SettleMs.toInt + 100)
      s.cmd(a, GameCmd.DealConfirm)
      s.cmd(b, GameCmd.DealConfirm)
      assertTrue(has(b, belt.name), has(b, boots.name), !has(a, belt.name), !has(a, boots.name))
    }
  )
