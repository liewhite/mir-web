package mir.data

import mir.core.*
import mir.testkit.TestFiles
import zio.json.*
import zio.test.*

object GameDataSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(s"game.json 解码失败：$e"), identity)

  def spec = suite("游戏数据 game.json")(
    test("完整解码，条数与 TS 版读到的一致") {
      val d = catalog.data
      assertTrue(
        d.maps.size == 265,
        d.routes.size == 1301,
        d.spawns.size == 2378,
        d.guards.size == 49,
        d.merchants.size == 69,
        d.minimaps.size == 117,
        d.items.size == 264,
        d.monsters.size == 188,
        d.drops.size == 188,
        d.drops.values.map(_.size).sum == 6833,
        d.displayNames.size == 66,
        d.skills.size == 33,
        d.exp.size == 47,
        d.monsterActions.size == 38,
        d.teleports.size == 123
      )
    },
    test("名字、编号不重复，索引不丢条目") {
      assertTrue(
        catalog.itemsByName.size == catalog.data.items.size,
        catalog.itemsById.size == catalog.data.items.size,
        catalog.monstersByName.size == catalog.data.monsters.size,
        catalog.skillsById.size == catalog.data.skills.size,
        catalog.mapsById.size == catalog.data.maps.size
      )
    },
    test("引用都能查到：刷怪、守卫的怪物，商品，传送和通道的地图，怪物动作表") {
      val d            = catalog.data
      val badSpawns    = d.spawns.filter(s => catalog.monster(s.monster).isEmpty || catalog.map(s.map).isEmpty)
      val badGuards    = d.guards.filter(g => catalog.monster(g.monster).isEmpty)
      val badGoods     = d.merchants.flatMap(_.goods).filter(g => catalog.item(g.name).isEmpty)
      val badTeleports = d.teleports.filter(t => catalog.map(t.map).isEmpty)
      val badRoutes    = d.routes.filter(r => catalog.map(r.from).isEmpty || catalog.map(r.to).isEmpty)
      val badActs      = d.monsters.filter(m => catalog.actions(m).isEmpty)
      val badDrops     = d.drops.values.flatten.filter(r => !r.isGold && catalog.item(r.item).isEmpty)
      assertTrue(
        badSpawns.isEmpty,
        badGuards.isEmpty,
        badGoods.isEmpty,
        badTeleports.isEmpty,
        badRoutes.isEmpty,
        badActs.isEmpty,
        badDrops.isEmpty
      )
    },
    test("具体条目的字段对上原表") {
      val chicken = catalog.monster(MonsterName("鸡")).get
      val potion  = catalog.item(ItemName("金创药(小量)")).get
      val bichi   = catalog.map(MapId("0")).get
      val fire    = catalog.skill(SkillId(1)).get
      val gate    = catalog.data.routes.head
      assertTrue(
        chicken.hp == 5,
        chicken.act.contains("MA11"),
        catalog.actions(chicken).isDefined,
        potion.ac == Span(20, 0),
        potion.price == 80,
        bichi.title == "比奇省",
        bichi.width == 700,
        bichi.has(MapFlag.Day),
        fire.name == "火球术",
        fire.job == mir.model.Job.Wizard,
        fire.needLevel == SkillTiers(7, 11, 16),
        gate == RouteDef(MapId("0"), 346, 186, MapId("0122"), 11, 41),
        catalog.data.guards.head.direction == Dir.UpLeft,
        catalog.expToNext(1).contains(Exp(100)),
        catalog.expToNext(47).isDefined,
        catalog.expToNext(48).isEmpty,
        catalog.displayName(MonsterName("僵尸10")) == MonsterName("僵尸1"),
        catalog.teleport(TeleportRef(0)).map(_.name).contains("比奇省"),
        catalog.teleport(TeleportRef(123)).isEmpty,
        catalog.merchant(MerchantRef(0)).map(_.name).contains("卫家店"),
        catalog.merchant(MerchantRef(-1)).isEmpty
      )
    },
    test("地图标记：断线重连带去处，未知标记报错") {
      val reconnect = catalog.data.maps.flatMap(_.flags).collect { case MapFlag.NoReconnect(b) => b }
      assertTrue(
        reconnect.contains("D5071"),
        MapFlag.parse("WHAT").isLeft,
        "\"FIGHT\\tNORECALL  NORECONNECT(3)\"".fromJson[Set[MapFlag]](using MapFlag.setCodec.decoder) ==
          Right(Set(MapFlag.Fight, MapFlag.NoRecall, MapFlag.NoReconnect("3")))
      )
    },
    test("编码再解码不变") {
      val back = catalog.data.toJson.fromJson[GameData]
      assertTrue(back == Right(catalog.data))
    }
  )
