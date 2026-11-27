package mir.world

import mir.core.*
import mir.data.{MonsterDef, SpawnDef}
import mir.rules.{MonsterRank, Rates}

import scala.collection.mutable

/** 开图放 NPC、守卫，按刷怪表补怪，以及在某处放一只怪（召唤、复活带来的宝宝也走这里）。 */
final class Spawner(w: World, start: Millis):
  private val spawns: Vector[(SpawnDef, Int)] =
    w.catalog.data.spawns.zipWithIndex.filter(_._1.map == w.mapId).toVector
  /** 刷怪点（按 game.json 下标）→ 下一次补怪的时刻 */
  private val due = mutable.Map.from(spawns.map((_, i) => i -> start))

  /** 开图：放上本图的 NPC 和守卫。 */
  private[world] def open(): Unit =
    w.catalog.data.merchants.zipWithIndex.filter(_._1.map == w.mapId).foreach { (m, i) =>
      w.enter(Npc(w.newActorId(), MerchantRef(i), m, w.now))
    }
    w.catalog.guardsByMap.getOrElse(w.mapId, Nil).foreach { g =>
      w.catalog.monster(g.monster).flatMap(spawnMonster(_, None, g.at, 0)).foreach(w.grid.face(_, g.direction))
    }

  /** 到点的刷怪点补到满（一次最多补 40 只，大刷怪点分几批）。 */
  private[world] def run(): Unit = spawns.foreach { (s, i) =>
    if due.get(i).exists(_ <= w.now) then
      w.catalog.monster(s.monster) match
        case None => due.remove(i): Unit
        case Some(d) =>
          val alive = livingOf(i).size
          val born  = (alive until math.min(s.count, alive + 40)).flatMap(_ => refillOne(d, i, s))
          if Rates.rankOf(s.monster) == MonsterRank.Boss then born.foreach(m => w.out.chronicle(Chronicle.BossSpawned(m.defn.name, m.cell)))
          due(i) = w.now + math.max(1, Rates.respawnMinutes(s.monster, s.minutes)) * 60_000L
  }

  /** 刷怪点 i 刷出来、还活着的怪 */
  private def livingOf(i: Int): Vector[Monster] = w.eachActor.collect { case m: Monster if m.spawn.contains(i) && !m.dead => m }.toVector

  /** 刷怪点 i 下次补怪的时刻（数据表里没有那种怪的刷怪点为空） */
  private[world] def nextRefill(i: Int): Option[Millis] = due.get(i)

  /** 本图各 boss 刷怪点此刻的样子（#1277） */
  def bossSpots: Vector[BossSpot] = spawns.collect {
    case (s, i) if Rates.rankOf(s.monster) == MonsterRank.Boss =>
      BossSpot(s.monster, s.at, s.count, livingOf(i).map(m => BossAlive(m.cell, m.hp, m.maxHp)), due.get(i))
  }

  private def keyOf(s: SpawnDef, i: Int): SpawnKey = SpawnKey(i, s.monster, s.at)

  /** 停服快照（#1279）：刷怪点刷出来、还活着的野怪（宝宝、被收服的、boss 召出的小怪不算），各刷怪点下次补怪的时刻。 */
  private[world] def freeze(): (List[FrozenMonster], List[FrozenRefill]) =
    val byIndex = spawns.map((s, i) => i -> keyOf(s, i)).toMap
    val alive = w.eachActor.collect {
      case m: Monster if !m.dead && m.master.isEmpty => m.spawn.flatMap(byIndex.get).map(FrozenMonster(_, m.cell, m.dir, m.hp))
    }.flatten.toList
    (alive, due.toList.flatMap((i, at) => byIndex.get(i).map(FrozenRefill(_, at))))

  /**
   * 开图后接上快照：放回活着的野怪（原位被占就放附近）、改回补怪时刻。刷怪点按 matchSlots 认（别的图增删刷怪点不影响），认不出的刷怪点的怪丢掉、
   * 补怪时刻不改（开图就补满，同没有快照）；一个刷怪点放回的不超过数据表现在的只数。返回放回了几只。
   */
  private[world] def thaw(monsters: List[FrozenMonster], refills: List[FrozenRefill]): Int =
    val current = Spawner.matchSlots(monsters.map(_.spawn) ++ refills.map(_.spawn), spawns.map((s, i) => keyOf(s, i)))
    refills.foreach(r => current.get(r.spawn.index).foreach(due(_) = r.at))
    // 数据表把某个刷怪点的只数改少了：只放回新的只数
    val capped = monsters.flatMap(m => current.get(m.spawn.index).map(_ -> m)).groupBy(_._1).toList.flatMap { (i, ms) =>
      ms.take(spawns.find(_._2 == i).fold(0)(_._1.count))
    }
    // 原位走不到（停服时卡在地图边上的小块里，#1369）就在刷怪点范围里重新挑格
    def home(i: Int) = spawns.find(_._2 == i).map(_._1)
    capped.flatMap { (i, f) =>
      w.catalog.monster(f.spawn.monster).flatMap { d =>
        spawnMonster(d, Some(i), f.cell, 0).orElse(home(i).flatMap(s => spawnMonster(d, Some(i), s.at, s.radius)))
      }.map { m =>
        w.grid.face(m, f.dir)
        w.vitals.setMaxHp(m, m.maxHp, Some(math.max(1, f.hp)))
      }
    }.size

  /**
   * 刷怪点补一只：先在范围里找；范围里一格能站的都没有（如石墓三/四层 (309,19)，#1371）就照原版 AddBaseObject
   * 往右、换行挪着找（Spawner.originalSlide），这次没找到就等下次补怪再试。
   */
  private def refillOne(d: MonsterDef, i: Int, s: SpawnDef): Option[Monster] =
    spawnMonster(d, Some(i), s.at, s.radius).orElse {
      val rng   = w.rngOf
      val start = Cell(s.at.x - s.radius + rng.random(s.radius * 2 + 1), s.at.y - s.radius + rng.random(s.radius * 2 + 1))
      Spawner.originalSlide(start, w.terrain.width, w.terrain.height, rng).find(w.grid.canSettle).map(place(d, Some(i), _))
    }

  /** 在 at 周围 radius 格内找空格放一只怪（找不到就扩大范围找）。 */
  private[world] def spawnMonster(d: MonsterDef, spawn: Option[Int], at: Cell, radius: Int): Option[Monster] =
    val rng   = w.rngOf
    val tries = Iterator.fill(20)(Cell(at.x + rng.random(radius * 2 + 1) - radius, at.y + rng.random(radius * 2 + 1) - radius))
    tries.find(w.grid.canSettle).orElse(w.freeCellNear(at, math.max(3, radius))).map(place(d, spawn, _))

  private def place(d: MonsterDef, spawn: Option[Int], c: Cell): Monster =
    val rng = w.rngOf
    val m   = Monster(w.newActorId(), d, Behavior.of(d), spawn, c, Dir.fromCode(rng.random(8)), w.now)
    m.nextThink = w.now + rng.random(2000)
    w.enter(m)
    MonsterAi.init(w, m)
    m

object Spawner:
  /**
   * 原版 M2Server TUserEngine.AddBaseObject 的落脚点不能走时依次试的 31 格（含起点）：每次往右挪 n20 格（地图宽不到 50 挪 2，否则 3），
   * 挪到离右边 n24 格（地图高 <30 取 2、<250 取 20、否则 50）就跳到左半边随机一列（再加 n24），同时往下挪 n20 行
   * （到离底边 n24 行就跳到上半边随机一行）。原版这 31 格都不能走就这次不刷。
   */
  def originalSlide(start: Cell, width: Int, height: Int, rng: mir.rules.Rng): Iterator[Cell] =
    val step   = if width < 50 then 2 else 3
    val margin = if height >= 250 then 50 else if height < 30 then 2 else 20
    Iterator.iterate(start) { c =>
      if width - margin - 1 > c.x then Cell(c.x + step, c.y)
      else
        val x = rng.random(width / 2) + margin
        Cell(x, if height - margin - 1 > c.y then c.y + step else rng.random(height / 2) + margin)
    }.take(31)

  /**
   * 快照里的刷怪点（停服时 game.json 的下标）→ 现在的下标（#1339）。下标是全表的，别的图加了刷怪点就整体挪动，
   * 所以不按下标认，按「刷哪种怪、在哪、本图同种同位置的第几个（按下标顺序）」认；只有在本图同一位置插进同种怪的刷怪点时才会认错位。
   * frozen、present 都是同一张图的刷怪点；对不上的不在结果里。
   */
  def matchSlots(frozen: Iterable[SpawnKey], present: Iterable[SpawnKey]): Map[Int, Int] =
    def slots(keys: Iterable[SpawnKey]): Map[(MonsterName, Cell, Int), Int] =
      keys.toVector.distinctBy(_.index).groupBy(k => (k.monster, k.at)).toVector.flatMap { case ((m, at), ks) =>
        ks.sortBy(_.index).zipWithIndex.map((k, n) => (m, at, n) -> k.index)
      }.toMap
    val now = slots(present)
    slots(frozen).toVector.flatMap((slot, old) => now.get(slot).map(old -> _)).toMap
