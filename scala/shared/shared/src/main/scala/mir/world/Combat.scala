package mir.world

import mir.core.*
import mir.data.SkillDef
import mir.model.*
import mir.protocol.{Anchor, ServerMsg, Tone}
import mir.rules.{Durability, ItemKind, Rng, Rules, SpellRules}

/** 一个操作的结果：做了；在忙（冷却、上个动作没做完，不用提示）；不行（告诉玩家原因）。 */
enum Outcome:
  case Done
  case Busy
  case Refused(why: String)

/** 技能的目标：一格，和格上被选中的对象（可没有）。 */
final case class SpellTarget(cell: Cell, actor: Option[Actor])

/**
 * 玩家的攻击与技能结算。公式出处：ref/Mir176 PlayObject.Attack.cs（剑术）、MagicManager.cs（魔法）。
 * 照 TS 版 combat.ts 和 world.ts playerAttack 移植。
 */
object Combat:
  import World.*
  import Sounds.SpellPhase

  private def jsRound(x: Double): Int = math.floor(x + 0.5).toInt

  /** 被动技能：学会就生效，不占快捷键（基本剑术、精神力战法、攻杀剑术、刺杀剑术）。 */
  def isPassive(id: SkillId): Boolean = Set(3, 4, 7, 12).contains(id.value)

  // ---------------- 技能熟练度 ----------------

  /** 练技能：熟练度够了且人物等级够了就升级（最高 3 级）。 */
  def train(w: World, p: Player, id: SkillId, amount: Int): Unit =
    for
      i   <- Some(p.skills.indexWhere(_.id == id)).filter(_ >= 0)
      s    = p.skills(i)
      d   <- w.catalog.skill(id)
      if s.level < 3 && p.level >= d.needLevel(s.level)
    do
      val trained = s.train + amount
      if trained >= d.train(s.level) then
        p.skills(i) = s.copy(level = s.level + 1, train = 0)
        w.recalc(p)
        w.notice(p, s"${d.name} 升到 ${s.level + 1} 级", Tone.Good)
      else p.skills(i) = s.copy(train = trained)

  // ---------------- 普通攻击与剑术 ----------------

  /** 朝某方向普通攻击（含战士剑术）。早到 slack 毫秒以内也算。 */
  def attack(w: World, p: Player, dir: Dir, slack: Int = 0): Outcome =
    if p.dead then Outcome.Refused("你已经死了")
    else if w.now + slack < p.busyUntil then Outcome.Busy
    else
      w.face(p, dir)
      val target = w.occupant(p.cell.step(dir))
      def has(id: Int) = p.skill(SkillId(id)).isDefined
      val style =
        if p.fireReady && target.exists(w.isEnemy(p, _)) then HitStyle.Fire
        else if p.powerHitReady && has(7) then HitStyle.Power
        else if p.wideHitOn && has(25) && p.mp >= 3 then HitStyle.Wide
        else if p.longHitOn && has(12) then HitStyle.Long
        else HitStyle.Normal
      w.setAction(p, if style == HitStyle.Normal then ActKind.Attack else ActKind.Heavy, HitAnimMs, hit = Some(style))
      p.busyUntil = w.now + math.max(HitAnimMs, p.stats.hitInterval)
      w.sound(p.cell, Sounds.weaponSwing(w.weaponShape(p)))
      val styleSound = style match
        case HitStyle.Power  => Some(if p.sex == Sex.Female then Sounds.yedoWoman else Sounds.yedoMan)
        case HitStyle.Wide   => Some(Sounds.wideHit)
        case HitStyle.Fire   => Some(Sounds.fireHit)
        case HitStyle.Long   => Option.when(target.isDefined)(Sounds.longHit)
        case HitStyle.Normal => None
      styleSound.foreach(w.sound(p.cell, _))
      resolveHit(w, p, dir, style, target)
      if p.invisibleUntil > w.now then p.invisibleUntil = Millis.zero
      Outcome.Done

  private def physicalHit(w: World, p: Player, t: Actor, power: Int, ignoreArmor: Boolean)(using Rng): Boolean =
    if !w.isEnemy(p, t) then false
    else if !Rules.hits(p.stats.hit, t.agility) then
      w.miss(t)
      false
    else
      val dmg = if ignoreArmor then power else Rules.afterArmor(power, t.ac)
      if dmg <= 0 then
        w.miss(t)
        false
      else
        w.applyDamage(t, dmg, Some(p.id))
        true

  private def resolveHit(w: World, p: Player, dir: Dir, style: HitStyle, target: Option[Actor]): Unit =
    given rng: Rng = w.rngOf
    var power = Rules.meleePower(p.stats.dc, p.stats.luck)
    style match
      case HitStyle.Power =>
        // 攻杀：额外 + (5 + 技能等级)（HitPlus）
        power += 5 + p.skill(SkillId(7)).fold(0)(_.level)
        p.powerHitReady = false
      case HitStyle.Fire =>
        // 烈火：威力 ×(1 + (4 + 等级×4)/10)（HitDouble）
        power += jsRound(power / 100.0 * (4 + p.skill(SkillId(26)).fold(0)(_.level) * 4) * 10)
        p.fireReady = false
        if target.isDefined then train(w, p, SkillId(26), 1)
      case HitStyle.Wide => p.mp -= 3
      case _             => ()
    target.foreach(physicalHit(w, p, _, power, ignoreArmor = false))
    if style == HitStyle.Long then
      // 刺杀：隔一格的目标无视防御（AttackDirect）
      w.occupant(p.cell.step(dir, 2)).filter(w.isEnemy(p, _)).foreach { t2 =>
        physicalHit(w, p, t2, power, ignoreArmor = true)
        train(w, p, SkillId(12), 1)
      }
    if style == HitStyle.Wide then
      // 半月：身边一圈（除了正前方）都打到
      val pw = jsRound(power * (p.skill(SkillId(25)).fold(0)(_.level) + 1) / 4.0) + 1
      List(1, 2, 6, 7, 3, 5, 4).foreach { k =>
        w.occupant(p.cell.step(dir.turn(k))).filter(w.isEnemy(p, _)).foreach(physicalHit(w, p, _, pw, ignoreArmor = false))
      }
      train(w, p, SkillId(25), 1)
    // 练剑术、攻杀计数（PlayObject.Attack.cs:360、943）
    if target.exists(w.isEnemy(p, _)) then
      List(3, 4).map(SkillId(_)).filter(p.skill(_).isDefined).foreach(id => train(w, p, id, rng.random(3) + 1))
    val hasWeapon = p.worn.contains(EquipSlot.Weapon)
    p.skill(SkillId(7)).filter(_ => hasWeapon).foreach { ph =>
      if style == HitStyle.Power then train(w, p, SkillId(7), 1)
      if p.powerHitCount <= 0 then
        p.powerHitCount = 7 - ph.level
        p.powerHitPoint = rng.random(p.powerHitCount)
      p.powerHitCount -= 1
      if p.powerHitCount == p.powerHitPoint then p.powerHitReady = true
    }
    p.worn.get(EquipSlot.Weapon).foreach { wpn =>
      val wear = if rng.random(Durability.WeaponWearOdds) == 0 && target.isDefined then Durability.WeaponWearPerHit else 0
      if wear > 0 then p.worn(EquipSlot.Weapon) = wpn.copy(dura = math.max(0, wpn.dura - wear))
    }

  // ---------------- 魔法 ----------------

  /** 魔法耗蓝：spell/4×(等级+1) + defSpell/4×(等级+1)，取整为 0 时用 spell。 */
  def manaCost(d: SkillDef, level: Int): Int =
    val v = jsRound(d.spell / 4.0 * (level + 1) + d.defSpell / 4.0 * (level + 1))
    if v == 0 then d.spell else v

  /** 施法动画：在人物身上播放的起手特效（MagicEff.pas EffectBase，按技能的 Effect 编号；图库、起始帧、帧数、每帧毫秒）。
   *  EffectBase 为 940 的（疾光电影、灵魂火符、幽灵盾、神圣战甲术、集体隐身术）原版没有起手特效；心灵启示 20 帧、帧时减半（HumActor.pas）。 */
  private val castBase: Map[Int, (String, Int, Int, Int)] = Map(
    1 -> ("magic", 0, 10, 60), 2 -> ("magic", 200, 10, 60), 3 -> ("magic", 400, 10, 60), 4 -> ("magic", 600, 10, 60),
    6 -> ("magic", 900, 10, 60), 7 -> ("magic", 920, 10, 60), 9 -> ("magic2", 20, 3, 60), 14 -> ("magic", 1380, 10, 60),
    15 -> ("magic", 1500, 10, 60), 16 -> ("magic", 1520, 10, 60), 18 -> ("magic", 1560, 10, 60), 19 -> ("magic", 1590, 10, 60),
    20 -> ("magic", 1620, 10, 60), 21 -> ("magic", 1650, 10, 60), 22 -> ("magic", 1680, 10, 60), 26 -> ("magic", 3960, 20, 30),
    27 -> ("magic", 1790, 10, 60), 29 -> ("magic", 3880, 10, 60), 30 -> ("magic", 3920, 10, 60), 31 -> ("magic", 3840, 10, 60)
  )

  private def hitMagic(w: World, p: Player, t: Actor, power: Int): Unit =
    given Rng = w.rngOf
    if w.actor(t.id).contains(t) && w.isEnemy(p, t) then
      val dmg = Rules.afterMagicArmor(power, t.mac)
      if dmg > 0 then w.applyDamage(t, dmg, Some(p.id)) else w.miss(t)

  private def enemiesAround(w: World, p: Player, c: Cell, r: Int): Vector[Actor] =
    w.actorsNow.filter(a => w.isEnemy(p, a) && a.cell.chebyshev(c) <= r)

  /** 左手镯位上的护符：None 没戴；Some(None) 护身符；Some(Some(毒)) 灰/黄药粉。 */
  private def amulet(w: World, p: Player): Option[Option[PoisonKind]] =
    p.worn.get(EquipSlot.BraceletL).flatMap(it => w.catalog.item(it.name)).map(ItemKind.of).collect { case ItemKind.Amulet(k) => k }

  /** 用掉一张护身符/一份毒药。 */
  private def consumeAmulet(w: World, p: Player): Unit =
    p.worn.get(EquipSlot.BraceletL).foreach { it =>
      val left = it.dura - Durability.PerUse
      if left <= 0 then
        p.worn.remove(EquipSlot.BraceletL)
        w.notice(p, s"${it.name.value} 用完了", Tone.Bad)
      else p.worn(EquipSlot.BraceletL) = it.copy(dura = left)
    }

  private def needCharm(w: World, p: Player): Option[String] =
    Option.unless(amulet(w, p).contains(None))("左手镯位要装备护身符")

  /** 放技能。成功则扣蓝、进入施法动作、加熟练度。早到 slack 毫秒以内也算。 */
  def cast(w: World, p: Player, skillId: SkillId, tgt: SpellTarget, slack: Int = 0): Outcome =
    given rng: Rng = w.rngOf
    (p.skill(skillId), w.catalog.skill(skillId)) match
      case (Some(s), Some(d)) =>
        if p.dead then Outcome.Refused("你已经死了")
        else if w.now + slack < p.busyUntil || p.cooldown.get(skillId).exists(_ > w.now) then Outcome.Busy
        else
          val cost = manaCost(d, s.level)
          if p.mp < cost then Outcome.Refused("魔法值不够")
          else
            val t = tgt.actor.filter(a => !a.dead && w.actor(a.id).contains(a))
            t.map(_.cell).orElse(Option.when(tgt.cell != p.cell)(tgt.cell)).foreach(c => w.face(p, p.cell.dirTo(c)))
            spell(w, p, s, d, t, tgt.cell, cost) match
              case Effect.Cast =>
                p.mp -= cost
                w.sound(p.cell, Sounds.magic(skillId.value, SpellPhase.Start))
                castBase.get(d.effect).foreach((lib, idx, n, ft) => w.effect(lib, idx, n, ft, Anchor.OnActor(p.id)))
                w.setAction(p, ActKind.Spell, SpellAnimMs, spellEffect = Some(d.effect))
                p.busyUntil = w.now + SpellInterval
                if d.delay > 0 then p.cooldown(skillId) = w.now + math.max(SpellInterval, d.delay)
                train(w, p, skillId, 1 + rng.random(3))
                if p.invisibleUntil > w.now && skillId.value != 18 then p.invisibleUntil = Millis.zero
                Outcome.Done
              case Effect.Instant => Outcome.Done
              case Effect.Fail(why) => Outcome.Refused(why)
      case _ => Outcome.Refused("还没学会")

  /** 技能本身做了什么：要进施法动作的；不进施法动作的开关、蓄力；不能放的原因。 */
  private enum Effect:
    case Cast, Instant
    case Fail(why: String)

  private def spell(w: World, p: Player, s: LearnedSkill, d: SkillDef, t: Option[Actor], cell: Cell, cost: Int)(using rng: Rng): Effect =
    val mc = p.stats.mc
    val sc = p.stats.sc
    val lv = s.level
    def mcPower(): Int = SpellRules.mcDamage(d, lv, mc)
    def run(e: Either[Effect, Unit]): Effect = e.fold(identity, _ => Effect.Cast)
    def inReach(c: Cell): Either[Effect, Unit] =
      Either.cond(c.chebyshev(p.cell) <= SpellRules.CastRange, (), Effect.Fail(s"太远了（技能最多放到 ${SpellRules.CastRange} 格远）"))
    def enemy: Either[Effect, Actor] =
      t.filter(w.isEnemy(p, _)).toRight(Effect.Fail("需要选中一个目标")).flatMap(a => inReach(a.cell).map(_ => a))
    def enemyMonster: Either[Effect, Monster] =
      enemy.flatMap { case m: Monster => Right(m); case _ => Left(Effect.Fail("需要选中一只怪")) }
    def charm: Either[Effect, Unit] = needCharm(w, p).map(Effect.Fail(_)).toLeft(())
    def fx(lib: String, start: Int, frames: Int, frameTime: Int, at: Anchor, from: Option[Cell] = None, duration: Option[Int] = None): Unit =
      w.effect(lib, start, frames, frameTime, at, from = from, duration = duration)
    /** 目标格：选中的对象所在格，没选就是点的格 */
    val aim = t.fold(cell)(_.cell)
    /** 朝目标的方向（目标就是脚下时用自己的朝向） */
    val aimDir = if aim == p.cell then p.dir else p.cell.dirTo(aim)
    val aimDir16 = if aim == p.cell then p.dir.code * 2 else p.cell.dir16(aim)
    /** 给自己人放的范围技能的中心：选中的是够得着的自己人就是它，否则是自己（客户端默认选中的是最近的怪） */
    val friendCenter = t.filter(a => isFriend(w, p, a) && a.cell.chebyshev(p.cell) <= SpellRules.CastRange).fold(p.cell)(_.cell)
    /** 符咒飞过去再在中心炸开（原版 TBujaukGroundEffect / TExploBujaukEffect：飞行 1160 起每方向 10 帧取 3 帧） */
    def charmFly(center: Cell, burst: Int, frames: Int, frameTime: Int): Unit =
      val delay = 80 * p.cell.chebyshev(center)
      if delay > 0 then fx("magic", 1160 + p.cell.dir16(center) * 10, 3, 60, Anchor.OnCell(center), from = Some(p.cell), duration = Some(delay))
      w.after(delay)(fx("magic", burst, frames, frameTime, Anchor.OnCell(center)))
    s.id.value match
      case id @ (1 | 5 | 11) => // 火球术、大火球、雷电术
        run(enemy.flatMap { t =>
          if !w.terrain.flyable(t.cell) then Left(Effect.Fail("被挡住了"))
          else
            val power = if id == 11 && isUndead(t) then SpellRules.vsUndead(mcPower()) else mcPower()
            val delay = if id == 11 then 100 else 80 * p.cell.chebyshev(t.cell)
            w.sound(p.cell, Sounds.magic(id, SpellPhase.Fly))
            w.after(delay) {
              w.sound(t.cell, Sounds.magic(id, SpellPhase.Burst))
              hitMagic(w, p, t, power)
            }
            if id == 11 then fx("magic2", 10, 5, 80, Anchor.OnActor(t.id))
            else
              val base = if id == 1 then 0 else 400
              fx("magic", base + 10 + p.cell.dir16(t.cell) * 10, 6, 50, Anchor.OnActor(t.id), from = Some(p.cell), duration = Some(delay))
              w.after(delay)(fx("magic", base + 170, 10, 50, Anchor.OnActor(t.id)))
            Right(())
        })
      case id @ (9 | 10) => // 地狱火（直线 5 格）、疾光电影（直线 8 格，打不死系 ×1.5）：MagPassThroughMagic
        val power  = mcPower()
        val length = if id == 9 then SpellRules.HellFireLength else SpellRules.LightningLength
        val cells  = (1 to length).map(p.cell.step(aimDir, _))
        if id == 9 then cells.foreach(c => fx("magic", 930, 6, 70, Anchor.OnCell(c)))
        else fx("magic", 970 + aimDir16 * 10, 6, 50, Anchor.OnActor(p.id)) // TLightingThunder：在施法者身上按 16 方向画光束
        cells.flatMap(w.occupant).distinctBy(_.id).filter(w.isEnemy(p, _)).foreach { a =>
          val pw = if id == 10 && isUndead(a) then SpellRules.vsUndead(power) else power
          w.after(100)(hitMagic(w, p, a, pw))
        }
        Effect.Cast
      case id @ (23 | 33) => // 爆裂火焰、冰咆哮：目标周围 1 格（MagBigExplosion）
        run(inReach(aim).map { _ =>
          val power = mcPower()
          val hit   = enemiesAround(w, p, aim, SpellRules.BlastRadius)
          if id == 23 then fx("magic", 1660, 20, 80, Anchor.OnCell(aim)) else fx("magic", 3850, 20, 80, Anchor.OnCell(aim))
          w.after(200)(hit.foreach(hitMagic(w, p, _, power)))
        })
      case 24 => // 地狱雷光：自己周围 2 格，不死系全额、其余十分之一（MagElecBlizzard）
        val power = mcPower()
        enemiesAround(w, p, p.cell, SpellRules.ThunderStormRadius).foreach { a =>
          val pw = SpellRules.thunderStorm(power, isUndead(a))
          w.after(200)(hitMagic(w, p, a, pw))
        }
        Effect.Cast
      case 22 => // 火墙：目标格和上下左右共 5 格烧一阵（MagMakeFireCross / FireBurnEvent）
        run(inReach(aim).map { _ =>
          val damage = mcPower()
          val secs   = SpellRules.fireWallSecs(d, lv, mc)
          val cells  = List(aim.step(Dir.Up), aim.step(Dir.Left), aim, aim.step(Dir.Right), aim.step(Dir.Down))
          cells.filter(w.terrain.walkable).foreach(c => w.addField(c, FieldEffect.Fire(p.id, damage), secs * 1000L))
        })
      case 8 => // 抗拒火环：推开身边比自己等级低的（MagPushArround）
        enemiesAround(w, p, p.cell, 1).foreach { a =>
          SpellRules.repelPush(p.level, levelOf(a), lv).foreach { n =>
            val dir = p.cell.dirTo(a.cell)
            (1 to n).takeWhile(_ => w.move(a, dir, run = false))
          }
        }
        fx("magic", 900, 6, 80, Anchor.OnActor(p.id))
        Effect.Cast
      case 21 => // 瞬息移动：成功时回到回城点那张图的随机位置（MagSaceMove → MapRandomMove(HomeMap)）
        if SpellRules.spaceMoveWorks(lv) then
          w.after(SpellAnimMs) {
            if w.actor(p.id).contains(p) && !p.dead then
              val home = p.home
              val dest =
                if home.map != w.mapId then Some(home) // 别的图的地形这里不知道：送到回城点，由那张图找空格
                else Iterator.fill(300)(Cell(rng.random(w.terrain.width), rng.random(w.terrain.height))).find(w.canEnter).map(Place(w.mapId, _))
              dest.foreach(w.transfer(p, _))
          }
        else w.notice(p, "瞬息移动失败", Tone.Bad)
        Effect.Cast
      case 31 => // 魔法盾
        p.shieldUntil = w.now + SpellRules.shieldSecs(d, lv, mc) * 1000L
        p.shieldLevel = lv
        w.notice(p, "魔法盾开启", Tone.System)
        Effect.Cast
      case 20 => // 诱惑之光（MagTamming）
        t match
          case Some(m: Monster) if m.master.contains(p.id) => Effect.Fail("已经是你的宝宝了")
          case Some(m: Monster) if w.isEnemy(p, m) =>
            val target = SpellRules.TameTarget(m.defn.level, m.maxHp, isUndead(m))
            if Behavior.isAnimal(m.defn) then Effect.Fail("鸡、鹿、羊、狼这类动物不能诱惑")
            else
              inReach(m.cell).flatMap(_ => SpellRules.tameBlocker(p.level, target, p.pets.size).map(Effect.Fail(_)).toLeft(())) match
                case Left(e) => e
                case Right(_) =>
                  fx("magic", 1570, 10, 80, Anchor.OnActor(m.id))
                  val name = w.catalog.displayName(m.defn.name).value
                  SpellRules.tame(p.level, lv, target) match
                    case SpellRules.TameOutcome.Tamed =>
                      makePet(w, p, m, lv)
                      w.notice(p, s"$name 成了你的宝宝", Tone.Good)
                    case SpellRules.TameOutcome.Seized(secs) =>
                      w.seize(m, secs)
                      w.notice(p, s"$name 被定住了 $secs 秒（挨打会醒），再试一次", Tone.System)
                    case SpellRules.TameOutcome.Crazy(secs) =>
                      w.enrage(m, secs)
                      w.notice(p, s"$name 发狂了，$secs 秒内见谁打谁", Tone.Bad)
                    case SpellRules.TameOutcome.Killed =>
                      w.applyDamage(m, m.hp, Some(p.id))
                      w.notice(p, s"$name 被诱惑之光打死了", Tone.System)
                    case SpellRules.TameOutcome.NoEffect =>
                      w.notice(p, s"诱惑 $name 没有成功", Tone.System)
                  Effect.Cast
          case _ => Effect.Fail("需要选中一只怪")
      case 2 => // 治愈术：选中的是自己人就治它，否则治自己
        val who = t.filter(a => isFriend(w, p, a) && a.cell.chebyshev(p.cell) <= SpellRules.CastRange).getOrElse(p)
        healOne(w, who, SpellRules.heal(d, lv, sc))
        fx("magic", 200, 10, 60, Anchor.OnActor(who.id))
        Effect.Cast
      case 29 => // 群体治疗术：中心周围 1 格的自己人（MagBigHealing）
        val heal = SpellRules.heal(d, lv, sc)
        friendsAround(w, p, friendCenter, SpellRules.GroupRadius).foreach(healOne(w, _, heal))
        fx("magic", 1800, 10, 80, Anchor.OnCell(friendCenter))
        Effect.Cast
      case 6 => // 施毒术：灰色药粉绿毒、黄色药粉红毒
        run(enemy.flatMap { t =>
          amulet(w, p).flatten.toRight(Effect.Fail("左手镯位要装备灰色或黄色药粉")).map { kind =>
            consumeAmulet(w, p)
            val secs = SpellRules.poisonSecs(d, lv, sc, green = kind == PoisonKind.Green)
            t.poison = Some(Poison(kind, w.now + secs * 1000L, SpellRules.poisonTick(sc), w.now + 2000, p.id))
            t match
              case m: Monster if m.target.isEmpty => m.target = Some(p.id)
              case _                              => ()
            fx("magic", 610, 10, 60, Anchor.OnActor(t.id))
          }
        })
      case 13 => // 灵魂火符：要护身符
        run(enemy.flatMap { t =>
          charm.map { _ =>
            consumeAmulet(w, p)
            val power = SpellRules.scDamage(d, lv, sc)
            val delay = 80 * p.cell.chebyshev(t.cell)
            fx("magic", 1160 + p.cell.dir16(t.cell) * 10, 3, 60, Anchor.OnActor(t.id), from = Some(p.cell), duration = Some(delay))
            w.sound(p.cell, Sounds.magic(13, SpellPhase.Fly))
            w.after(delay) {
              w.sound(t.cell, Sounds.magic(13, SpellPhase.Burst))
              hitMagic(w, p, t, power)
              fx("magic", 1320, 10, 50, Anchor.OnActor(t.id))
            }
          }
        })
      case id @ (14 | 15) => // 幽灵盾（魔防）、神圣战甲术（防御）：中心周围 3 格的自己人（MagMakeDefenceArea）
        run(charm.map { _ =>
          consumeAmulet(w, p)
          val secs = SpellRules.defenceUpSecs(d, lv, sc)
          friendsAround(w, p, friendCenter, SpellRules.DefenceRadius).foreach { a =>
            w.defenceUp(a, magic = id == 14, SpellRules.defenceUpAmount(levelOf(a)), secs)
          }
          charmFly(friendCenter, if id == 14 then 1320 else 1340, 16, 50)
        })
      case 16 => // 困魔咒：目标周围 1 格等级够低的怪定住，四周围一圈光（MagMakeHolyCurtain）
        run(inReach(aim).flatMap(_ => charm).map { _ =>
          consumeAmulet(w, p)
          val secs   = SpellRules.holyCurtainSecs(d, lv, sc)
          val caught = enemiesAround(w, p, aim, SpellRules.GroupRadius).collect {
            case m: Monster if SpellRules.holyCurtainCatches(p.level, m.defn.level) => m
          }
          caught.foreach(w.seize(_, secs))
          if caught.nonEmpty then
            List((-1, -2), (1, -2), (-2, -1), (2, -1), (-2, 1), (2, 1), (-1, 2), (1, 2)).foreach { (dx, dy) =>
              w.addField(Cell(aim.x + dx, aim.y + dy), FieldEffect.HolyCurtain, secs * 1000L)
            }
            w.notice(p, s"困住 ${caught.size} 只怪 $secs 秒（挨打会醒）", Tone.System)
          else w.notice(p, "没有困住怪（要比你低几级的怪才困得住）", Tone.System)
        })
      case id @ (17 | 30) => // 召唤骷髅、召唤神兽
        val name = MonsterName(if id == 17 then "变异骷髅" else "神兽")
        val have = p.pets.flatMap(w.actor).exists {
          case m: Monster => m.defn.name == name
          case _          => false
        }
        needCharm(w, p) match
          case Some(why) => Effect.Fail(why)
          case None if have => Effect.Fail(s"已经有${name.value}了")
          case None =>
            w.catalog.monster(name) match
              case None => Effect.Fail("召唤失败")
              case Some(md) =>
                consumeAmulet(w, p)
                w.spawnMonster(md, None, p.cell.step(p.dir), 1).foreach { m =>
                  val boost = 1 + lv * 0.35
                  m.master = Some(p.id)
                  m.petLevel = lv
                  m.maxHpNow = jsRound(md.hp * boost)
                  m.hp = m.maxHpNow
                  m.dc = mir.data.Span(jsRound(md.dc.lo * boost), jsRound(md.dc.hi * boost))
                  p.pets += m.id
                  fx("magic", 1500, 10, 60, Anchor.OnActor(m.id))
                }
                Effect.Cast
      case 18 => // 隐身术
        run(charm.map { _ =>
          consumeAmulet(w, p)
          makeInvisible(w, p, SpellRules.invisibleSecs(d, lv, sc))
        })
      case 19 => // 集体隐身术：中心周围 1 格还没隐身的自己人（MagMakeGroupTransparent）
        run(charm.map { _ =>
          consumeAmulet(w, p)
          val secs = SpellRules.invisibleSecs(d, lv, sc)
          friendsAround(w, p, friendCenter, SpellRules.GroupRadius).filter(_.invisibleUntil <= w.now).foreach(makeInvisible(w, _, secs))
          charmFly(friendCenter, 1540, 10, 60)
        })
      case 28 => // 心灵启示：看穿目标的血量（ShowHp）
        t.filter(a => !a.isInstanceOf[Npc]) match
          case None => Effect.Fail("需要选中一个目标")
          case Some(a) =>
            run(inReach(a.cell).map { _ =>
              fx("magic", 3990, 10, 80, Anchor.OnActor(a.id))
              val name = a match
                case m: Monster => w.catalog.displayName(m.defn.name).value
                case q: Player  => q.charName
                case _          => ""
              if SpellRules.showHpWorks(lv) then
                val secs = SpellRules.showHpSecs(d, lv, sc)
                w.notice(p, s"$name 血量 ${a.hp}/${a.maxHp}（看穿 $secs 秒）", Tone.System)
              else w.notice(p, "心灵启示失败", Tone.System)
            })
      case 32 => // 圣言术：有机会直接打死不死系（MagTurnUndead）
        run(enemyMonster.flatMap { m =>
          if !isUndead(m) then Left(Effect.Fail("圣言术只对不死系的怪有效"))
          else
            fx("magic", 3930, 16, 80, Anchor.OnActor(m.id))
            val name = w.catalog.displayName(m.defn.name).value
            if SpellRules.turnUndeadKills(p.level, m.defn.level, lv) then
              w.applyDamage(m, m.hp, Some(p.id))
              w.notice(p, s"圣言术超度了 $name", Tone.Good)
            else
              if m.target.isEmpty then m.target = Some(p.id)
              w.notice(p, s"圣言术没有超度 $name", Tone.System)
            Right(())
        })
      case 26 => // 烈火剑法：蓄力，下一刀生效（不进施法动作）
        if p.fireReady then Effect.Fail("烈火已经准备好了")
        else
          p.fireReady = true
          p.cooldown(SkillId(26)) = w.now + 10_000
          w.sound(p.cell, Sounds.fireHitReady)
          w.notice(p, "烈火剑法准备好了", Tone.System)
          p.mp -= cost
          Effect.Instant
      case 25 => // 半月弯刀：开关
        setSword(w, p, mir.protocol.SwordArt.WideHit, !p.wideHitOn)
        Effect.Instant
      case 12 => // 刺杀剑术：开关
        setSword(w, p, mir.protocol.SwordArt.LongHit, !p.longHitOn)
        Effect.Instant
      case _ => Effect.Fail("这个技能还没做")

  private def isUndead(a: Actor): Boolean = a match
    case m: Monster => m.defn.undead > 0
    case _          => false

  private def levelOf(a: Actor): Int = a match
    case m: Monster => m.defn.level
    case q: Player  => q.level
    case _: Npc     => 0

  /** 自己人：活着的玩家和宝宝（不做组队和 PK，所有玩家都算）。 */
  private def isFriend(w: World, p: Player, a: Actor): Boolean =
    !a.dead && !w.isEnemy(p, a) && (a match
      case _: Player  => true
      case m: Monster => m.master.isDefined
      case _: Npc     => false)

  private def friendsAround(w: World, p: Player, c: Cell, r: Int): Vector[Actor] =
    w.actorsNow.filter(a => isFriend(w, p, a) && a.cell.chebyshev(c) <= r)

  private def healOne(w: World, who: Actor, heal: Int): Unit =
    val v = math.max(0, math.min(heal, who.maxHp - who.hp))
    who.hp += v
    w.nearby(who.cell, ServerMsg.Heal(who.id, v))

  /** 隐身：盯着他的怪（看不穿隐身的）丢掉目标。 */
  private def makeInvisible(w: World, a: Actor, secs: Int): Unit =
    a.invisibleUntil = w.now + secs * 1000L
    w.monsters.filter(m => m.target.contains(a.id) && m.defn.coolEye == 0).foreach(_.target = None)
    a match
      case q: Player => w.notice(q, s"隐身 $secs 秒（打怪或被打会现身）", Tone.System)
      case _         => ()

  /** 诱惑成功：认主、不再算刷怪点的、解除定住发狂、按技能等级加快走路出手。 */
  private def makePet(w: World, p: Player, m: Monster, level: Int): Unit =
    m.master = Some(p.id)
    m.target = None
    m.spawn = None
    m.seizedUntil = Millis.zero
    m.crazyUntil = Millis.zero
    m.mood = Mood.Calm
    m.petLevel = level
    m.walkInterval = math.min(m.walkInterval, SpellRules.petWalkCap(level))
    m.attackInterval = math.min(m.attackInterval, SpellRules.petAttackCap(level))
    p.pets += m.id

  def setSword(w: World, p: Player, art: mir.protocol.SwordArt, on: Boolean): Unit = art match
    case mir.protocol.SwordArt.WideHit =>
      p.wideHitOn = on
      w.notice(p, if on then "开启半月弯刀" else "关闭半月弯刀", Tone.System)
    case mir.protocol.SwordArt.LongHit =>
      p.longHitOn = on
      w.notice(p, if on then "开启刺杀剑术" else "关闭刺杀剑术", Tone.System)
