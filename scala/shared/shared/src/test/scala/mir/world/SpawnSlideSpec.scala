package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.rules.Rng
import mir.terrain.Terrain
import mir.testkit.TestFiles
import zio.test.*

/**
 * 刷怪点范围里一格能站的都没有（石墓三/四层 (309,19)，#1371）：照原版 AddBaseObject 往右、换行挪着找落脚点，
 * 怪刷到别处，而不是一直少一批。
 */
object SpawnSlideSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private def run(w: World, ms: Int): Unit = (0 until ms by 50).foreach { _ => w.tick(w.now + 50); w.flush(): Unit }

  def spec = suite("刷怪点范围里没有能走的地（#1371）")(
    test("原版挪格顺序：每次往右 3 格，到离右边 50 格就跳到左半边随机一列、往下 3 行，共 31 格") {
      val cells = Spawner.originalSlide(Cell(339, 19), 400, 400, Rng.seeded(1)).toVector
      assertTrue(
        cells.size == 31,
        cells.take(5) == Vector(Cell(339, 19), Cell(342, 19), Cell(345, 19), Cell(348, 19), Cell(351, 19)),
        cells(5).y == 22, cells(5).x >= 50, cells(5).x < 250,
        cells(6) == Cell(cells(5).x + 3, 22)
      )
    },
    test("石墓三层：(309,19) 一圈全是墙时，红野猪、黑野猪、黑色恶蛆照样刷出来，落在能走的地方") {
      val map = catalog.map(MapId("D713")).getOrElse(throw new AssertionError("没有 D713"))
      // 只有 y 20~40、x 50~250 一条能走；(309,19) 周围 30 格全是墙
      val flags = Array.tabulate[Byte](map.width * map.height) { i =>
        val (x, y) = (i % map.width, i / map.width)
        if y >= 20 && y <= 40 && x >= 50 && x <= 250 then 0 else 1
      }
      val terrain = Terrain.fromFlags(map.width, map.height, flags)
      val w       = World(catalog, map, terrain, Rng.seeded(7), UidSource.counter(1_000_000), Millis(0))
      val blocked = catalog.data.spawns.zipWithIndex.collect { case (s, i) if s.map == map.id && s.at == Cell(309, 19) => i }.toSet
      val d = CharacterData.newbie(catalog, "甲", Job.Warrior, Sex.Male, UidSource.counter(5_000))
      w.join(d.copy(place = Place(w.mapId, Cell(150, 30)))): Unit // 图上有人才刷怪
      run(w, 3000)
      val born = w.monsters.filter(_.spawn.exists(blocked))
      assertTrue(
        blocked.size == 3,
        born.nonEmpty,
        born.forall(m => terrain.walkable(m.cell))
      )
    }
  )
