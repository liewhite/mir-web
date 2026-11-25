package mir.world

import mir.core.*
import mir.data.{Catalog, MapDef, MonsterActionTable}
import mir.model.*
import mir.protocol.*
import mir.rules.{Charges, Rng, Ward}
import mir.terrain.Terrain

import scala.collection.mutable

/** 世界处理完一批指令、推进一次之后要发出去的东西。 */
final case class Outbox(
    /** 每个玩家（按本世界里的对象编号）要收的消息，按顺序 */
    messages: Map[ActorId, Vector[ServerMsg]],
    /** 离开本图去别的图的玩家：服务端把他交给目的地图的世界 */
    departures: Vector[Departure],
    /** 要立刻存档的玩家（升级、交易成交；交易双方一起存） */
    checkpoints: Set[ActorId] = Set.empty,
    /** 要写进服务端日志的事（boss 刷出、死亡，#1277） */
    chronicle: Vector[Chronicle] = Vector.empty
)

/** 一个玩家换图：存档（位置已是目的地）、跟着走的不进存档的东西。 */
final case class Departure(player: ActorId, data: CharacterData, carried: Carried):
  def to: Place = data.place

/**
 * 换图时跟着人走、但不进存档的东西：宝宝、身上的限时状态（含灰名）、还没回完的药、下一次降 PK 值的时刻
 * （时刻都是服务端统一的时钟；没有就从进图时重新计）。
 */
final case class Carried(
    pets: List[PetData] = Nil,
    statuses: List[Timed] = Nil,
    potions: List[PotionCourse] = Nil,
    pkDecayAt: Option[Millis] = None
)

/**
 * 组队（#1220）在世界这边的样子：队伍本身跨地图、归服务端（mir.social.Social），世界只问「这个人和谁一队」来分打怪经验、判敌我（队友互不伤害，Hostility.allied）。
 */
trait Company:
  /** 和这个角色同队的全部人名（含他自己）；没组队为空 */
  def mates(name: String): Seq[String]

object Company:
  /** 没有组队这回事（测试、本机单人） */
  val Alone: Company = _ => Nil

object World:
  /** 视野：玩家能看到身边多少格以内（切比雪夫距离） */
  val ViewRange = 14
  /** 指令到早了也算数的余量（网络抖动），毫秒 */
  val Slack = 200
  val CorpseMs    = 10_000L
  val ItemKeepMs  = 10 * 60_000L

/**
 * 一张地图的世界：地图上的玩家、怪物、NPC、地上的东西，时间推进和玩家指令。
 *
 * - 单线程使用：服务端每张图一个，自己排队调用 handle / tick / flush。内部状态可变，对外只有「指令进、消息出」。
 * - 随机数和时钟从外面传入（测试可固定）；没人的图 tick 什么也不做。
 * - 这里只放对象表、时钟、定时器和每次推进的顺序；各管一摊的部分在各自的类里，只对 world 包开放：
 *   Mailbox（要发的消息）、Trades（玩家交易）、Placement（占位与移动：格子、朝向、动作只由它改）、Vitals（血蓝与生死：血量蓝量只由它改）、
 *   Conditions（身上状态、地上法术）、Hostility（敌我）、Justice（PK：灰名、PK 值、红名掉东西）、Roster（进出图）、Spawner（开图放 NPC 守卫、刷怪）、
 *   Visibility（按视野算出每个玩家要收的消息）。
 */
final class World(
    val catalog: Catalog,
    val mapDef: MapDef,
    val terrain: Terrain,
    rng: Rng,
    uids: UidSource,
    start: Millis,
    private[world] val company: Company = Company.Alone
):
  import World.*
  private[world] given Rng = rng

  private var clock: Millis = start
  def now: Millis           = clock
  def mapId: MapId          = mapDef.id

  private val actors     = mutable.LinkedHashMap.empty[ActorId, Actor]
  private val ground     = mutable.LinkedHashMap.empty[GroundId, GroundItem]
  private var nextActor  = 1
  private var nextGround = 1

  // ---------------- 各管一摊的部分（world 包里的规则经 w.vitals.strike 这样点名调用） ----------------

  private[world] val out: Mailbox               = Mailbox(this)
  private[world] val grid: Placement            = Placement(this)
  private[world] val vitals: Vitals             = Vitals(this)
  private[world] val conditions: Conditions     = Conditions(this)
  private[world] val hostility: Hostility       = Hostility(this)
  private[world] val justice: Justice           = Justice(this)
  private[world] val roster: Roster             = Roster(this)
  private[world] val spawner: Spawner           = Spawner(this, start)
  private[world] val visibility: Visibility     = Visibility(this)
  private[world] val trades: Trades             = Trades(this)

  // 对外（服务端、测试）开放的查询和进出图
  export grid.{occupant, occupants, canEnter, walkableFor, freeCellNear, randomSpot}
  export conditions.activeFields
  export hostility.{allied, canAttack, harms, isEnemy}
  export roster.{join, leave, snapshot}
  export spawner.bossSpots
  export visibility.{viewOf, visibleTo, fieldsVisibleTo}

  // ---------------- 查询 ----------------

  def players: Iterable[Player] = actors.values.collect { case p: Player => p }
  def hasPlayers: Boolean       = actors.valuesIterator.exists(_.isInstanceOf[Player])
  def actor(id: ActorId): Option[Actor] = actors.get(id)
  def player(id: ActorId): Option[Player] = actors.get(id).collect { case p: Player => p }
  def groundItems: Iterable[GroundItem] = ground.values
  def groundItem(id: GroundId): Option[GroundItem] = ground.get(id)
  private[world] def rngOf: Rng = rng
  /** 此刻全部对象的快照（遍历时可以增删） */
  private[world] def actorsNow: Vector[Actor] = actors.values.toVector
  /** 全部对象（不拷贝；遍历时不能增删） */
  private[world] def eachActor: Iterator[Actor] = actors.valuesIterator
  private[world] def monsters: Vector[Monster] = actors.values.collect { case m: Monster => m }.toVector
  private[world] def actTable(m: Monster): Option[MonsterActionTable] = catalog.actions(m.defn)

  // ---------------- 对象进出本图 ----------------

  private[world] def newActorId(): ActorId =
    val id = ActorId(nextActor)
    nextActor += 1
    id

  /** 对象放上本图（占住它的格子）。 */
  private[world] def enter(a: Actor): Unit =
    actors(a.id) = a
    grid.occupy(a)

  /** 对象离开本图：让出格子，视野里不再有它。 */
  private[world] def remove(a: Actor): Unit =
    grid.vacate(a)
    actors.remove(a.id)
    visibility.forget(a.id)

  /** a 还在这张图上。对象编号在一张图里不重复使用，所以「这个编号上还是它」就是它还在。 */
  private[world] def present(a: Actor): Boolean = actors.get(a.id).exists(_ eq a)

  // ---------------- 定时器 ----------------

  private final case class Timer(at: Millis, seq: Long, run: () => Unit)
  private val timers   = mutable.ArrayBuffer.empty[Timer]
  private var timerSeq = 0L

  /** 过 ms 毫秒后对 a 做 f（飞行技能命中、钻回地下等）：到点时 a 已不在这张图上（下线、换图、尸体清掉）或已死就不做。 */
  private[world] def afterOn[A <: Actor](a: A, ms: Long)(f: A => Unit): Unit =
    timerSeq += 1
    timers += Timer(clock + ms, timerSeq, () => if present(a) && !a.dead then f(a))

  // ---------------- 时间推进 ----------------

  /** 推进到 to（不会倒退）。没有玩家的图什么也不做（时钟照走）。 */
  def tick(to: Millis): Unit =
    if to > clock then clock = to
    if hasPlayers then
      actorsNow.foreach(conditions.expire)
      hostility.refresh()
      val due = timers.filter(_.at <= clock).sortBy(t => (t.at.value, t.seq))
      timers.filterInPlace(_.at > clock)
      due.foreach(_.run())
      players.toVector.foreach { p =>
        vitals.drinkPotions(p)
        justice.decay(p)
      }
      spawner.run()
      actorsNow.foreach { a =>
        if actors.contains(a.id) then
          a match
            case m: Monster => MonsterAi.think(this, m)
            case _          => ()
          vitals.tickPoison(a)
          vitals.tickMending(a)
          vitals.regen(a)
      }
      conditions.tickFields()
      players.toVector.foreach(AutoPlay.run(this, _))
      actorsNow.foreach {
        case m: Monster if m.dead && clock >= m.removeAt => actors.remove(m.id): Unit
        case _                                           => ()
      }
      ground.values.toVector.foreach(g => if clock - g.droppedAt > ItemKeepMs then ground.remove(g.id): Unit)
      trades.settle()

  // ---------------- 地上的东西 ----------------

  /** 放到 at 附近没有东西的可走格上。 */
  private[world] def dropAt(at: Cell, loot: Loot, ward: Option[Ward] = None): Unit =
    val taken = ground.valuesIterator.map(_.cell).toSet
    val cell = (0 until 4).iterator.flatMap { r =>
      for
        dy <- (-r to r).iterator
        dx <- (-r to r).iterator
        c = Cell(at.x + dx, at.y + dy)
        if terrain.walkable(c) && !taken.contains(c)
      yield c
    }.nextOption().getOrElse(at)
    val g = GroundItem(GroundId(nextGround), cell, loot, clock, ward)
    nextGround += 1
    ground(g.id) = g

  private[world] def removeGround(id: GroundId): Unit = ground.remove(id).foreach(_ => ())

  private[world] def newItem(name: ItemName): Option[ItemInstance] =
    catalog.item(name).map(d => Charges.fresh(uids.next(), d))

  private[world] def newUid(): ItemUid = uids.next()

  // ---------------- 指令进、消息出 ----------------

  /** 执行一个玩家的游戏指令；不合法的回 Refused（或移动回 MoveDenied）。 */
  def handle(id: ActorId, cmd: GameCmd): Unit =
    player(id).foreach(PlayerOps.handle(this, _, cmd))
    trades.settle() // 指令可能动了交易栏里的东西（用掉、扔掉）或把人传走

  /** 取走到目前为止要发的全部消息。 */
  def flush(): Outbox = visibility.flush(out.drain())

  /** 视野重来：玩家同图传送后，进图消息之后重新发身边的一切。 */
  private[world] def resetView(p: Player): Unit = visibility.restart(p.id)

  // 开图：放 NPC、守卫（放在类的最后，字段都已初始化）
  spawner.open()
