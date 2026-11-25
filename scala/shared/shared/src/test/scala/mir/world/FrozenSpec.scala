package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.rules.{MonsterRank, PetBody, Rates, Rng, Summoning, Ward}
import mir.terrain.Terrain
import mir.testkit.TestFiles
import mir.world.FrozenCodecs.given
import zio.json.*
import zio.test.*

/** 停服快照（#1279）：一张图拍下来、转成 JSON 再接到新开的图上，野怪、boss 血量、地上的东西、刷怪计时都在，不多刷也不复制。 */
object FrozenSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  /** 有 boss 刷怪点的一张图 */
  private lazy val bossMap: MapDef =
    catalog.data.spawns.find(s => Rates.rankOf(s.monster) == MonsterRank.Boss && catalog.monster(s.monster).isDefined)
      .flatMap(s => catalog.map(s.map)).getOrElse(throw new AssertionError("数据表里没有 boss 刷怪点"))

  private def world(start: Millis, seed: Long): World =
    World(catalog, bossMap, Terrain.open(bossMap.width, bossMap.height), Rng.seeded(seed), UidSource.counter(1_000_000), start)

  private def run(w: World, ms: Int): Unit = (0 until ms by 50).foreach { _ => w.tick(w.now + 50); w.flush(): Unit }

  /** 各刷怪点活着的野怪数 */
  private def census(w: World): Map[Int, Int] =
    w.monsters.filter(m => !m.dead && m.master.isEmpty).flatMap(_.spawn).groupBy(identity).view.mapValues(_.size).toMap

  def spec = suite("停服快照")(
    test("一张图：活着的野怪（含半血 boss）、地上的东西和掉落保护、刷怪计时原样接上；接上后不立刻补怪") {
      val w1 = world(Millis(0), 1)
      val d  = CharacterData.newbie(catalog, "甲", Job.Warrior, Sex.Male, UidSource.counter(5_000))
      val pid = w1.join(d.copy(place = Place(w1.mapId, Cell(bossMap.width / 2, bossMap.height / 2))))
      run(w1, 3000) // 有人才推进：刷怪点补满
      val boss = w1.monsters.find(m => Rates.rankOf(m.defn.name) == MonsterRank.Boss && m.spawn.isDefined).get
      w1.vitals.setMaxHp(boss, boss.maxHp, Some(boss.maxHp / 2))
      val sword = w1.newItem(ItemName("木剑")).get
      w1.dropAt(Cell(10, 10), Loot.Item(sword), Some(Ward("甲", w1.now + 60_000)))
      w1.dropAt(Cell(12, 10), Loot.Coins(Gold(123)))
      w1.leave(pid): Unit
      val before  = census(w1)
      val bossAt  = (boss.cell, boss.hp, boss.spawn)
      val refill  = boss.spawn.flatMap(w1.spawner.nextRefill)
      val frozen  = w1.freeze()
      val decoded = frozen.toJson.fromJson[FrozenMap]

      val w2 = world(w1.now + 5_000, 2)
      val restored = decoded.map(w2.thaw)
      val boss2    = w2.monsters.find(m => m.spawn == boss.spawn && m.cell == boss.cell)
      val items    = w2.groundItems.toVector.map(g => (g.cell, g.loot, g.ward)).toSet
      // 有人进图推进几秒：刷怪点不会因为「开图」再补一遍
      w2.join(d.copy(place = Place(w2.mapId, Cell(bossMap.width / 2, bossMap.height / 2)))): Unit
      run(w2, 3000)
      assertTrue(
        decoded == Right(frozen),
        frozen.monsters.size == before.values.sum,
        restored == Right((before.values.sum, 2)),
        boss2.map(m => (m.cell, m.hp, m.spawn)).contains(bossAt),
        boss.spawn.flatMap(w2.spawner.nextRefill) == refill,
        items == Set(
          (Cell(10, 10), Loot.Item(sword), Some(Ward("甲", Millis(3000 + 60_000)))),
          (Cell(12, 10), Loot.Coins(Gold(123)), None)
        ),
        census(w2) == before
      )
    },
    test("认不出的刷怪点（数据表改过）的怪丢掉、计时不改；数据表里没有的物品丢掉") {
      val w  = world(Millis(0), 1)
      val bogus = SpawnKey(0, MonsterName("没有这种怪"), Cell(1, 1))
      val f = FrozenMap(
        List(FrozenMonster(bogus, Cell(5, 5), Dir.Down, 10)),
        List(FrozenGround(Cell(3, 3), Loot.Item(ItemInstance(ItemUid(9), ItemName("没有这件"), 1, 1)), Millis(0), None)),
        List(FrozenRefill(bogus, Millis(999_999)))
      )
      assertTrue(w.thaw(f) == (0, 0), w.spawner.nextRefill(0).forall(_ != Millis(999_999)))
    },
    test("角色带的（宝宝、状态、药、PK 计时）转成 JSON 再读回来一样") {
      val c = Carried(
        pets = List(PetData(MonsterName("神兽"), 321, PetBody(500, Span(10, 20), Span(3, 4), Span(5, 6)), 2, Some(Summoning(3, 40)))),
        statuses = List(Timed(Status.Shielded(3), Millis(77)), Timed(Status.Poisoned(PoisonKind.Green, 5, Millis(10), None), Millis(88)),
          Timed(Status.Brawler, Millis(99))),
        potions = List(PotionCourse(5, 3, 4, Millis(55))),
        pkDecayAt = Some(Millis(66))
      )
      assertTrue(c.toJson.fromJson[Carried] == Right(c))
    }
  )
