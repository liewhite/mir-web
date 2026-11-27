package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.rules.{MonsterRank, Rates, Rng}
import mir.terrain.{Reachable, Terrain}
import mir.testkit.TestFiles
import zio.test.*

/**
 * 地图边上走不进去的小块（#1369）：原版地图文件在可见区域外有零星可走格、跟主区域不连通（如圣域 x=0~1），
 * 刷怪、随机传送、停服快照放回都不能把怪或人放进去。
 */
object ReachableSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  /** 有 boss 刷怪点的一张图 */
  private lazy val bossMap: MapDef =
    catalog.data.spawns.find(s => Rates.rankOf(s.monster) == MonsterRank.Boss && catalog.monster(s.monster).isDefined)
      .flatMap(s => catalog.map(s.map)).getOrElse(throw new AssertionError("数据表里没有 boss 刷怪点"))

  /** x = wallX 一整列是墙，左边是跟主区域不通的小块。 */
  private def walled(width: Int, height: Int, wallX: Int): Terrain =
    val flags = Array.tabulate[Byte](width * height)(i => if i % width == wallX then 1 else 0)
    Terrain.fromFlags(width, height, flags)

  private def inPocket(c: Cell): Boolean = c.x < 2

  private def run(w: World, ms: Int): Unit = (0 until ms by 50).foreach { _ => w.tick(w.now + 50); w.flush(): Unit }

  def spec = suite("走不进去的小块（#1369）")(
    test("分块：最大块和门所在（或紧挨门）的块算走得到，别的小块不算") {
      // 30×10：x=3 和 x=20 两列墙，分成 x 0~2（30 格）、4~19（160 格）、21~29（90 格）三块
      val flags = Array.tabulate[Byte](300)(i => if i % 30 == 3 || i % 30 == 20 then 1 else 0)
      val t     = Terrain.fromFlags(30, 10, flags)
      val none  = Reachable.of(t, Nil)
      val door  = Reachable.of(t, List(Cell(20, 5))) // 门在墙格上，看它旁边一圈：两边的块都算
      assertTrue(
        none.size == 160, none(Cell(10, 5)), !none(Cell(1, 5)), !none(Cell(25, 5)),
        door.size == 250, door(Cell(25, 5)), !door(Cell(1, 5)), !door(Cell(3, 5)),
        Reachable.all(t).size == 280
      )
    },
    test("刷怪、随机传送都不落进小块；停服时卡在小块里的 boss 放回时挪出来") {
      val routesHere = catalog.data.routes.filter(r => r.from == bossMap.id).map(_.at) ++
        catalog.data.routes.filter(_.to == bossMap.id).map(_.target) ++
        catalog.data.teleports.filter(_.map == bossMap.id).map(_.at)
      val terrain = walled(bossMap.width, bossMap.height, 2)
      val w1 = World(catalog, bossMap.copy(flags = Set.empty), terrain, // 去掉「不能随机传送」等标记，好测随机传送
                     Rng.seeded(1), UidSource.counter(1_000_000), Millis(0))
      val d   = CharacterData.newbie(catalog, "甲", Job.Warrior, Sex.Male, UidSource.counter(5_000))
      val pid = w1.join(d.copy(place = Place(w1.mapId, Cell(bossMap.width / 2, bossMap.height / 2))))
      run(w1, 3000)
      val spawned = w1.monsters.filter(_.master.isEmpty).map(_.cell)
      given Rng   = Rng.seeded(3)
      val spots   = (1 to 2000).flatMap(_ => w1.randomSpot.toOption)
      // 把一只 boss 挪进小块，拍快照、接到新开的图上
      val boss = w1.monsters.find(m => Rates.rankOf(m.defn.name) == MonsterRank.Boss && m.spawn.isDefined).get
      w1.grid.teleportActor(boss, Cell(0, bossMap.height / 2))
      w1.leave(pid): Unit
      val frozen = w1.freeze()
      val w2 = World(catalog, bossMap, terrain, Rng.seeded(2), UidSource.counter(2_000_000), w1.now)
      val restored = w2.thaw(frozen)
      val boss2    = w2.monsters.filter(m => m.spawn == boss.spawn)
      assertTrue(
        !routesHere.exists(inPocket), // 这张图的门、落点都不在小块里，测试才成立
        spawned.nonEmpty, !spawned.exists(inPocket),
        spots.size == 2000, !spots.exists(inPocket),
        restored._1 == frozen.monsters.size,
        boss2.nonEmpty, !boss2.exists(m => inPocket(m.cell))
      )
    }
  )
