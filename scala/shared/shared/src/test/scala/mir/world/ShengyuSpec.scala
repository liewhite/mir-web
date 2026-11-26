package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.{ItemEffects, ItemKind, Rates, Rng}
import mir.terrain.Terrain
import mir.testkit.TestFiles
import mir.view.ViewState
import zio.test.*

/** 圣域（#1332，用户 11-26 定）：幻境十层右下角进、33 种圣域怪、四档算 boss、荣誉勋章戴在勋章格。数据由 tools/import_shengyu.py 生成。 */
object ShengyuSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private val Sy = MapId("SY01")
  private def mon(n: String): MonsterDef = catalog.monster(MonsterName(n)).get
  private def item(n: String): ItemDef   = catalog.item(ItemName(n)).get
  private def drops(n: String)           = catalog.data.drops(MonsterName(n))

  private val Tier4 = List("圣域血魔", "圣域金刚", "圣域修罗", "圣域教主", "圣域魔魂", "圣域牛魔王")

  def spec = suite("圣域（#1332）")(
    test("地图用桃源之门的素材；十层右下角楼梯进、西边的门回十层；不进传送菜单；33 种怪全图随机刷") {
      val d      = catalog.data
      val in     = d.routes.filter(r => r.from == MapId("H010") && r.to == Sy).map(r => (r.x, r.y)).toSet
      val out    = d.routes.filter(_.from == Sy).map(r => (r.x, r.y, r.to.value))
      val spawns = d.spawns.filter(_.map == Sy)
      assertTrue(
        catalog.map(Sy).exists(m => m.asset == "R001" && m.title == "圣域" && m.flags.contains(MapFlag.NoRecall)),
        in == Set((371, 377), (371, 376)),
        out == List((55, 94, "H010")),
        !d.teleports.exists(_.map == Sy),
        spawns.size == 33, spawns.map(_.count).sum == 213,
        spawns.forall(_.monster.value.startsWith("圣域"))
      )
    },
    test("圣域金刚血 12000、攻击 200~300（用户定）；四档算 boss，其余不算；外观借 1.76 的怪") {
      val jg = mon("圣域金刚")
      assertTrue(
        jg.hp == 12000, jg.dc == Span(200, 300), jg.appr == mon("双头金刚").appr, jg.race == mon("双头金刚").race,
        Tier4.forall(n => Rates.bosses(MonsterName(n))),
        catalog.data.monsters.filter(_.name.value.startsWith("圣域")).count(m => Rates.bosses(m.name)) == 6,
        mon("圣域稻草人").hp == 1000, mon("圣域稻草人").appr == mon("稻草人").appr
      )
    },
    test("勋章：StdMode 30 戴勋章格；十位档次（原版两倍）、个位属性；各怪掉原版表里那个号") {
      def medalsOf(n: String) = drops(n).map(_.item.value).filter(_.startsWith("荣誉勋章"))
      val m43 = item("荣誉勋章43号")
      val m11 = item("荣誉勋章11号")
      val m35 = item("荣誉勋章35号")
      val jgMedal = drops("圣域金刚").find(_.item.value == "荣誉勋章42号").get
      val cowMedal = drops("圣域牛魔").find(_.item.value == "荣誉勋章11号").get
      assertTrue(
        ItemKind.of(m43) == ItemKind.Equip(List(EquipSlot.Medal)),
        ItemEffects.of(m43) == ItemEffects(dc = Span(2, 6)),
        ItemEffects.of(m11) == ItemEffects(ac = Span(0, 2)),
        ItemEffects.of(m35) == ItemEffects(sc = Span(2, 4)),
        catalog.data.items.count(_.name.value.startsWith("荣誉勋章")) == 20,
        medalsOf("圣域金刚") == List("荣誉勋章42号"), medalsOf("圣域魔魂") == List("荣誉勋章44号"),
        medalsOf("圣域牛魔王") == List("荣誉勋章45号"), medalsOf("圣域神龙") == List("荣誉勋章35号"),
        medalsOf("圣域骷髅") == List("荣誉勋章15号"),
        // 原版 1/500 ×20 = 4%，四档算 boss、勋章要 28 级算高级装备，保底 10%；一档 1/100 ×20 = 20%
        Rates.dropChance(Rates.rankOf(MonsterName("圣域金刚")), jgMedal, Some(item("荣誉勋章42号"))) == 0.1,
        Rates.dropChance(Rates.rankOf(MonsterName("圣域牛魔")), cowMedal, Some(m11)) == 0.2,
        catalog.data.monsters.filter(_.name.value.startsWith("圣域")).forall(m => medalsOf(m.name.value).size == 1)
      )
    },
    test("戴上勋章加属性，视野里勋章格有它；脱下还原") {
      val w = World(catalog, MapDef(MapId("T"), "T", "测试", Set.empty, 60, 60), Terrain.open(60, 60), Rng.seeded(1),
        UidSource.counter(1_000_000), Millis(0))
      var view = ViewState()
      def flush(): Unit = w.flush().messages.values.foreach(ms => view = view.applyAll(ms))
      val d = CharacterData.newbie(catalog, "道", Job.Taoist, Sex.Male, UidSource.counter(1))
      val p = w.player(w.join(d.copy(level = 30, place = Place(w.mapId, Cell(30, 30))))).get
      flush()
      val before = p.stats
      val medal  = w.newItem(ItemName("荣誉勋章45号")).get
      Inventory.add(catalog, p, medal)
      w.handle(p.id, GameCmd.Equip(medal.uid, None)); flush()
      val worn = p.stats
      val seen = view.worn.get(EquipSlot.Medal).map(_.uid)
      w.handle(p.id, GameCmd.Unequip(EquipSlot.Medal)); flush()
      assertTrue(
        worn.sc == Span(before.sc.lo + 2, before.sc.hi + 6),
        seen.contains(medal.uid),
        p.stats == before, !p.worn.contains(EquipSlot.Medal)
      )
    }
  )
