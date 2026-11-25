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
          val born  = (alive until math.min(s.count, alive + 40)).flatMap(_ => spawnMonster(d, Some(i), s.at, s.radius))
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
   * 开图后接上快照：放回活着的野怪（原位被占就放附近）、改回补怪时刻。认不出的刷怪点（数据表改过）的怪丢掉、
   * 补怪时刻不改（开图就补满，同没有快照）；一个刷怪点放回的不超过数据表现在的只数。返回放回了几只。
   */
  private[world] def thaw(monsters: List[FrozenMonster], refills: List[FrozenRefill]): Int =
    val known = spawns.map((s, i) => keyOf(s, i)).toSet
    refills.filter(r => known(r.spawn)).foreach(r => due(r.spawn.index) = r.at)
    // 数据表把某个刷怪点的只数改少了：只放回新的只数
    val capped = monsters.filter(m => known(m.spawn)).groupBy(_.spawn.index).toList.flatMap((i, ms) => ms.take(spawns.find(_._2 == i).fold(0)(_._1.count)))
    capped.flatMap { f =>
      w.catalog.monster(f.spawn.monster).flatMap(spawnMonster(_, Some(f.spawn.index), f.cell, 0)).map { m =>
        w.grid.face(m, f.dir)
        w.vitals.setMaxHp(m, m.maxHp, Some(math.max(1, f.hp)))
      }
    }.size

  /** 在 at 周围 radius 格内找空格放一只怪（找不到就扩大范围找）。 */
  private[world] def spawnMonster(d: MonsterDef, spawn: Option[Int], at: Cell, radius: Int): Option[Monster] =
    val rng   = w.rngOf
    val tries = Iterator.fill(20)(Cell(at.x + rng.random(radius * 2 + 1) - radius, at.y + rng.random(radius * 2 + 1) - radius))
    tries.find(w.canEnter).orElse(w.freeCellNear(at, math.max(3, radius))).map { c =>
      val m = Monster(w.newActorId(), d, Behavior.of(d), spawn, c, Dir.fromCode(rng.random(8)), w.now)
      m.nextThink = w.now + rng.random(2000)
      w.enter(m)
      MonsterAi.init(w, m)
      m
    }
