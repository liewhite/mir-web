package mir.world

import mir.core.*
import mir.model.*
import mir.protocol.Anchor
import mir.rules.{Rng, Rules}

/**
 * 怪物每次思考做什么：按 Behavior 出招、藏身、附加本领、追人、闲逛。
 * 出处见 Behavior；照 TS 版 monster_ai.ts 移植，多人时「目标」可以是任何一个玩家或宝宝。
 */
object MonsterAi:
  import Sounds.MonsterCry

  /** 刚刷出来：按行为设好藏身状态、复活次数等。 */
  def init(w: World, m: Monster): Unit =
    given Rng = w.rngOf
    if Behavior.fixedDir(m.defn) then m.dir = Dir.Up
    m.behavior.hiding match
      case Hiding.Burrow(_, _) | Hiding.DigOut | Hiding.Centipede => m.cover = Cover.Buried
      case Hiding.Statue(_) =>
        m.cover = Cover.Stone
        if m.defn.raceImg == 49 then m.dir = Dir.DownLeft
      case Hiding.Never => m.cover = Cover.Open
    if m.behavior.extras.contains(Extra.Revive) && w.rngOf.random(3) == 0 then m.revivesLeft = w.rngOf.random(3) + 1
    m.bornAt = w.now

  def think(w: World, m: Monster): Unit =
    val rng = w.rngOf
    if w.now >= m.nextThink && w.now >= m.busyUntil then
      if m.dead then tryRevive(w, m)
      else
        m.nextThink = w.now + 100 + rng.random(100)
        if m.action.kind != ActKind.Stand && w.now >= m.action.startedAt + m.action.duration then w.setAction(m, ActKind.Stand, 0)
        val b = m.behavior
        if updateCover(w, m, b) then
          b.extras.foreach(extraTick(w, m, _))
          if !m.dead then
            b.strike match
              case Strike.Explode if w.now - m.bornAt > 60_000 => explode(w, m)
              case s: Strike.Spawn                           => spawnTick(w, m, s)
              case _                                         => hunt(w, m, b)

  private def hunt(w: World, m: Monster, b: Behavior): Unit =
    val master = m.master.flatMap(w.actor)
    val kept = m.target.flatMap(w.actor).filter { t =>
      !t.dead && w.isEnemy(m, t) && m.cell.chebyshev(t.cell) <= 14 && t.invisibleUntil <= w.now
    }
    val target = kept.orElse {
      if b.passive && master.isEmpty then None
      else
        val range = if m.isGuard then 6 else if master.isDefined then 8 else b.strike match
          case Strike.Area(r, _) => r
          case _                 => 7
        w.nearestEnemy(m, range)
    }
    m.target = target.map(_.id)
    target match
      case Some(t) =>
        if !attack(w, m, b, t) && !m.isGuard && !b.rooted then stepToward(w, m, t.cell)
      case None =>
        master match
          case Some(ms) => if m.cell.chebyshev(ms.cell) > 2 then stepToward(w, m, ms.cell)
          case None if m.isGuard || b.rooted => ()
          case None =>
            // 闲逛
            if w.rngOf.random(100) < 4 && w.move(m, Dir.fromCode(w.rngOf.random(8)), run = false) then
              m.busyUntil = w.now + m.defn.walkSpeed + w.rngOf.random(1000)

  // ---------------- 藏身 ----------------

  /** 先处理藏身状态。返回 false 表示这回合还藏着、不做别的。 */
  private def updateCover(w: World, m: Monster, b: Behavior): Boolean =
    def near(r: Int, strict: Boolean = false): Option[Actor] =
      w.nearestEnemy(m, r).filter(e => !strict || m.cell.chebyshev(e.cell) < r)
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
            if h == Hiding.Centipede then m.hp = m.maxHp
          }
        false
      case (_, Hiding.Burrow(_, reach)) =>
        val t = m.target.flatMap(w.actor).filter(t => !t.dead && m.cell.chebyshev(t.cell) <= reach)
        t.orElse(w.nearestEnemy(m, reach)) match
          case None =>
            burrow(w, m)
            false
          case Some(e) =>
            m.target = Some(e.id)
            true
      case (_, Hiding.Centipede) =>
        if w.now - m.coverAt < 3000 then false
        else if w.nearestEnemy(m, 5).isEmpty && w.now - m.coverAt > 10_000 then
          burrow(w, m)
          false
        else true
      case _ => true

  private def digAnim(w: World, m: Monster, kind: ActKind): Int =
    val info = w.actTable(m).map(t => if kind == ActKind.DigDown || !Behavior.herbLike(m.defn) then t.death else t.walk)
    val dur  = info.fold(600)(i => math.max(300, i.frames * i.frameTime))
    w.setAction(m, kind, dur)
    m.busyUntil = w.now + dur
    dur

  /** 钻出地面、石像醒来。 */
  private def surface(w: World, m: Monster): Unit =
    m.cover = Cover.Open
    m.coverAt = w.now
    digAnim(w, m, ActKind.DigUp)
    w.sound(m.cell, Sounds.monster(m.defn.appr, MonsterCry.Appear))

  private def burrow(w: World, m: Monster): Unit =
    m.target = None
    m.coverAt = w.now
    val dur = digAnim(w, m, ActKind.DigDown)
    w.after(dur) { if !m.dead then m.cover = Cover.Buried }

  // ---------------- 出招 ----------------

  private def power(w: World, m: Monster): Int = m.dc.lo + w.rngOf.random(math.max(0, m.dc.hi - m.dc.lo) + 1)

  private def damage(w: World, m: Monster, t: Actor, amount: Int): Unit =
    if amount > 0 then w.applyDamage(t, amount, Some(m.id)) else w.miss(t)

  private def physical(w: World, m: Monster, t: Actor, pw: Int, checkHit: Boolean = true): Unit =
    given Rng = w.rngOf
    t match
      case _: Npc => ()
      case _ =>
        if checkHit && !Rules.hits(m.hit, t.agility) then w.miss(t)
        else damage(w, m, t, Rules.afterArmor(pw, t.ac))

  private def magical(w: World, m: Monster, t: Actor, pw: Int): Unit =
    given Rng = w.rngOf
    t match
      case _: Npc => ()
      case _      => damage(w, m, t, Rules.afterMagicArmor(pw, t.mac))

  /** 攻击动作：按动作表放动画，播声音和特效。返回动画时长。 */
  private def swing(w: World, m: Monster, t: Option[Actor]): Int =
    if !Behavior.fixedDir(m.defn) then t.foreach(t => w.face(m, m.cell.dirTo(t.cell)))
    val info = w.actTable(m).map(tab => if m.defn.raceImg == 33 then tab.critical else tab.attack)
    val anim = info.fold(600)(i => i.frames * i.frameTime)
    w.setAction(m, ActKind.Attack, anim)
    m.busyUntil = w.now + anim
    m.lastAttack = w.now
    w.sound(m.cell, Sounds.monster(m.defn.appr, MonsterCry.Attack))
    attackFx(w, m, t, info.fold(6)(_.frames), info.fold(100)(_.frameTime))
    anim

  /** 攻击时身上的特效（原版 TGasKuDeGi.LoadSurface 等：图库、起始帧、方向间隔 10）。 */
  private final case class AttackFx(lib: String, base: Int, dir16: Boolean = false, extra: Int = 0, frames: Option[Int] = None)
  private val attackFxByImg: Map[Int, AttackFx] = Map(
    16 -> AttackFx("mon3", 1444), // 洞蛆毒气
    20 -> AttackFx("mon4", 1800, extra = 1), // 火焰沃玛
    21 -> AttackFx("mon4", 1900), // 沃玛教主
    40 -> AttackFx("mon5", 350, dir16 = true), // 僵尸电光
    49 -> AttackFx("mon7", 1680, frames = Some(9)), // 祖玛教主
    52 -> AttackFx("mon4", 3590), // 楔蛾
    53 -> AttackFx("mon3", 3590) // 粪虫
  )

  private def attackFx(w: World, m: Monster, t: Option[Actor], frames: Int, frameTime: Int): Unit =
    attackFxByImg.get(m.defn.raceImg).foreach { f =>
      val dir = (f.dir16, t) match
        case (true, Some(t)) => m.cell.dir16(t.cell)
        case (true, None)    => m.dir.code * 2
        case (false, _)      => m.dir.code
      val n = f.frames.getOrElse(frames + f.extra)
      w.effect(f.lib, f.base + dir * 10, n, frameTime, Anchor.OnActor(m.id))
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
          if !Behavior.fixedDir(m.defn) then w.face(m, m.cell.dirTo(t.cell))
          if ready(w, m) then
            b.strike match
              case Strike.Explode => explode(w, m)
              case s =>
                swing(w, m, Some(t))
                val pw = power(w, m)
                s match
                  case Strike.Melee => physical(w, m, t, pw)
                  case Strike.Magic => magical(w, m, t, pw)
                  case Strike.Split(ratio) =>
                    val ph = math.round(pw * ratio).toInt
                    if ph > 0 then physical(w, m, t, ph, checkHit = false)
                    magical(w, m, t, pw - ph)
                  case _ => gas(w, m, t, pw)
          true
      case Strike.Spit =>
        val dir = m.cell.dirTo(t.cell)
        if d > 2 || m.cell.step(dir, d) != t.cell then false
        else
          w.face(m, dir)
          if ready(w, m) then
            swing(w, m, Some(t))
            val pw = power(w, m)
            for k <- 1 to 2 do
              w.occupant(m.cell.step(dir, k)).foreach { a =>
                given Rng = rng
                if !a.isInstanceOf[Npc] && w.isEnemy(m, a) && Rules.hits(m.hit, a.agility) then
                  magical(w, m, a, pw)
                  if !a.dead && rng.random(20) == 0 then poisonGreen(w, a, m, 30_000, 1)
              }
          true
      case Strike.Missile(range, fly) =>
        if d < 1 || d > range then false
        else
          if ready(w, m) then
            swing(w, m, Some(t))
            val delay = d * 50 + 300
            fly.foreach { f =>
              w.effect(f.lib, f.base + m.cell.dir16(t.cell) * f.stride, f.frames, 60, Anchor.OnActor(t.id),
                from = Some(m.cell), blend = false, duration = Some(delay))
            }
            val pw = power(w, m)
            w.after(delay) { if !t.dead && !m.dead && w.actor(t.id).isDefined then physical(w, m, t, pw, checkHit = false) }
          true
      case Strike.Lightning =>
        if d <= 2 && rng.random(3) == 0 && stepAway(w, m, t.cell) then true
        else if d >= 6 then false
        else
          if ready(w, m) then
            swing(w, m, Some(t))
            val dir = m.cell.dirTo(t.cell)
            val pw  = power(w, m)
            (1 to 8).flatMap(k => w.occupant(m.cell.step(dir, k))).distinctBy(_.id).filter(w.isEnemy(m, _)).foreach(magical(w, m, _, pw))
          true
      case Strike.Area(range, poison) =>
        if d < range && ready(w, m) then
          swing(w, m, Some(t))
          val pw    = power(w, m)
          val delay = if poison then 600 else 200
          w.actorsNow.filter(a => w.isEnemy(m, a) && m.cell.chebyshev(a.cell) < range).foreach { a =>
            w.after(delay) {
              if !a.dead && !m.dead && w.actor(a.id).isDefined then
                magical(w, m, a, pw)
                if poison && !a.dead && rng.random(4) == 0 then
                  if rng.random(3) != 0 then poisonGreen(w, a, m, 60_000, 3) else paralyze(w, a, 5000)
            }
          }
        true // 不动的怪：够不着就等人走近
      case Strike.Spawn(_, _) => true

  private def gas(w: World, m: Monster, t: Actor, pw: Int): Unit =
    given Rng = w.rngOf
    if !Rules.hits(m.hit, t.agility) then w.miss(t)
    else
      magical(w, m, t, pw)
      if !t.dead && w.rngOf.random(20) == 0 then paralyze(w, t, 5000)

  private def poisonGreen(w: World, t: Actor, m: Monster, ms: Int, pw: Int): Unit =
    t.poison = Some(Poison(PoisonKind.Green, w.now + ms, pw, w.now + 2000, m.id))
    t match
      case p: Player => w.notice(p, "你中毒了", mir.protocol.Tone.Bad)
      case _         => ()

  /** 麻痹（原版 STONE 状态）：这段时间不能动。 */
  private def paralyze(w: World, t: Actor, ms: Int): Unit =
    val until = w.now + ms
    if t.paralyzedUntil < until then t.paralyzedUntil = until
    if t.busyUntil < t.paralyzedUntil then t.busyUntil = t.paralyzedUntil
    t match
      case p: Player => w.notice(p, s"你被麻痹了（${ms / 1000} 秒）", mir.protocol.Tone.Bad)
      case _         => ()

  private def explode(w: World, m: Monster): Unit =
    given Rng = w.rngOf
    val pw = power(w, m)
    w.actorsNow.filter(a => w.isEnemy(m, a) && m.cell.chebyshev(a.cell) <= 1 && !a.isInstanceOf[Npc]).foreach { a =>
      damage(w, m, a, Rules.afterArmor(pw / 2, a.ac) + Rules.afterMagicArmor(pw / 2, a.mac))
    }
    w.killWithoutReward(m)

  private def spawnTick(w: World, m: Monster, s: Strike.Spawn): Unit =
    m.minions.filterInPlace(id => w.actor(id).exists(!_.dead))
    if ready(w, m) then
      m.lastAttack = w.now
      w.nearestEnemy(m, 9).filter(_ => m.minions.size < 15).foreach { t =>
        swing(w, m, None)
        w.catalog.monster(s.child).foreach { d =>
          w.after(500) {
            if !m.dead then
              val at = if s.below then m.cell.step(Dir.Down) else m.cell
              w.spawnMonster(d, None, at, 1).foreach { c =>
                c.target = Some(t.id)
                m.minions += c.id
              }
          }
        }
      }

  // ---------------- 附加本领 ----------------

  private def extraTick(w: World, m: Monster, e: Extra): Unit = e match
    case Extra.Revive => ()
    case Extra.SummonZuma =>
      if w.now - m.extraTick >= 1000 then
        m.extraTick = w.now
        val level = m.hp * 5 / m.maxHp
        if m.hp >= m.maxHp then m.danger = 5
        if m.danger > level && m.danger > 0 && m.target.isDefined then
          m.danger -= 1
          m.minions.filterInPlace(id => w.actor(id).exists(!_.dead))
          val at = m.cell.step(m.dir)
          val n  = w.rngOf.random(6) + 6
          (0 until n).foreach { _ =>
            if m.minions.size < 30 then
              val kind = Behavior.zumaMinions(w.rngOf.random(Behavior.zumaMinions.size))
              w.catalog.monster(kind).flatMap(w.spawnMonster(_, None, at, 2)).foreach { c =>
                c.cover = Cover.Open
                c.target = m.target
                m.minions += c.id
              }
          }
    case Extra.CowKing => cowKing(w, m)

  /** 沃玛教主 CowKingMonster.Run：每 30 秒检查一次。 */
  private def cowKing(w: World, m: Monster): Unit =
    if w.now - m.extraTick >= 30_000 then
      m.extraTick = w.now
      val jumped = m.target.flatMap(w.actor).filter(!_.dead).exists { t =>
        val blocked = Dir.values.count(d => !w.canEnter(m.cell.step(d)))
        blocked >= 5 && {
          val back = t.cell.step(t.dir.opposite)
          val to   = if w.canEnter(back) then Some(back) else w.freeCellNear(t.cell, 3)
          to.foreach(w.teleportActor(m, _))
          to.isDefined
        }
      }
      if !jumped then
        val before = m.danger
        m.danger = 7 - m.hp / math.max(1, m.maxHp / 7)
        if m.danger >= 2 && m.danger != before then
          m.rage = Rage.Ready
          m.rageAt = w.now
        if m.rage == Rage.Ready then
          if w.now - m.rageAt < 8000 then m.attackInterval = 10_000
          else
            m.rage = Rage.Crazy
            m.rageAt = w.now
        if m.rage == Rage.Crazy then
          if w.now - m.rageAt < 8000 then
            m.attackInterval = 500
            m.walkInterval = 400
          else
            m.rage = Rage.Calm
            m.attackInterval = m.defn.attackSpeed
            m.walkInterval = m.defn.walkSpeed

  /** 死了的僵尸过几秒爬起来（ZilKinZombi.Run）。 */
  private def tryRevive(w: World, m: Monster): Unit =
    m.reviveAt.filter(at => m.revivesLeft > 0 && w.now >= at).foreach { _ =>
      if !w.canEnter(m.cell) then m.reviveAt = Some(w.now + 1000)
      else
        m.revivesLeft -= 1
        m.reviveAt = None
        m.maxHpNow = math.max(1, m.maxHpNow >> 1)
        m.hp = m.maxHpNow
        m.expScale /= 2
        w.reviveMonster(m)
        digAnim(w, m, ActKind.DigUp)
    }

  /** 怪死时：会复活的定好复活时间，尸体留到复活。 */
  def onDeath(w: World, m: Monster): Unit =
    if m.revivesLeft > 0 then
      val at = w.now + (w.rngOf.random(20) + 4) * 1000L
      m.reviveAt = Some(at)
      if m.removeAt < at + 1000 then m.removeAt = at + 1000

  // ---------------- 移动 ----------------

  def stepToward(w: World, m: Monster, t: Cell): Unit =
    if w.move(m, m.cell.dirTo(t), run = false) then m.busyUntil = w.now + m.walkInterval
    else
      Path.find(w.canEnter, m.cell, t, 200, adjacent = true).flatMap(_.headOption) match
        case Some(d) if w.move(m, d, run = false) => m.busyUntil = w.now + m.walkInterval
        case _                                    => m.busyUntil = w.now + 500

  private def stepAway(w: World, m: Monster, t: Cell): Boolean =
    val away = m.cell.dirTo(t).opposite
    List(away, away.turn(1), away.turn(-1)).exists { d =>
      val ok = w.move(m, d, run = false)
      if ok then m.busyUntil = w.now + m.walkInterval
      ok
    }
