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
        d.maps.size == 265 + 16 + 10, // 1.76 数据表 + 1.80 魔龙补充表（#1313）+ 幻境（#1317）
        d.routes.size == 1301 + 173 + 19,
        d.spawns.size == 2378 + 112 + 218,
        d.guards.size == 49,
        d.merchants.size == 69 + 8,
        d.minimaps.size == 117 + 14 + 9,
        d.items.size == 264,
        d.monsters.size == 188 + 7,
        d.drops.size == 188,
        d.drops.values.map(_.size).sum == 6833,
        d.displayNames.size == 66,
        d.skills.size == 33,
        d.exp.size == 47,
        d.monsterActions.size == 38,
        d.teleports.size == 105
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
        catalog.teleport(TeleportRef(105)).isEmpty,
        catalog.merchant(MerchantRef(0)).map(_.name).contains("卫家店"),
        catalog.merchant(MerchantRef(-1)).isEmpty
      )
    },
    test("传送菜单不到 boss 层和迷宫，祖玛、牛魔到五层，赤月到抉择之地；祖玛阁的门照原版（#1315）") {
      val d         = catalog.data
      val bossMaps  = d.spawns.filter(s => mir.rules.Rates.bosses(s.monster)).map(_.map).toSet
      val wild      = d.teleports.filter(_.group != "城市")
      val listed    = wild.map(_.map.value).toSet
      // 未知暗殿、幻境一层没有路进去，只能传，留着
      val toBoss    = wild.filter(t => bossMaps(t.map)).map(_.map.value)
      val zumaMaze  = d.routes.find(r => r.from == MapId("D5061") && r.x == 66 && r.y == 16)
      assertTrue(
        toBoss == List("M001", "H001"),
        !wild.exists(t => t.name == "石墓阵" || t.name == "祖玛阁"),
        Set("D505", "D2076", "D1004", "D715").subsetOf(listed),
        Set("D5072", "D2078", "D10052", "D716").intersect(listed).isEmpty,
        zumaMaze.map(_.to).contains(MapId("D5062"))
      )
    },
    test("1.80 魔龙（#1313）：传送菜单只有魔龙城和东郊、西郊；从魔龙城走得到魔龙血域；教主是 boss") {
      val d      = catalog.data
      val molong = Set("6", "B357", "GA1", "61", "62", "611", "621", "612", "613", "622", "63", "632", "631", "64", "65", "66")
      val menu   = d.teleports.filter(t => molong(t.map.value)).map(t => (t.group, t.map.value))
      val edges  = d.routes.groupMap(_.from)(_.to)
      def reach(seen: Set[MapId]): Set[MapId] =
        val next = seen ++ seen.flatMap(edges.getOrElse(_, Nil))
        if next == seen then seen else reach(next)
      val fromCity = reach(Set(MapId("6"))).map(_.value)
      val boss     = d.spawns.filter(_.monster == MonsterName("魔龙教主")).map(_.map.value)
      assertTrue(
        menu.toSet == Set(("城市", "6"), ("魔龙东郊", "61"), ("魔龙西郊", "62")),
        fromCity == molong,
        boss == List("66"),
        mir.rules.Rates.bosses(MonsterName("魔龙教主")),
        d.monsters.filter(_.name.value.startsWith("魔龙")).forall(m => catalog.actions(m).isDefined)
      )
    },
    test("幻境（#1317）：十层用现成地图的素材，传送菜单只有一层，一层走得到十层、出口回盟重，每层有 boss") {
      val d      = catalog.data
      val layers = (1 to 10).map(i => f"H$i%03d").toList
      val edges  = d.routes.groupMap(_.from)(_.to)
      def reach(seen: Set[MapId]): Set[MapId] =
        val next = seen ++ seen.flatMap(edges.getOrElse(_, Nil))
        if next == seen then seen else reach(next)
      val fromFirst = reach(Set(MapId("H001"))).map(_.value)
      val menu      = d.teleports.filter(_.map.value.startsWith("H0")).map(t => (t.group, t.map.value))
      val bossLayer = layers.filter(h => d.spawns.exists(s => s.map.value == h && mir.rules.Rates.bosses(s.monster)))
      assertTrue(
        layers.forall(h => catalog.map(MapId(h)).exists(m => m.asset != h)),
        layers.toSet.subsetOf(fromFirst),
        fromFirst.contains("3"),
        menu == List(("幻境", "H001")),
        bossLayer == layers
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
