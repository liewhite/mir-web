package mir.world

import mir.core.*
import mir.data.{Catalog, DropDef, MapDef, MonsterActionTable, MonsterDef}
import mir.model.*
import mir.protocol.*
import mir.rules.{ItemEffects, ItemKind, Leveling, PassiveSkills, Progress, Rates, Recovery, Rng, Rules}

import scala.collection.mutable

/** 世界处理完一批指令、推进一次之后要发出去的东西。 */
final case class Outbox(
    /** 每个玩家（按本世界里的对象编号）要收的消息，按顺序 */
    messages: Map[ActorId, Vector[ServerMsg]],
    /** 离开本图去别的图的玩家：服务端把他交给目的地图的世界 */
    departures: Vector[Departure]
)

/** 一个玩家换图：存档（位置已是目的地）、跟着走的宝宝。 */
final case class Departure(player: ActorId, data: CharacterData, pets: List[PetData]):
  def to: Place = data.place

object World:
  /** 视野：玩家能看到身边多少格以内（切比雪夫距离） */
  val ViewRange = 14
  /** 指令到早了也算数的余量（网络抖动），毫秒 */
  val Slack = 200
  val WalkMs      = 600
  val RunMs       = 600
  val HitAnimMs   = 510
  val SpellAnimMs = 600
  val SpellInterval = 800
  /** 野蛮冲撞每冲一格的时长（原版跑步半圈）、冲完的晕眩（原版 g_dwDizzyDelayTime） */
  val RushStepMs  = RunMs / 2
  val RushDizzyMs = 300
  /** 被推后退一格的时长（原版倒放走路、跳帧，约走路的一半）；怪被推后多歇的时间（CharPushed 的 WalkTick + 800） */
  val BackstepMs     = WalkMs / 2
  val PushedPauseMs  = 800
  val CorpseMs    = 10_000L
  val ItemKeepMs  = 10 * 60_000L

/**
 * 一张地图的世界：地图上的玩家、怪物、NPC、地上的东西，时间推进和玩家指令。
 *
 * - 单线程使用：服务端每张图一个，自己排队调用 handle / tick / flush。内部状态可变，对外只有「指令进、消息出」。
 * - 随机数和时钟从外面传入（测试可固定）；没人的图 tick 什么也不做。
 * - 出去的消息只在 flush 时按视野算：每个玩家收到身边对象的出现/消失/动作/血量、地上物品、附近的特效声音飘字，
 *   以及自己变了的属性、背包等。
 */
final class World(val catalog: Catalog, val mapDef: MapDef, val terrain: Terrain, rng: Rng, uids: UidSource, start: Millis):
  import World.*
  private[world] given Rng = rng

  private var clock: Millis = start
  def now: Millis           = clock
  def mapId: MapId          = mapDef.id

  private[world] val actors = mutable.LinkedHashMap.empty[ActorId, Actor]
  private[world] val ground = mutable.LinkedHashMap.empty[GroundId, GroundItem]
  private[world] val fields = mutable.LinkedHashMap.empty[FieldId, Field]
  private val occupancy     = new Array[Int](terrain.width * terrain.height)
  private var nextActor     = 1
  private var nextGround    = 1
  private var nextField     = 1

  // ---------------- 生成 NPC、守卫，排上刷怪 ----------------

  private val spawns: Vector[(mir.data.SpawnDef, Int)] =
    catalog.data.spawns.zipWithIndex.filter(_._1.map == mapDef.id).toVector
  /** 刷怪点（按 game.json 下标）→ 下一次补怪的时刻 */
  private val spawnDue = mutable.Map.from(spawns.map((_, i) => i -> start))

  // ---------------- 查询 ----------------

  def players: Iterable[Player] = actors.values.collect { case p: Player => p }
  def hasPlayers: Boolean       = actors.valuesIterator.exists(_.isInstanceOf[Player])
  def actor(id: ActorId): Option[Actor] = actors.get(id)
  def player(id: ActorId): Option[Player] = actors.get(id).collect { case p: Player => p }
  def groundItems: Iterable[GroundItem] = ground.values
  def activeFields: Iterable[Field]     = fields.values
  private[world] def rngOf: Rng = rng
  /** 此刻全部对象的快照（遍历时可以增删） */
  private[world] def actorsNow: Vector[Actor] = actors.values.toVector
  private[world] def monsters: Vector[Monster] = actors.values.collect { case m: Monster => m }.toVector

  /** 一个对象此刻的样子（进入视野时发的全貌）。 */
  def viewOf(a: Actor): ActorView =
    ActorView(a.id, a.look, a.cell, a.dir, Hp(a.hp), Hp(a.maxHp), a.action, a.dead)

  /** 某玩家此刻能看到的对象和地上物品。 */
  def visibleTo(p: Player): (Iterable[Actor], Iterable[GroundItem]) =
    (actors.values.filter(a => sees(p, a.cell)), ground.values.filter(g => sees(p, g.cell)))

  /** 某玩家此刻能看到的地上法术。 */
  def fieldsVisibleTo(p: Player): Iterable[Field] = fields.values.filter(f => sees(p, f.cell))

  private def sees(p: Player, c: Cell): Boolean = p.cell.chebyshev(c) <= ViewRange

  // ---------------- 地图与占位 ----------------

  private def newActorId(): ActorId =
    val id = ActorId(nextActor)
    nextActor += 1
    id

  private def idx(c: Cell): Int = c.y * terrain.width + c.x

  def occupant(c: Cell): Option[Actor] =
    if !terrain.inside(c) then None
    else
      val id = occupancy(idx(c))
      if id == 0 then None else actors.get(ActorId(id))

  private def occupy(a: Actor): Unit = occupancy(idx(a.cell)) = a.id.value

  private def vacate(a: Actor): Unit =
    val i = idx(a.cell)
    if occupancy(i) == a.id.value then occupancy(i) = 0

  /** 地形可走且没人站。 */
  def canEnter(c: Cell): Boolean = terrain.walkable(c) && occupant(c).isEmpty

  def freeCellNear(p: Cell, radius: Int = 6): Option[Cell] =
    (0 to radius).iterator.flatMap { r =>
      for
        dy <- (-r to r).iterator
        dx <- (-r to r).iterator
        if math.max(math.abs(dx), math.abs(dy)) == r
        c = Cell(p.x + dx, p.y + dy)
        if canEnter(c)
      yield c
    }.nextOption()

  // ---------------- 出去的消息 ----------------

  private val personal  = mutable.LinkedHashMap.empty[ActorId, mutable.ArrayBuffer[ServerMsg]]
  /** 附近的人能看到的事：发生在哪一格、是什么 */
  private val local     = mutable.ArrayBuffer.empty[(Cell, ServerMsg)]
  private val departed  = mutable.ArrayBuffer.empty[Departure]

  private[world] def tell(p: Player, msg: ServerMsg): Unit =
    personal.getOrElseUpdate(p.id, mutable.ArrayBuffer.empty) += msg

  private[world] def notice(p: Player, text: String, tone: Tone): Unit = tell(p, ServerMsg.Notice(text, tone))
  private[world] def refuse(p: Player, why: String): Unit            = tell(p, ServerMsg.Refused(why))

  /** 附近的人都看得到/听得到的事。 */
  private[world] def nearby(at: Cell, msg: ServerMsg): Unit = local += at -> msg

  private[world] def sound(at: Cell, id: Int): Unit = nearby(at, ServerMsg.Sound(id, Some(at)))
  /** 只有自己听得到的声音（脚步、喝药） */
  private[world] def selfSound(p: Player, id: Int): Unit = tell(p, ServerMsg.Sound(id, None))

  private[world] def effect(
      lib: String, start: Int, frames: Int, frameTime: Int, at: Anchor,
      from: Option[Cell] = None, dir: Option[Dir] = None, dirStride: Option[Int] = None,
      blend: Boolean = true, duration: Option[Int] = None
  ): Unit =
    val cell = at match
      case Anchor.OnCell(c)   => c
      case Anchor.OnActor(id) => actors.get(id).fold(from.getOrElse(Cell(0, 0)))(_.cell)
    nearby(cell, ServerMsg.Effect(EffectView(lib, start, frames, frameTime, at, from, dir, dirStride, blend, clock, duration.getOrElse(frames * frameTime))))

  // ---------------- 时间推进 ----------------

  private final case class Timer(at: Millis, seq: Long, run: () => Unit)
  private val timers   = mutable.ArrayBuffer.empty[Timer]
  private var timerSeq = 0L

  /** 按游戏时间延后执行（飞行技能命中、药效分跳、钻回地下等）。 */
  private[world] def after(ms: Long)(f: => Unit): Unit =
    timerSeq += 1
    timers += Timer(clock + ms, timerSeq, () => f)

  /** 推进到 to（不会倒退）。没有玩家的图什么也不做（时钟照走）。 */
  def tick(to: Millis): Unit =
    if to > clock then clock = to
    if hasPlayers then
      // 发狂的怪不算：野怪不会主动去找它，只在挨了它的打时还手
      fighters = actors.values.filter(a => side(a).exists(s => s != Side.Wild && s != Side.Crazy)).toVector
      val due = timers.filter(_.at <= clock).sortBy(t => (t.at.value, t.seq))
      timers.filterInPlace(_.at > clock)
      due.foreach(_.run())
      runSpawns()
      actors.values.toVector.foreach { a =>
        if actors.contains(a.id) then
          a match
            case m: Monster => MonsterAi.think(this, m)
            case _          => ()
          tickStatus(a)
      }
      tickFields()
      players.toVector.foreach { p =>
        regen(p)
        AutoPlay.run(this, p)
      }
      actors.values.toVector.foreach {
        case m: Monster if m.dead && clock >= m.removeAt => actors.remove(m.id)
        case _                                           => ()
      }
      ground.values.toVector.foreach(g => if clock - g.droppedAt > ItemKeepMs then ground.remove(g.id): Unit)

  private def tickStatus(a: Actor): Unit =
    a.poison match
      case Some(p) if !a.dead =>
        if clock >= p.until then a.poison = None
        else if p.kind == PoisonKind.Green && clock >= p.nextTick then
          a.poison = Some(p.copy(nextTick = clock + 2000))
          applyDamage(a, math.max(1, p.power), Some(p.by), interrupt = false, struck = false)
      case _ => ()
    // 神圣战甲术、幽灵盾到期
    val acEnd  = a.acUp.exists(_.until <= clock)
    val macEnd = a.macUp.exists(_.until <= clock)
    if acEnd then a.acUp = None
    if macEnd then a.macUp = None
    a match
      case p: Player if acEnd || macEnd =>
        recalc(p)
        if acEnd then notice(p, "防御力回复正常", Tone.System)
        if macEnd then notice(p, "魔法防御力回复正常", Tone.System)
      case m: Monster => m.mood = m.moodAt(clock)
      case _          => ()

  /** 给对象加上神圣战甲术（acUp）或幽灵盾；再放一次不会把还没到期的时间缩短。 */
  private[world] def defenceUp(a: Actor, magic: Boolean, amount: Int, secs: Int): Unit =
    val until = clock + secs * 1000L
    def merge(old: Option[Buff]) = Some(Buff(amount, old.fold(until)(o => if o.until > until then o.until else until)))
    if magic then a.macUp = merge(a.macUp) else a.acUp = merge(a.acUp)
    a match
      case p: Player =>
        recalc(p)
        notice(p, s"${if magic then "魔法防御" else "防御"}上限 +$amount，$secs 秒", Tone.Good)
      case _ => ()

  /** 怪物被定住 / 发狂。 */
  private[world] def seize(m: Monster, secs: Int): Unit =
    m.seizedUntil = clock + secs * 1000L
    m.target = None
    m.mood = m.moodAt(clock)

  private[world] def enrage(m: Monster, secs: Int): Unit =
    m.crazyUntil = clock + secs * 1000L
    m.target = None
    m.mood = m.moodAt(clock)

  // ---------------- 地上持续的法术 ----------------

  /** 放一格地上法术；这格已有的不重叠（原版 GetEvent == null 才放）。放了返回 true。 */
  private[world] def addField(cell: Cell, effect: FieldEffect, ms: Long): Boolean =
    if !terrain.inside(cell) || fields.valuesIterator.exists(_.cell == cell) then false
    else
      val f = Field(FieldId(nextField), cell, effect, clock + ms, clock)
      nextField += 1
      fields(f.id) = f
      true

  /** 到期的去掉；火墙每 3 秒烧一次站在上面的敌人（放下就烧第一次，按魔防减，经验归放火的人）。 */
  private def tickFields(): Unit =
    fields.values.toVector.foreach { f =>
      if clock >= f.until then fields.remove(f.id): Unit
      else
        f.effect match
          case FieldEffect.Fire(owner, damage) if clock >= f.nextBurn =>
            fields(f.id) = f.copy(nextBurn = clock + mir.rules.SpellRules.FireWallTickMs)
            for
              o <- actors.get(owner)
              t <- occupant(f.cell) if isEnemy(o, t)
            do
              val dmg = Rules.afterMagicArmor(damage, t.mac)
              if dmg > 0 then applyDamage(t, dmg, Some(owner), interrupt = false) else miss(t)
          case _ => ()
    }

  /** 自然回复（规则见 Recovery）。 */
  private def regen(p: Player): Unit =
    if !p.dead && clock - p.lastRegen >= Recovery.IntervalMs then
      p.lastRegen = clock
      val s = p.stats
      p.hp = math.min(s.maxHp, p.hp + Recovery.hpPerTick(s)).max(p.hp)
      p.mp = math.min(s.maxMp, p.mp + Recovery.mpPerTick(s)).max(p.mp)

  private def runSpawns(): Unit = spawns.foreach { (s, i) =>
    if spawnDue.get(i).exists(_ <= clock) then
      catalog.monster(s.monster) match
        case None => spawnDue.remove(i): Unit
        case Some(d) =>
          val alive = actors.valuesIterator.count {
            case m: Monster => m.spawn.contains(i) && !m.dead
            case _          => false
          }
          // 一次补齐，大刷怪点分几批
          (alive until math.min(s.count, alive + 40)).foreach(_ => spawnMonster(d, Some(i), s.at, s.radius))
          spawnDue(i) = clock + math.max(1, s.minutes) * 60_000L
  }

  // ---------------- 怪物 ----------------

  private[world] def spawnMonster(d: MonsterDef, spawn: Option[Int], at: Cell, radius: Int): Option[Monster] =
    val tries = Iterator.fill(20)(Cell(at.x + rng.random(radius * 2 + 1) - radius, at.y + rng.random(radius * 2 + 1) - radius))
    tries.find(canEnter).orElse(freeCellNear(at, math.max(3, radius))).map { c =>
      val m = Monster(newActorId(), d, Behavior.of(d), spawn, c, Dir.fromCode(rng.random(8)), clock)
      m.nextThink = clock + rng.random(2000)
      actors(m.id) = m
      occupy(m)
      MonsterAi.init(this, m)
      m
    }

  private[world] def actTable(m: Monster): Option[MonsterActionTable] = catalog.actions(m.defn)

  private def monsterMoveMs(m: Monster): Int =
    val anim = actTable(m).fold(600)(t => t.walk.frames * t.walk.frameTime)
    math.max(300, math.min(anim, m.walkInterval))

  // ---------------- 动作与移动 ----------------

  private[world] def setAction(a: Actor, kind: ActKind, duration: Int, from: Option[Cell] = None, hit: Option[HitStyle] = None, spellEffect: Option[Int] = None): Unit =
    a.action = ActionView(kind, clock, duration, from, hit, spellEffect)

  /** 走/跑一步（跑两格）。成功返回 true。 */
  private[world] def move(a: Actor, dir: Dir, run: Boolean): Boolean =
    val steps = if run then 2 else 1
    val path  = (1 to steps).map(a.cell.step(dir, _))
    if a.dead || !path.forall(canEnter) then false
    else
      val from = a.cell
      vacate(a)
      a.cell = path.last
      a.dir = dir
      occupy(a)
      val dur = a match
        case _: Player  => if run then RunMs else WalkMs
        case m: Monster => monsterMoveMs(m)
        case _: Npc     => WalkMs
      setAction(a, if run then ActKind.Run else ActKind.Walk, dur, from = Some(from))
      a.busyUntil = clock + dur
      a match
        case p: Player => afterPlayerMove(p, run)
        case _         => ()
      true

  private var stepFoot = 0

  private def afterPlayerMove(p: Player, run: Boolean): Unit =
    val cave = mapDef.id.value.startsWith("D")
    val pair = (run, cave) match
      case (true, true)   => Sounds.runCave
      case (true, false)  => Sounds.runGround
      case (false, true)  => Sounds.walkCave
      case (false, false) => Sounds.walkGround
    selfSound(p, if stepFoot % 2 == 0 then pair._1 else pair._2)
    stepFoot += 1
    arrived(p)

  /** 玩家停在新格上：踩到传送点就传送。 */
  private[world] def arrived(p: Player): Unit =
    catalog.routesByMap.getOrElse(mapDef.id, Nil).find(_.at == p.cell).foreach(r => transfer(p, Place(r.to, r.target)))

  /** 挨推、冲撞时挪一格（不改动作和朝向，由调用方定）。进不去返回 false。 */
  private[world] def shove(a: Actor, dir: Dir): Boolean =
    val to = a.cell.step(dir)
    if a.dead || !canEnter(to) then false
    else
      vacate(a)
      a.cell = to
      occupy(a)
      true

  /**
   * 被推着倒退最多 n 格（原版 CharPushed）：脸朝推的人、倒着走，怪被推后多歇一会儿。返回实际退了几格。
   * 同一刻接着推（野蛮冲撞每冲一格推一次）时动画从最初的格算，一口气退过去。
   */
  private[world] def pushBack(a: Actor, dir: Dir, n: Int): Int =
    val origin = a.action match
      case ActionView(ActKind.Backstep, at, _, Some(from), _, _) if at == clock => from
      case _                                                                   => a.cell
    val moved = (1 to n).takeWhile(_ => shove(a, dir)).size
    if moved > 0 then
      val dur = origin.chebyshev(a.cell) * BackstepMs
      a.dir = dir.opposite
      setAction(a, ActKind.Backstep, dur, from = Some(origin))
      a.busyUntil = clock + dur + (a match
        case _: Monster => PushedPauseMs
        case _          => 0)
    moved

  private[world] def face(a: Actor, d: Dir): Unit = a.dir = d

  /** 瞬移到某格（沃玛教主、同图传送）。 */
  private[world] def teleportActor(a: Actor, to: Cell): Unit =
    vacate(a)
    a.cell = to
    occupy(a)
    setAction(a, ActKind.Stand, 0)

  // ---------------- 玩家进出 ----------------

  /**
   * 玩家进图：位置用存档里的格子（被占就找附近空格）。返回他在本图的对象编号。
   * 数据表里已不存在的物品、技能丢掉（旧存档不会把游戏弄坏）。
   */
  def join(data: CharacterData, pets: List[PetData] = Nil): ActorId =
    val cell = freeCellNear(data.place.cell).getOrElse(data.place.cell)
    val p    = Player(newActorId(), data.name, data.job, data.sex, data.hair, cell, data.dir, clock)
    def valid(it: ItemInstance): Option[ItemInstance] = catalog.item(it.name).map(_ => it)
    p.level = data.level
    p.exp = data.exp
    p.gold = data.gold
    p.home = data.home
    data.bag.take(Capacity.bag).zipWithIndex.foreach((it, i) => p.bag(i) = it.flatMap(valid))
    data.belt.take(Capacity.belt).zipWithIndex.foreach((it, i) => p.belt(i) = it.flatMap(valid))
    data.worn.foreach((s, it) => valid(it).foreach(p.worn(s) = _))
    p.storage ++= data.storage.flatMap(valid)
    Inventory.normalize(catalog, p) // 旧存档补数量、同名可叠的并成一叠（#1159）
    p.skills ++= data.skills.filter(s => catalog.skill(s.id).isDefined)
    p.settings = data.settings
    p.longHitOn = data.longHitOn
    p.wideHitOn = data.wideHitOn
    p.hp = data.hp.value
    p.mp = data.mp.value
    recalc(p)
    if p.hp <= 0 then p.hp = math.max(1, p.stats.maxHp / 3) // 死着下线的，回来时活着
    actors(p.id) = p
    occupy(p)
    tell(p, ServerMsg.EnterMap(p.id, mapDef.id, p.cell, p.dir, clock))
    sound(p.cell, Sounds.spaceIn)
    pets.foreach { pd =>
      catalog.monster(pd.kind).flatMap(spawnMonster(_, None, p.cell, 1)).foreach { m =>
        m.master = Some(p.id)
        m.petLevel = pd.level
        m.maxHpNow = pd.maxHp
        m.hp = math.min(pd.hp, pd.maxHp)
        m.dc = pd.dc
        m.cover = Cover.Open
        p.pets += m.id
      }
    }
    p.id

  /** 玩家下线：取出存档，人和宝宝从图上消失。 */
  def leave(id: ActorId): Option[CharacterData] = player(id).map { p =>
    val data = snapshot(p)
    removePlayer(p)
    p.pets.flatMap(actors.get).foreach(removeActor)
    data
  }

  /** 在线玩家的存档（定时保存用）。 */
  def snapshot(id: ActorId): Option[CharacterData] = player(id).map(snapshot)

  private[world] def snapshot(p: Player): CharacterData =
    CharacterData(
      name = p.charName, job = p.job, sex = p.sex, hair = p.hair, level = p.level, exp = p.exp, gold = p.gold,
      hp = Hp(p.hp), mp = Mp(p.mp), place = Place(mapDef.id, p.cell), dir = p.dir, home = p.home,
      bag = p.bag.toVector, belt = p.belt.toVector, worn = p.worn.toMap, storage = p.storage.toList,
      skills = p.skills.toList, settings = p.settings, longHitOn = p.longHitOn, wideHitOn = p.wideHitOn
    )

  private def removeActor(a: Actor): Unit =
    vacate(a)
    actors.remove(a.id)
    lastBroadcast.remove(a.id): Unit

  private def removePlayer(p: Player): Unit =
    removeActor(p)
    viewers.remove(p.id): Unit

  /** 把玩家送到某处：同图直接挪过去（像重新进图），别的图交给服务端。 */
  private[world] def transfer(p: Player, to: Place): Unit =
    p.intent = Intent.Idle
    if to.map == mapDef.id then
      val c = freeCellNear(to.cell).getOrElse(p.cell)
      teleportActor(p, c)
      p.pets.flatMap(actors.get).foreach(pet => freeCellNear(c).foreach(teleportActor(pet, _)))
      viewers.remove(p.id) // 视野重来：进图消息之后重新发身边的一切
      tell(p, ServerMsg.EnterMap(p.id, mapDef.id, p.cell, p.dir, clock))
      sound(p.cell, Sounds.spaceIn)
    else
      val pets = p.pets.flatMap(actors.get).collect {
        case m: Monster if !m.dead => PetData(m.defn.name, m.hp, m.maxHpNow, m.dc, m.petLevel)
      }.toList
      sound(p.cell, Sounds.spaceOut)
      departed += Departure(p.id, snapshot(p).copy(place = to), pets)
      removePlayer(p)
      p.pets.flatMap(actors.get).foreach(removeActor)

  // ---------------- 战斗结算 ----------------

  /** 阵营：玩家和宝宝、野怪、守卫、发狂的怪（和谁都是敌人，原版 CrazyMode）。 */
  private enum Side:
    case Players, Wild, Guards, Crazy

  private def side(a: Actor): Option[Side] = a match
    case _: Player                        => Some(Side.Players)
    case m: Monster if m.master.isDefined => Some(Side.Players)
    case m: Monster if m.isGuard          => Some(Side.Guards)
    case m: Monster if m.mood == Mood.Crazy => Some(Side.Crazy)
    case _: Monster                       => Some(Side.Wild)
    case _: Npc                           => None

  /** a 能不能打 b：不同阵营，守卫和玩家不互打（不做 PK），埋着的、石像打不到。 */
  def isEnemy(a: Actor, b: Actor): Boolean =
    val hidden = b match
      case m: Monster => m.cover != Cover.Open
      case _          => false
    if a.dead || b.dead || a.id == b.id || hidden then false
    else
      (side(a), side(b)) match
        case (Some(x), Some(y)) =>
          x != y && !Set(x, y).subsetOf(Set(Side.Players, Side.Guards))
        case _ => false

  /**
   * 本次 tick 开始时不是野怪的对象（玩家、宝宝、守卫）。野怪只会把它们当敌人，找敌人时只看这些，
   * 不用每只怪都把全图上千只怪扫一遍。
   */
  private var fighters: Vector[Actor] = Vector.empty

  /** range 格以内最近的敌人（怪看不见隐身的，除非能看穿隐身）。 */
  private[world] def nearestEnemy(m: Actor, range: Int): Option[Actor] =
    val seesInvisible = m match
      case mon: Monster => mon.defn.coolEye > 0
      case _            => true
    val candidates = if side(m).contains(Side.Wild) then fighters.iterator.filter(a => actors.contains(a.id)) else actors.valuesIterator
    candidates
      .filter(a => isEnemy(m, a) && (seesInvisible || a.invisibleUntil <= clock))
      .map(a => a -> m.cell.chebyshev(a.cell))
      .filter(_._2 <= range)
      .minByOption(_._2)
      .map(_._1)

  /** 武器外观（挥刀、打中的声音） */
  private[world] def weaponShape(p: Player): Option[Int] =
    p.worn.get(EquipSlot.Weapon).flatMap(it => catalog.item(it.name)).map(_.shape)

  /** 伤害结算：魔法盾减伤、被打动作、死亡、经验。 */
  private[world] def applyDamage(target: Actor, amount: Int, source: Option[ActorId], interrupt: Boolean = true, struck: Boolean = true): Unit =
    target match
      case _: Npc => ()
      case _ if target.dead => ()
      case _ =>
        // 中红毒的挨打 ×1.2（绿毒自己掉的血不算挨打）
        val poisoned = if struck && target.poison.exists(_.kind == PoisonKind.Red) then mir.rules.SpellRules.redPoisonDamage(amount) else amount
        val dmg = target match
          case _: Player if target.shieldUntil > clock =>
            val absorb = mir.rules.SpellRules.shieldAbsorb(target.shieldLevel)
            math.max(1, math.round(poisoned * (1 - absorb)).toInt)
          case _ => poisoned
        target.hp -= dmg
        target.lastHitBy = source
        val src = source.flatMap(actors.get)
        target match
          case m: Monster =>
            val shape = src.collect { case p: Player => p }.flatMap(weaponShape)
            sound(m.cell, Sounds.struckBody(shape))
            if m.hp > 0 then sound(m.cell, Sounds.monster(m.defn.appr, Sounds.MonsterCry.Struck))
          case p: Player if p.hp > 0 => sound(p.cell, if p.sex == Sex.Female then Sounds.womStruck else Sounds.manStruck)
          case _                     => ()
        nearby(target.cell, ServerMsg.Damage(target.id, dmg, source))
        if target.hp <= 0 then
          target.hp = 0
          kill(target, source)
        else
          target match
            case m: Monster if interrupt =>
              // 被定住的挨打就醒（AnimalObject 收到 RM_STRUCK 时 BreakHolySeizeMode）
              if m.seizedUntil > clock then
                m.seizedUntil = Millis.zero
                m.mood = m.moodAt(clock)
              val dur = actTable(m).fold(200)(t => math.max(200, t.struck.frames * t.struck.frameTime))
              if m.action.kind == ActKind.Stand || m.action.kind == ActKind.Struck then
                setAction(m, ActKind.Struck, dur)
                if m.busyUntil < clock + dur then m.busyUntil = clock + dur
              if m.target.isEmpty then m.target = source
            case p: Player if p.action.kind == ActKind.Stand => setAction(p, ActKind.Struck, 210)
            case _                                           => ()

  private[world] def miss(t: Actor): Unit = nearby(t.cell, ServerMsg.Miss(t.id))

  private def kill(target: Actor, killer: Option[ActorId]): Unit =
    target.dead = true
    target.poison = None
    setAction(target, ActKind.Die, 1200)
    target match
      case m: Monster =>
        sound(m.cell, Sounds.monster(m.defn.appr, Sounds.MonsterCry.Die))
        vacate(m)
        m.removeAt = clock + CorpseMs
        val owner = killer.flatMap(actors.get).flatMap {
          case p: Player   => Some(p)
          case k: Monster  => k.master.flatMap(player)
          case _: Npc      => None
        }
        owner.filter(_ => m.master.isEmpty).foreach { o =>
          gainExp(o, Rates.killExp(m.defn.exp, m.expScale))
          dropLoot(m)
        }
        m.master.flatMap(player).foreach(_.pets -= m.id)
        MonsterAi.onDeath(this, m)
      case p: Player =>
        sound(p.cell, if p.sex == Sex.Female then Sounds.womDie else Sounds.manDie)
        p.intent = Intent.Idle
        tell(p, ServerMsg.Died)
        notice(p, "你死了。点「回城复活」回到城里。", Tone.Bad)
      case _: Npc => ()

  /** 不给经验、不掉东西的死（自爆）。 */
  private[world] def killWithoutReward(m: Monster): Unit =
    m.hp = 0
    kill(m, None)

  /** 怪物原地复活（僵尸）。 */
  private[world] def reviveMonster(m: Monster): Unit =
    m.dead = false
    m.target = None
    m.removeAt = Millis.zero
    occupy(m)

  /** 死了的玩家回城复活：血蓝回三分之一。 */
  private[world] def revivePlayer(p: Player): Unit =
    p.dead = false
    p.hp = math.max(1, p.stats.maxHp / 3)
    p.mp = p.stats.maxMp / 3
    setAction(p, ActKind.Stand, 0)
    transfer(p, p.home)

  private[world] def gainExp(p: Player, exp: Exp): Unit =
    if exp.value > 0 then
      notice(p, s"获得经验 ${exp.value}", Tone.Good)
      val before = p.level
      val after  = Leveling.gain(catalog, Progress(p.level, p.exp), exp)
      p.level = after.level
      p.exp = after.exp
      if after.level > before then
        recalc(p)
        p.hp = p.stats.maxHp
        p.mp = p.stats.maxMp
        (before + 1 to after.level).foreach { lv =>
          tell(p, ServerMsg.LevelUp(lv))
          notice(p, s"升级了！现在 $lv 级", Tone.Good)
        }

  /** 按爆率表掉落（概率按 Rates 换算；金币数量 amount/2 + Random(amount)）。 */
  private def dropLoot(m: Monster): Unit = catalog.drops(m.defn.name).foreach { (d: DropDef) =>
    val itemDef = if d.isGold then None else catalog.item(d.item)
    val roll    = rng.random(1_000_000) / 1_000_000.0
    if roll < Rates.dropChance(d, itemDef) then
      if d.isGold then
        val g = d.amount / 2 + rng.random(d.amount)
        if g > 0 then dropAt(m.cell, Loot.Coins(Gold(g)))
      else
        itemDef.foreach { id =>
          val n = math.max(1, d.amount)
          // 可叠的一次掉多个就掉成一叠（#1159）
          if ItemKind.stacks(id) then dropAt(m.cell, Loot.Item(ItemInstance.fresh(uids.next(), id).copy(count = n)))
          else (1 to n).foreach(_ => dropAt(m.cell, Loot.Item(ItemInstance.fresh(uids.next(), id))))
        }
  }

  /** 放到 at 附近没有东西的可走格上。 */
  private[world] def dropAt(at: Cell, loot: Loot): Unit =
    val taken = ground.valuesIterator.map(_.cell).toSet
    val cell = (0 until 4).iterator.flatMap { r =>
      for
        dy <- (-r to r).iterator
        dx <- (-r to r).iterator
        c = Cell(at.x + dx, at.y + dy)
        if terrain.walkable(c) && !taken.contains(c)
      yield c
    }.nextOption().getOrElse(at)
    val g = GroundItem(GroundId(nextGround), cell, loot, clock)
    nextGround += 1
    ground(g.id) = g

  private[world] def removeGround(id: GroundId): Unit = ground.remove(id).foreach(_ => ())

  private[world] def newItem(name: ItemName): Option[ItemInstance] =
    catalog.item(name).map(d => ItemInstance.fresh(uids.next(), d))

  private[world] def newUid(): ItemUid = uids.next()

  // ---------------- 玩家属性 ----------------

  /** 重算属性：等级 + 身上装备（持久用完的不算；护身符和毒药不加属性）+ 被动技能。 */
  private[world] def recalc(p: Player): Unit =
    val effects = p.worn.values.flatMap { it =>
      catalog.item(it.name).filter(d => it.dura > 0 && !ItemKind.of(d).isInstanceOf[ItemKind.Amulet]).map(ItemEffects.of)
    }
    def lv(id: Int): Option[Int] = p.skill(SkillId(id)).map(_.level)
    val base = Rules.computeStats(p.job, p.level, effects, PassiveSkills(oneSword = lv(3), spiritSword = lv(4), powerHit = lv(7)))
    p.stats = base.copy(ac = p.acUp.fold(base.ac)(_.on(base.ac)), mac = p.macUp.fold(base.mac)(_.on(base.mac)))
    p.hp = math.min(p.hp, p.stats.maxHp)
    p.mp = math.min(p.mp, p.stats.maxMp)

  // ---------------- 指令 ----------------

  /** 执行一个玩家的游戏指令；不合法的回 Refused（或移动回 MoveDenied）。 */
  def handle(id: ActorId, cmd: GameCmd): Unit = player(id).foreach(PlayerOps.handle(this, _, cmd))

  // ---------------- 视野：算出每个玩家要收的消息 ----------------

  /** 一个对象上次广播出去的样子（变了才再发）。 */
  private final case class Shown(cell: Cell, dir: Dir, action: ActionView, hp: Int, maxHp: Int, look: Look)
  private val lastBroadcast = mutable.Map.empty[ActorId, Shown]

  /** 一个玩家已经知道的对象、地上物品，和上次发给他的「自己」的状态。 */
  private final class Viewer:
    val actors = mutable.Set.empty[ActorId]
    val ground = mutable.Set.empty[GroundId]
    val fields = mutable.Set.empty[FieldId]
    var self   = Option.empty[SelfView]

  private val viewers = mutable.Map.empty[ActorId, Viewer]

  private def shown(a: Actor): Shown = Shown(a.cell, a.dir, a.action, a.hp, a.maxHp, a.look)

  /** 自己的全部界面状态。 */
  private[world] def selfView(p: Player): SelfView =
    SelfView(
      stats = SelfStats(p.level, p.exp, catalog.expToNext(p.level), Hp(p.hp), Mp(p.mp), p.gold, p.stats),
      bag = p.bag.toVector, belt = p.belt.toVector, worn = p.worn.toMap, storage = p.storage.toList, skills = p.skills.toList,
      cooldowns = p.cooldown.toMap,
      status = SelfStatus(
        poison = p.poison.map(_.kind), poisonUntil = p.poison.map(_.until),
        shieldUntil = Option.when(p.shieldUntil > clock)(p.shieldUntil),
        invisibleUntil = Option.when(p.invisibleUntil > clock)(p.invisibleUntil),
        paralyzedUntil = Option.when(p.paralyzedUntil > clock)(p.paralyzedUntil),
        acUpUntil = p.acUp.map(_.until),
        macUpUntil = p.macUp.map(_.until)
      ),
      sword = SwordState(p.longHitOn, p.wideHitOn, p.fireReady),
      settings = p.settings
    )

  /** 取走到目前为止要发的全部消息。 */
  def flush(): Outbox =
    val out = mutable.LinkedHashMap.empty[ActorId, mutable.ArrayBuffer[ServerMsg]]
    def to(id: ActorId) = out.getOrElseUpdate(id, mutable.ArrayBuffer.empty)
    personal.foreach((id, msgs) => to(id) ++= msgs)

    // 每个对象变了什么（全图算一次）
    val changes = mutable.Map.empty[ActorId, List[ServerMsg]]
    actors.values.foreach { a =>
      val now = shown(a)
      lastBroadcast.get(a.id).foreach { was =>
        val act  = Option.when(was.cell != now.cell || was.dir != now.dir || was.action != now.action)(ServerMsg.Act(a.id, now.cell, now.dir, now.action))
        val hp   = Option.when(was.hp != now.hp || was.maxHp != now.maxHp)(ServerMsg.HpChanged(a.id, Hp(now.hp), Hp(now.maxHp)))
        val look = Option.when(was.look != now.look)(ServerMsg.LookChanged(a.id, now.look))
        val all  = List(look, act, hp).flatten
        if all.nonEmpty then changes(a.id) = all
      }
      lastBroadcast(a.id) = now
    }
    lastBroadcast.keySet.diff(actors.keySet).foreach(lastBroadcast.remove)

    players.foreach { p =>
      val v   = viewers.getOrElseUpdate(p.id, Viewer())
      val buf = to(p.id)
      val (seenActors, seenGround) = visibleTo(p)
      val nowIds = seenActors.map(_.id).toSet
      v.actors.diff(nowIds).foreach(id => buf += ServerMsg.Disappear(id))
      seenActors.foreach { a =>
        if v.actors.contains(a.id) then changes.get(a.id).foreach(buf ++= _)
        else buf += ServerMsg.Appear(viewOf(a))
      }
      v.actors.clear()
      v.actors ++= nowIds
      val gIds = seenGround.map(_.id).toSet
      v.ground.diff(gIds).foreach(id => buf += ServerMsg.GroundDisappear(id))
      seenGround.filterNot(g => v.ground.contains(g.id)).foreach(g => buf += ServerMsg.GroundAppear(g.view))
      v.ground.clear()
      v.ground ++= gIds
      val seenFields = fieldsVisibleTo(p)
      val fIds       = seenFields.map(_.id).toSet
      v.fields.diff(fIds).foreach(id => buf += ServerMsg.FieldDisappear(id))
      seenFields.filterNot(f => v.fields.contains(f.id)).foreach(f => buf += ServerMsg.FieldAppear(f.view))
      v.fields.clear()
      v.fields ++= fIds
      local.foreach((c, m) => if sees(p, c) then buf += m)
      val sv = selfView(p)
      buf ++= SelfView.diff(v.self, sv)
      v.self = Some(sv)
    }
    val result = Outbox(out.view.mapValues(_.toVector).toMap.filter(_._2.nonEmpty), departed.toVector)
    personal.clear()
    local.clear()
    departed.clear()
    result

  // ---------------- 开图：放 NPC、守卫（放在类的最后，字段都已初始化） ----------------

  catalog.data.merchants.zipWithIndex.filter(_._1.map == mapDef.id).foreach { (m, i) =>
    val npc = Npc(newActorId(), MerchantRef(i), m, clock)
    actors(npc.id) = npc
    occupy(npc)
  }
  catalog.guardsByMap.getOrElse(mapDef.id, Nil).foreach { g =>
    catalog.monster(g.monster).flatMap(spawnMonster(_, None, g.at, 0)).foreach(m => m.dir = g.direction)
  }

/** 一个玩家界面上「自己」的全部状态；变了的部分才发。 */
final case class SelfView(
    stats: SelfStats,
    bag: Vector[Option[ItemInstance]],
    belt: Vector[Option[ItemInstance]],
    worn: Map[EquipSlot, ItemInstance],
    storage: List[ItemInstance],
    skills: List[LearnedSkill],
    cooldowns: Map[SkillId, Millis],
    status: SelfStatus,
    sword: SwordState,
    settings: CharSettings
)

object SelfView:
  def diff(was: Option[SelfView], now: SelfView): List[ServerMsg] =
    def changed[A](f: SelfView => A): Boolean = !was.map(f).contains(f(now))
    List(
      Option.when(changed(_.stats))(ServerMsg.Stats(now.stats)),
      Option.when(changed(v => (v.bag, v.belt)))(ServerMsg.Bag(now.bag, now.belt)),
      Option.when(changed(_.worn))(ServerMsg.Equipment(now.worn)),
      Option.when(changed(_.storage))(ServerMsg.Storage(now.storage)),
      Option.when(changed(_.skills))(ServerMsg.Skills(now.skills)),
      Option.when(changed(_.cooldowns))(ServerMsg.Cooldowns(now.cooldowns)),
      Option.when(changed(_.status))(ServerMsg.Status(now.status)),
      Option.when(changed(_.sword))(ServerMsg.Sword(now.sword)),
      Option.when(changed(_.settings))(ServerMsg.Settings(now.settings))
    ).flatten
