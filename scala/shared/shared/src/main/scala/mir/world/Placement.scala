package mir.world

import mir.core.*
import mir.model.*
import mir.protocol.*
import mir.data.MapFlag
import mir.rules.{Pace, Rng}
import scala.collection.mutable

/**
 * 占位与移动：谁站在哪一格，以及对象的格子、朝向、动作——这些只在这里改，
 * 改格子时同时维护占位表，旁人看到的变化由 Visibility 在 flush 时比出来。
 * 一格一般只站一个活的；例外是人物能穿怪（#1240）：玩家可以走进、停在怪（含宝宝）站的格子上，
 * 于是一格可能叠着几个，占位表按先来后到记下全部。怪、NPC 和被推的对象仍只进空格。
 */
final class Placement(w: World):
  /** 格子下标 → 站在上面的对象（先来的在前）；只记有人的格子。 */
  private val occupancy = mutable.HashMap.empty[Int, List[ActorId]]
  private var stepFoot  = 0

  private def idx(c: Cell): Int = c.y * w.terrain.width + c.x
  private val RandomSpotTries = 300

  /** 站在这格上的全部对象（先来的在前；玩家穿怪时会叠着几个）。范围类伤害用它。 */
  def occupants(c: Cell): List[Actor] =
    if !w.terrain.inside(c) then Nil
    else occupancy.getOrElse(idx(c), Nil).flatMap(w.actor)

  /** 站在这格上的（叠着几个时是先来的那个）。 */
  def occupant(c: Cell): Option[Actor] = occupants(c).headOption

  private[world] def occupy(a: Actor): Unit =
    val i = idx(a.cell)
    occupancy(i) = occupancy.getOrElse(i, Nil).filterNot(_ == a.id) :+ a.id

  private[world] def vacate(a: Actor): Unit =
    val i = idx(a.cell)
    occupancy.get(i).map(_.filterNot(_ == a.id)).foreach { rest =>
      if rest.isEmpty then occupancy.remove(i) else occupancy(i) = rest
    }

  /** 地形可走且没人站。 */
  def canEnter(c: Cell): Boolean = w.terrain.walkable(c) && occupants(c).isEmpty

  /**
   * a 自己走路时能不能走进这格。玩家穿怪（#1240）：站着的只有怪（含宝宝、守卫）就能走进去、停在上面；
   * 别的玩家、NPC 照旧挡路。怪和 NPC 只进空格。
   */
  def walkableFor(a: Actor)(c: Cell): Boolean = a match
    case _: Player => w.terrain.walkable(c) && occupants(c).forall(isMonster)
    case _         => canEnter(c)

  private def isMonster(a: Actor): Boolean = a match
    case _: Monster => true
    case _          => false

  /** 能把人或怪放上去：没人站、且从主区域走得到（#1369：不放进地图边上走不进去的小块）。 */
  def canSettle(c: Cell): Boolean = canEnter(c) && w.reachable(c)

  /** 本图随机一格能站的（MapRandomMove 的取格）；地图带 NORANDOMMOVE 的不许随机传送。 */
  def randomSpot(using rng: Rng): Either[String, Cell] =
    if w.mapDef.has(MapFlag.NoRandomMove) then Left("这张地图不能随机传送")
    else
      Iterator.fill(RandomSpotTries)(Cell(rng.random(w.terrain.width), rng.random(w.terrain.height)))
        .find(canSettle).toRight("没找到落脚的地方")

  /** p 周围由近到远第一个能放的格（canSettle）。 */
  def freeCellNear(p: Cell, radius: Int = 6): Option[Cell] =
    (0 to radius).iterator.flatMap { r =>
      for
        dy <- (-r to r).iterator
        dx <- (-r to r).iterator
        if math.max(math.abs(dx), math.abs(dy)) == r
        c = Cell(p.x + dx, p.y + dy)
        if canSettle(c)
      yield c
    }.nextOption()

  private[world] def setAction(a: Actor, kind: ActKind, duration: Int, from: Option[Cell] = None, hit: Option[HitStyle] = None, spellEffect: Option[Int] = None): Unit =
    a.action = ActionView(kind, w.now, duration, from, hit, spellEffect)

  private[world] def face(a: Actor, d: Dir): Unit = a.dir = d

  /** 走/跑一步（跑两格）。成功返回 true。 */
  private[world] def move(a: Actor, dir: Dir, run: Boolean): Boolean =
    val steps = if run then 2 else 1
    val path  = (1 to steps).map(a.cell.step(dir, _))
    if a.dead || !path.forall(walkableFor(a)) then false
    else
      val from = a.cell
      relocate(a, path.last)
      a.dir = dir
      val dur = a match
        case _: Player  => if run then Pace.RunMs else Pace.WalkMs
        case m: Monster => monsterMoveMs(m)
        case _: Npc     => Pace.WalkMs
      setAction(a, if run then ActKind.Run else ActKind.Walk, dur, from = Some(from))
      a.busyFor(w.now, dur)
      a match
        case p: Player => afterPlayerMove(p, run)
        case _         => ()
      true

  private def relocate(a: Actor, to: Cell): Unit =
    vacate(a)
    a.cell = to
    occupy(a)

  private def monsterMoveMs(m: Monster): Int =
    val anim = w.actTable(m).fold(600)(t => t.walk.frames * t.walk.frameTime)
    math.max(300, math.min(anim, m.walkInterval))

  private def afterPlayerMove(p: Player, run: Boolean): Unit =
    val cave = w.mapDef.id.value.startsWith("D")
    val pair = (run, cave) match
      case (true, true)   => Sounds.runCave
      case (true, false)  => Sounds.runGround
      case (false, true)  => Sounds.walkCave
      case (false, false) => Sounds.walkGround
    w.out.selfSound(p, if stepFoot % 2 == 0 then pair._1 else pair._2)
    stepFoot += 1
    arrived(p)

  /** 玩家停在新格上：踩到传送点就传送。 */
  private[world] def arrived(p: Player): Unit =
    w.catalog.routesByMap.getOrElse(w.mapDef.id, Nil).find(_.at == p.cell).foreach(r => w.roster.transfer(p, Place(r.to, r.target)))

  /** 挨推、冲撞时挪一格（不改动作和朝向，由调用方定）。进不去返回 false。 */
  private[world] def shove(a: Actor, dir: Dir): Boolean =
    val to = a.cell.step(dir)
    if a.dead || !canEnter(to) then false
    else
      relocate(a, to)
      true

  /**
   * 被推着倒退最多 n 格（原版 CharPushed）：脸朝推的人、倒着走，怪被推后多歇一会儿。返回实际退了几格。
   * 同一刻接着推（野蛮冲撞每冲一格推一次）时动画从最初的格算，一口气退过去。
   */
  private[world] def pushBack(a: Actor, dir: Dir, n: Int): Int =
    val origin = a.action match
      case ActionView(ActKind.Backstep, at, _, Some(from), _, _) if at == w.now => from
      case _                                                                   => a.cell
    val moved = (1 to n).takeWhile(_ => shove(a, dir)).size
    if moved > 0 then
      val dur = origin.chebyshev(a.cell) * Pace.BackstepMs
      a.dir = dir.opposite
      setAction(a, ActKind.Backstep, dur, from = Some(origin))
      a.busyUntil = w.now + dur + (a match
        case _: Monster => Pace.PushedPauseMs
        case _          => 0)
    moved

  /** 瞬移到某格（沃玛教主、同图传送）。 */
  private[world] def teleportActor(a: Actor, to: Cell): Unit =
    relocate(a, to)
    setAction(a, ActKind.Stand, 0)
