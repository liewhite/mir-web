package mir.world

import mir.core.*
import mir.model.*
import mir.protocol.Anchor
import mir.rules.{PetBody, Rng, SpellRules}
import mir.terrain.Path

/**
 * 怪物每次思考做什么：按 Behavior 出招、藏身、附加本领、追人、闲逛。
 * 出处见 Behavior；照 TS 版 monster_ai.ts 移植，多人时「目标」可以是任何一个玩家或宝宝。
 */
object MonsterAi:
  import Sounds.MonsterCry

  /** 刚刷出来：按行为设好藏身状态、各项本领的初始状态。 */
  def init(w: World, m: Monster): Unit =
    given Rng = w.rngOf
    if m.style.fixedDir then w.grid.face(m, Dir.Up)
    m.behavior.hiding match
      case Hiding.Burrow(_, _) | Hiding.DigOut | Hiding.Centipede => m.cover = Cover.Buried
      case Hiding.Statue(_) =>
        m.cover = Cover.Stone
        m.style.statueDir.foreach(w.grid.face(m, _))
      case Hiding.Never => m.cover = Cover.Open
    m.extras = m.behavior.extras.map(_.initial(w.now)).toVector
    m.bornAt = w.now
    // 神兽出现时从地下钻出来（TElfMonster / TElfWarriorMonster 第一次 Run 发 RM_DIGUP）
    if m.extras.exists(_.isInstanceOf[ExtraState.Shapeshift]) then
      shinsuPace(m)
      digAnim(w, m, ActKind.DigUp): Unit

  def think(w: World, m: Monster): Unit =
    val rng = w.rngOf
    if w.now >= m.nextThink && w.now >= m.busyUntil then
      if m.dead then updateExtras(m)(deadTick(w, m, _))
      else if m.statuses.has(StatusKind.Seized) then
        // 被定住（诱惑之光、困魔咒）：站着不动不打
        m.nextThink = w.now + 200
        if m.action.kind != ActKind.Stand && w.now >= m.action.startedAt + m.action.duration then w.grid.setAction(m, ActKind.Stand, 0)
      else
        m.nextThink = w.now + 100 + rng.random(100)
        if m.action.kind != ActKind.Stand && w.now >= m.action.startedAt + m.action.duration then w.grid.setAction(m, ActKind.Stand, 0)
        val b = m.behavior
        if updateCover(w, m, b) then
          updateExtras(m)(extraTick(w, m, _))
          // 本领处理里放了动作（变身钻出、钻下）的，这回合不再出手走动
          if !m.dead && w.now >= m.busyUntil then
            b.strike match
              case Strike.Explode if w.now - m.bornAt > 60_000 => explode(w, m)
              case s: Strike.Spawn                           => spawnTick(w, m, s)
              case _                                         => hunt(w, m, b)

  /**
   * 宝宝离主人横或竖超过这么多格就瞬移回主人身后（ObjMon.pas MonsterObject.Run：
   * abs(dx) > 20 or abs(dy) > 20 时 SpaceMove 到主人 GetBackPosition）。
   * 主人换图、同图传送时宝宝跟着走在 Roster.transfer 里。
   */
  val PetLeash = 20

  /** 离主人太远就瞬移回去，返回是否瞬移了（找不到空格就留原地，下回合再试）。 */
  private def recall(w: World, m: Monster, master: Actor): Boolean =
    m.cell.chebyshev(master.cell) > PetLeash && {
      val to = w.freeCellNear(master.cell.step(master.dir.opposite))
      to.foreach { c =>
        w.out.sound(m.cell, Sounds.spaceOut)
        w.grid.teleportActor(m, c)
        m.target = None
        w.out.sound(c, Sounds.spaceIn)
      }
      to.isDefined
    }

  private def hunt(w: World, m: Monster, b: Behavior): Unit =
    val master = m.master.flatMap(w.actor)
    if !master.exists(recall(w, m, _)) then chase(w, m, b, master)

  private def chase(w: World, m: Monster, b: Behavior, master: Option[Actor]): Unit =
    val kept = m.target.flatMap(w.actor).filter { t =>
      !t.dead && w.isEnemy(m, t) && m.cell.chebyshev(t.cell) <= 14 && !t.statuses.has(StatusKind.Invisible)
    }
    // 没目标时每回合找；有目标时按 retarget 定时重找，找到更近的就换，没找到继续追原来的（原版 SearchTarget 只在找到时 SetTargetCreat）
    val seeks = !(b.passive && master.isEmpty)
    val found =
      if seeks && (kept.isEmpty || searchDue(w, m, b.retarget)) then
        m.lastSearch = w.now
        val range = if m.isGuard then 6 else if master.isDefined then 8 else b.strike match
          case Strike.Area(r, _) => r
          case _                 => 7
        w.hostility.nearestEnemy(m, range)
      else None
    val target = found.orElse(kept)
    m.target = target.map(_.id)
    target match
      case Some(t) =>
        if !attack(w, m, b, t) && !m.isGuard && !b.rooted then stepToward(w, m, t.cell)
      case None =>
        master match
          case Some(ms) =>
            if m.cell.chebyshev(ms.cell) > 2 then
              val pace = SpellRules.catchUpMs(m.defn.name).fold(m.walkInterval)(math.min(_, m.walkInterval))
              stepToward(w, m, ms.cell, pace)
          case None if m.isGuard || b.rooted => ()
          case None =>
            // 闲逛
            if w.rngOf.random(100) < 4 && w.grid.move(m, Dir.fromCode(w.rngOf.random(8)), run = false) then
              m.busyUntil = w.now + m.defn.walkSpeed + w.rngOf.random(1000)

  /** 已有目标时该不该重找最近的敌人。 */
  private def searchDue(w: World, m: Monster, r: Retarget): Boolean = r match
    case Retarget.Every(ms)  => w.now - m.lastSearch >= ms
    case Retarget.EachAttack => ready(w, m)
    case Retarget.Never      => false

  /**
   * 挨了 by 的打要不要改打它（AnimalObject.Struck）：没目标、当前目标就贴在身边一格（GetAttackDir）、
   * 或者 1/6 的机会，就改打 by（by 得是它能打的敌人，IsProperTarget）。所以怪贴身砸玩家时，宝宝一打就把它拉走。
   * 野怪、宝宝、发狂的怪都是这条；守卫原版是 SuperGuard（不走 AnimalObject.Struck），不进这里。
   */
  def struck(w: World, m: Monster, by: Actor): Unit =
    val current  = m.target.flatMap(w.actor).filter(!_.dead)
    val switches = current.forall(t => m.cell.chebyshev(t.cell) == 1) || w.rngOf.random(6) == 0
    if switches && w.isEnemy(m, by) then m.target = Some(by.id)

  // ---------------- 藏身 ----------------

  /** 先处理藏身状态。返回 false 表示这回合还藏着、不做别的。 */
  private def updateCover(w: World, m: Monster, b: Behavior): Boolean =
    def near(r: Int, strict: Boolean = false): Option[Actor] =
      w.hostility.nearestEnemy(m, r).filter(e => !strict || m.cell.chebyshev(e.cell) < r)
    (m.cover, b.hiding) match
      case (Cover.Stone, Hiding.Statue(wakeAll)) =>
        near(2).foreach { _ =>
          surface(w, m)
          if wakeAll then
            w.monsters.filter(o => o.cover == Cover.Stone && o.cell.chebyshev(m.cell) <= 7 && o.behavior.hiding.isInstanceOf[Hiding.Statue])
              .foreach(surface(w, _))
        }
        false
      case (Cover.Buried, h) =>
        val tooSoon = h == Hiding.Centipede && w.now - m.coverAt < 10_000
        if !tooSoon then
          // 食人花、触龙神：距离 < 4 格钻出；僵尸：≤ 3 格
          val e = h match
            case Hiding.Burrow(comeOut, _) => near(comeOut, strict = true)
            case Hiding.Centipede          => near(4, strict = true)
            case _                         => near(3)
          e.foreach { _ =>
            surface(w, m)
            if h == Hiding.Centipede then w.vitals.heal(m, m.maxHp): Unit
          }
        false
      case (_, Hiding.Burrow(_, reach)) =>
        val t = m.target.flatMap(w.actor).filter(t => !t.dead && m.cell.chebyshev(t.cell) <= reach)
        t.orElse(w.hostility.nearestEnemy(m, reach)) match
          case None =>
            burrow(w, m)
            false
          case Some(e) =>
            m.target = Some(e.id)
            true
      case (_, Hiding.Centipede) =>
        if w.now - m.coverAt < 3000 then false
        else if w.hostility.nearestEnemy(m, 5).isEmpty && w.now - m.coverAt > 10_000 then
          burrow(w, m)
          false
        else true
      case _ => true

  private def digAnim(w: World, m: Monster, kind: ActKind): Int =
    val info = w.actTable(m).map(t => if kind == ActKind.DigDown || !m.style.herb then t.death else t.walk)
    val dur  = info.fold(600)(i => math.max(300, i.frames * i.frameTime))
    w.grid.setAction(m, kind, dur)
    m.busyUntil = w.now + dur
    dur

  /** 钻出地面、石像醒来。 */
  private def surface(w: World, m: Monster): Unit =
    m.cover = Cover.Open
    m.coverAt = w.now
    digAnim(w, m, ActKind.DigUp)
    w.out.sound(m.cell, Sounds.monster(m.defn.appr, MonsterCry.Appear))

  private def burrow(w: World, m: Monster): Unit =
    m.target = None
    m.coverAt = w.now
    val dur = digAnim(w, m, ActKind.DigDown)
    w.afterOn(m, dur)(_.cover = Cover.Buried)

  // ---------------- 出招 ----------------

  private def power(w: World, m: Monster): Int = m.dc.lo + w.rngOf.random(math.max(0, m.dc.hi - m.dc.lo) + 1)

  /** 物理一下：扣防御；checkHit 时按怪的准确判命中。 */
  private def physical(w: World, m: Monster, t: Actor, pw: Int, checkHit: Boolean = true): StrikeResult =
    w.vitals.strike(Some(m), t, Blow(Defence.Armor, pw, accuracy = Option.when(checkHit)(m.hit)))

  /** 魔法一下：扣魔防；checkHit 时按怪的准确判命中（毒液、毒气）。 */
  private def magical(w: World, m: Monster, t: Actor, pw: Int, checkHit: Boolean = false): StrikeResult =
    w.vitals.strike(Some(m), t, Blow(Defence.MagicArmor, pw, accuracy = Option.when(checkHit)(m.hit)))

  /** 攻击动作：按动作表放动画，播声音和特效。返回动画时长。 */
  private def swing(w: World, m: Monster, t: Option[Actor]): Int =
    if !m.style.fixedDir then t.foreach(t => w.grid.face(m, m.cell.dirTo(t.cell)))
    val info = w.actTable(m).map(m.style.attackAction)
    val anim = info.fold(600)(i => i.frames * i.frameTime)
    w.grid.setAction(m, ActKind.Attack, anim)
    m.busyUntil = w.now + anim
    m.lastAttack = w.now
    w.out.sound(m.cell, Sounds.monster(m.defn.appr, MonsterCry.Attack))
    attackFx(w, m, t, info.fold(6)(_.frames), info.fold(100)(_.frameTime))
    anim

  /** 攻击时身上的特效（按外观查 MonsterLook）。 */
  private def attackFx(w: World, m: Monster, t: Option[Actor], frames: Int, frameTime: Int): Unit =
    m.style.attackFx.foreach { f =>
      val dir = (f.dir16, t) match
        case (true, Some(t)) => m.cell.dir16(t.cell)
        case (true, None)    => m.dir.code * 2
        case (false, _)      => m.dir.code
      val n  = f.frames.getOrElse(frames + f.extra)
      val ft = f.frameTime.getOrElse(frameTime)
      def show(m: Monster): Unit = w.out.effect(f.lib, f.base + dir * 10, n, ft, Anchor.OnActor(m.id))
      if f.atFrame > 0 then w.afterOn(m, f.atFrame.toLong * frameTime)(show) else show(m)
    }

  private def ready(w: World, m: Monster): Boolean = w.now - m.lastAttack >= m.attackInterval

  /** 能打就打（含冷却中原地等）；返回 false 表示够不着，要走近。 */
  private def attack(w: World, m: Monster, b: Behavior, t: Actor): Boolean =
    val rng = w.rngOf
    val d   = m.cell.chebyshev(t.cell)
    b.strike match
      case Strike.Melee | Strike.Magic | Strike.Split(_) | Strike.Gas | Strike.Explode =>
        if d > 1 then false
        else
          if !m.style.fixedDir then w.grid.face(m, m.cell.dirTo(t.cell))
          if ready(w, m) then
            b.strike match
              case Strike.Explode => explode(w, m)
              case s =>
                swing(w, m, Some(t))
                val pw = power(w, m)
                s match
                  case Strike.Melee => physical(w, m, t, pw): Unit
                  case Strike.Magic => magical(w, m, t, pw): Unit
                  case Strike.Split(ratio) =>
                    val ph = math.round(pw * ratio).toInt
                    if ph > 0 then physical(w, m, t, ph, checkHit = false): Unit
                    magical(w, m, t, pw - ph): Unit
                  case _ => gas(w, m, t, pw)
          true
      case Strike.Hold => true
      case Strike.Spit(poison) =>
        val dir = m.cell.dirTo(t.cell)
        if d > 2 || m.cell.step(dir, d) != t.cell then false
        else
          w.grid.face(m, dir)
          if ready(w, m) then
            swing(w, m, Some(t))
            val pw = power(w, m)
            for k <- 1 to 2 do
              w.occupants(m.cell.step(dir, k)).foreach { a =>
                if magical(w, m, a, pw, checkHit = true).connected && poison && !a.dead && rng.random(20) == 0 then poisonGreen(w, a, m, 30_000, 1)
              }
          true
      case Strike.Missile(range, fly, poison) =>
        if d < 1 || d > range then false
        else
          if ready(w, m) then
            swing(w, m, Some(t))
            val delay = d * 50 + 300
            fly.foreach { f =>
              w.out.effect(f.lib, f.frameFrom(m.cell, t.cell), f.frames, 60, Anchor.OnActor(t.id),
                from = Some(m.cell), blend = false, duration = Some(delay))
            }
            val pw = power(w, m)
            w.afterOn(t, delay) { t =>
              if physical(w, m, t, pw, checkHit = false).connected && poison && !t.dead && rng.random(4) == 0 then
                poisonGreen(w, t, m, 20_000, 3)
            }
          true
      case Strike.Lightning =>
        if d <= 2 && rng.random(3) == 0 && stepAway(w, m, t.cell) then true
        else if d >= 6 then false
        else
          if ready(w, m) then
            swing(w, m, Some(t))
            val dir = m.cell.dirTo(t.cell)
            val pw  = power(w, m)
            (1 to 8).flatMap(k => w.occupants(m.cell.step(dir, k))).distinctBy(_.id).foreach(magical(w, m, _, pw): Unit)
          true
      case Strike.Area(range, poison) =>
        if d < range && ready(w, m) then
          swing(w, m, Some(t))
          val pw    = power(w, m)
          val delay = if poison then 600 else 200
          w.actorsNow.filter(a => w.isEnemy(m, a) && m.cell.chebyshev(a.cell) < range).foreach { a =>
            w.afterOn(a, delay) { a =>
              if magical(w, m, a, pw).connected && poison && !a.dead && rng.random(4) == 0 then
                if rng.random(3) != 0 then poisonGreen(w, a, m, 60_000, 3) else paralyze(w, a, 5000)
            }
          }
        true // 不动的怪：够不着就等人走近
      case Strike.Warp =>
        if ready(w, m) then
          swing(w, m, Some(t))
          physical(w, m, t, power(w, m)): Unit
        true
      case Strike.Spawn(_, _) => true

  /** 毒气：判命中的魔法一下，打到身上有 1/20 麻痹。 */
  private def gas(w: World, m: Monster, t: Actor, pw: Int): Unit =
    if magical(w, m, t, pw, checkHit = true).connected && !t.dead && w.rngOf.random(20) == 0 then paralyze(w, t, 5000)

  private def poisonGreen(w: World, t: Actor, m: Monster, ms: Int, pw: Int): Unit =
    w.conditions.poison(t, PoisonKind.Green, ms, pw, m.id)
    t match
      case p: Player => w.out.notice(p, "你中毒了", mir.protocol.Tone.Bad)
      case _         => ()

  /** 麻痹（原版 STONE 状态）：这段时间不能动。 */
  private def paralyze(w: World, t: Actor, ms: Int): Unit =
    w.conditions.paralyze(t, ms)
    t match
      case p: Player => w.out.notice(p, s"你被麻痹了（${ms / 1000} 秒）", mir.protocol.Tone.Bad)
      case _         => ()

  private def explode(w: World, m: Monster): Unit =
    val pw = power(w, m)
    w.actorsNow.filter(a => m.cell.chebyshev(a.cell) <= 1).foreach(a => w.vitals.strike(Some(m), a, Blow(Defence.Blast, pw)): Unit)
    w.vitals.killWithoutReward(m)

  private def spawnTick(w: World, m: Monster, s: Strike.Spawn): Unit =
    m.minions.filterInPlace(id => w.actor(id).exists(!_.dead))
    if ready(w, m) then
      m.lastAttack = w.now
      w.hostility.nearestEnemy(m, 9).filter(_ => m.minions.size < 15).foreach { t =>
        swing(w, m, None)
        w.catalog.monster(s.child).foreach { d =>
          w.afterOn(m, 500) { m =>
            val at = if s.below then m.cell.step(Dir.Down) else m.cell
            w.spawner.spawnMonster(d, None, at, 1).foreach { c =>
              c.target = Some(t.id)
              m.minions += c.id
            }
          }
        }
      }

  // ---------------- 附加本领 ----------------
  // 每项本领的处理拿着它的旧状态、返回新状态；加一种本领在这几个 match 里各加一个分支。

  private def updateExtras(m: Monster)(f: ExtraState => ExtraState): Unit =
    val before = m.extras
    val after  = before.map(f)
    // 处理中变了身的，extras 已换成新形态的初始状态，不拿旧形态的结果盖掉
    if m.extras eq before then m.extras = after

  /** 活着时每回合 */
  private def extraTick(w: World, m: Monster, s: ExtraState): ExtraState = s match
    case r: ExtraState.Revive     => r
    case z: ExtraState.SummonZuma => summonZuma(w, m, z)
    case c: ExtraState.CowKing    => cowKing(w, m, c)
    case s: ExtraState.Shapeshift => shapeshift(w, m, s)

  /** 死了以后每回合 */
  private def deadTick(w: World, m: Monster, s: ExtraState): ExtraState = s match
    case r: ExtraState.Revive => tryRevive(w, m, r)
    case other                => other

  /** 刚死时 */
  def onDeath(w: World, m: Monster): Unit = updateExtras(m) {
    // 会复活的定好复活时间，尸体留到复活
    case r: ExtraState.Revive if r.left > 0 =>
      val at = w.now + (w.rngOf.random(20) + 4) * 1000L
      if m.removeAt < at + 1000 then m.removeAt = at + 1000
      r.copy(at = Some(at))
    case other => other
  }

  /** 祖玛教主：每秒看一次血，比上次召唤时掉了一档（20%）就召 6~11 只祖玛怪；回满血档位复原。 */
  private def summonZuma(w: World, m: Monster, z: ExtraState.SummonZuma): ExtraState =
    if w.now - z.lastCheck < 1000 then z
    else
      val hpLevel = m.hp * 5 / m.maxHp
      val level   = if m.hp >= m.maxHp then 5 else z.level
      if level > hpLevel && level > 0 && m.target.isDefined then
        m.minions.filterInPlace(id => w.actor(id).exists(!_.dead))
        val at = m.cell.step(m.dir)
        val n  = w.rngOf.random(6) + 6
        (0 until n).foreach { _ =>
          if m.minions.size < 30 then
            val kind = Behavior.zumaMinions(w.rngOf.random(Behavior.zumaMinions.size))
            w.catalog.monster(kind).flatMap(w.spawner.spawnMonster(_, None, at, 2)).foreach { c =>
              c.cover = Cover.Open
              c.target = m.target
              m.minions += c.id
            }
        }
        ExtraState.SummonZuma(level - 1, w.now)
      else ExtraState.SummonZuma(level, w.now)

  /** 沃玛教主 CowKingMonster.Run：每 30 秒检查一次。被围住（8 格里 5 格走不了）就瞬移到目标身后，否则按血量狂暴。 */
  private def cowKing(w: World, m: Monster, c: ExtraState.CowKing): ExtraState =
    if w.now - c.lastCheck < 30_000 then c
    else
      val jumped = m.target.flatMap(w.actor).filter(!_.dead).exists { t =>
        val blocked = Dir.values.count(d => !w.canEnter(m.cell.step(d)))
        blocked >= 5 && {
          val back = t.cell.step(t.dir.opposite)
          val to   = if w.canEnter(back) then Some(back) else w.freeCellNear(t.cell, 3)
          to.foreach(w.grid.teleportActor(m, _))
          to.isDefined
        }
      }
      if jumped then c.copy(lastCheck = w.now)
      else
        val level = 7 - m.hp / math.max(1, m.maxHp / 7)
        val (rage, rageAt) =
          if level >= 2 && level != c.level then advanceRage(w, m, Rage.Ready, w.now)
          else advanceRage(w, m, c.rage, c.rageAt)
        ExtraState.CowKing(level, rage, rageAt, w.now)

  /** 狂暴阶段往前走：准备 8 秒（几乎不出手）→ 狂暴 8 秒（攻速、移速变快；boss 平时已比 400 毫秒快的不减速）→ 平静（恢复平时速度）。 */
  private def advanceRage(w: World, m: Monster, rage: Rage, since: Millis): (Rage, Millis) = rage match
    case Rage.Ready if w.now - since < 8000 =>
      m.attackInterval = 10_000
      (Rage.Ready, since)
    case Rage.Ready => advanceRage(w, m, Rage.Crazy, w.now)
    case Rage.Crazy if w.now - since < 8000 =>
      m.attackInterval = 500
      m.walkInterval = math.min(400, m.baseWalkMs)
      (Rage.Crazy, since)
    case Rage.Crazy =>
      m.attackInterval = m.defn.attackSpeed
      m.walkInterval = m.baseWalkMs
      (Rage.Calm, since)
    case Rage.Calm => (Rage.Calm, since)

  /**
   * 神兽变身（TElfMonster.Run / TElfWarriorMonster.Run）：小神兽一有仗打就原地变大、从地里钻出；
   * 大神兽连续 Calm 那么久没仗打就钻下去，钻完变回小神兽。
   */
  private def shapeshift(w: World, m: Monster, s: ExtraState.Shapeshift): ExtraState =
    shinsuPace(m)
    val engaged = inFight(w, m)
    s.when match
      case ShiftWhen.Engaged =>
        if engaged then morph(w, m, s.into, emerge = true)
        s
      case ShiftWhen.Calm(_) if engaged => s.copy(engagedAt = w.now)
      case ShiftWhen.Calm(ms) if w.now - s.engagedAt > ms =>
        m.target = None
        val dur = digAnim(w, m, ActKind.DigDown)
        w.afterOn(m, dur)(morph(w, _, s.into, emerge = false))
        s.copy(engagedAt = w.now)
      case ShiftWhen.Calm(_) => s

  /**
   * 认了主的宝宝按宝宝等级定走路、出手间隔：召唤时、诱惑收服时、跟主人换图重新放出来时都调。
   * 召唤骷髅按 WhiteSkeleton 公式定走路（出手照数据表）；神兽在 shinsuPace 里每回合定；
   * 收服的按 petWalkCap / petAttackCap 压到上限以内。
   */
  def petPace(m: Monster): Unit = SpellRules.summonedAs(m.defn.name) match
    case Some(SpellRules.SkeletonPet) =>
      m.walkInterval = SpellRules.skeletonWalkMs(m.petLevel)
      m.attackInterval = SpellRules.skeletonHitMs(m.petLevel)
    case Some(_)                      => shinsuPace(m)
    case None =>
      m.walkInterval = math.min(m.defn.walkSpeed, SpellRules.petWalkCap(m.petLevel))
      m.attackInterval = math.min(m.defn.attackSpeed, SpellRules.petAttackCap(m.petLevel))

  /** 神兽按宝宝等级定走路、出手间隔（ResetElfMon）。 */
  private def shinsuPace(m: Monster): Unit =
    m.walkInterval = SpellRules.shinsuWalkMs(m.petLevel)
    m.attackInterval = SpellRules.shinsuHitMs(m.petLevel)

  /** 有没有仗打：自己有活着的目标，或主人正按住攻击打着谁、最近挨过别的玩家打（原版 Master.TargetCret / LastHiter）。 */
  private def inFight(w: World, m: Monster): Boolean =
    def alive(id: ActorId) = w.actor(id).exists(!_.dead)
    val mine = m.target.exists(alive)
    val masters = m.master.flatMap(w.player).exists { p =>
      p.intent.foe.exists(alive) || p.assailant.exists(g => w.now - g.at <= Grudge.Ms)
    }
    mine || masters

  /**
   * 原地变成 into 那种怪（MakeClone）：主人、宝宝等级、目标、身上状态都留着；召唤的按召唤加成和新形态底数重算身板，
   * 血量按比例换算；emerge 时放钻出动作。
   */
  private def morph(w: World, m: Monster, into: MonsterName, emerge: Boolean): Unit =
    w.catalog.monster(into).foreach { d =>
      given Rng = w.rngOf
      val ratio = m.hp.toDouble / math.max(1, m.maxHp)
      m.morph(d, Behavior.of(d))
      val body = m.summoning.fold(PetBody.of(d))(_.body(d))
      w.vitals.shape(m, body, Some(math.max(1, math.round(body.hp * ratio).toInt)))
      m.extras = m.behavior.extras.map(_.initial(w.now)).toVector
      shinsuPace(m)
      if emerge then
        digAnim(w, m, ActKind.DigUp): Unit
        w.out.sound(m.cell, Sounds.monster(d.appr, MonsterCry.Appear))
    }

  /** 死了的僵尸过几秒爬起来（ZilKinZombi.Run）：血上限减半，再打死经验也减半。 */
  private def tryRevive(w: World, m: Monster, r: ExtraState.Revive): ExtraState =
    r.at.filter(at => r.left > 0 && w.now >= at) match
      case None                         => r
      case Some(_) if !w.canEnter(m.cell) => r.copy(at = Some(w.now + 1000))
      case Some(_) =>
        w.vitals.setMaxHp(m, math.max(1, m.maxHpNow >> 1))
        w.vitals.reviveMonster(m)
        digAnim(w, m, ActKind.DigUp)
        ExtraState.Revive(r.left - 1, None, r.times + 1)

  // ---------------- 移动 ----------------

  /** 朝 t 走一格，走成了歇 pace 毫秒（默认自己的走路间隔）。 */
  def stepToward(w: World, m: Monster, t: Cell, pace: Int): Unit =
    if w.grid.move(m, m.cell.dirTo(t), run = false) then m.busyUntil = w.now + pace
    else
      Path.find(w.canEnter, m.cell, t, 200, adjacent = true).flatMap(_.headOption) match
        case Some(d) if w.grid.move(m, d, run = false) => m.busyUntil = w.now + pace
        case _                                    => m.busyUntil = w.now + 500

  def stepToward(w: World, m: Monster, t: Cell): Unit = stepToward(w, m, t, m.walkInterval)

  private def stepAway(w: World, m: Monster, t: Cell): Boolean =
    val away = m.cell.dirTo(t).opposite
    List(away, away.turn(1), away.turn(-1)).exists { d =>
      val ok = w.grid.move(m, d, run = false)
      if ok then m.busyUntil = w.now + m.walkInterval
      ok
    }
