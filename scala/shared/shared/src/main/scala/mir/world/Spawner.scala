package mir.world

import mir.core.*
import mir.data.{MonsterDef, SpawnDef}

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
          val alive = w.eachActor.count {
            case m: Monster => m.spawn.contains(i) && !m.dead
            case _          => false
          }
          (alive until math.min(s.count, alive + 40)).foreach(_ => spawnMonster(d, Some(i), s.at, s.radius))
          due(i) = w.now + math.max(1, s.minutes) * 60_000L
  }

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
