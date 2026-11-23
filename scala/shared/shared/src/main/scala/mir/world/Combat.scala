package mir.world

import mir.core.*
import mir.data.SkillDef
import mir.model.*
import mir.protocol.{Anchor, ServerMsg, Tone}
import mir.rules.{ItemKind, Rng, Rules}

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
    val dc = p.stats.dc
    var power = Rules.attackPower(dc.lo, dc.hi - dc.lo)
    // 幸运：有机会直接打出攻击上限；诅咒：有机会只打出下限
    val luck = p.stats.luck
    if luck > 0 && rng.random(10 - math.min(9, luck)) == 0 then power = dc.hi
    else if luck < 0 && rng.random(10 - math.min(9, -luck)) == 0 then power = dc.lo
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
      val wear = if rng.random(3) == 0 && target.isDefined then 1 else 0
      if wear > 0 then p.worn(EquipSlot.Weapon) = wpn.copy(dura = math.max(0, wpn.dura - wear))
    }

  // ---------------- 魔法 ----------------

  /** 魔法耗蓝：spell/4×(等级+1) + defSpell/4×(等级+1)，取整为 0 时用 spell。 */
  def manaCost(d: SkillDef, level: Int): Int =
    val v = jsRound(d.spell / 4.0 * (level + 1) + d.defSpell / 4.0 * (level + 1))
    if v == 0 then d.spell else v

  /** 施法动画：在人物身上播放的起手特效（EffectBase，10 帧）。 */
  private val castBase: Map[Int, (String, Int, Int)] = Map(
    1 -> ("magic", 0, 10), 2 -> ("magic", 200, 10), 3 -> ("magic", 400, 10), 4 -> ("magic", 600, 10),
    6 -> ("magic", 900, 10), 7 -> ("magic", 920, 10), 9 -> ("magic2", 20, 3), 15 -> ("magic", 1500, 10),
    16 -> ("magic", 1520, 10), 18 -> ("magic", 1560, 10), 19 -> ("magic", 1590, 10), 29 -> ("magic", 3880, 10),
    31 -> ("magic", 3840, 10), 27 -> ("magic", 1790, 10)
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
      val left = it.dura - 100
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
                castBase.get(d.effect).foreach((lib, idx, n) => w.effect(lib, idx, n, 60, Anchor.OnActor(p.id)))
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
    def mcPower(): Int = Rules.attackPower(Rules.skillPower(Rules.magicBase(d.power), s.level, d.defPower) + mc.lo, mc.hi - mc.lo + 1)
    def enemy: Either[Effect, Actor] = t.filter(w.isEnemy(p, _)).toRight(Effect.Fail("需要选中一个目标"))
    def run(e: Either[Effect, Unit]): Effect = e.fold(identity, _ => Effect.Cast)
    def fx(lib: String, start: Int, frames: Int, frameTime: Int, at: Anchor, from: Option[Cell] = None, duration: Option[Int] = None): Unit =
      w.effect(lib, start, frames, frameTime, at, from = from, duration = duration)
    s.id.value match
      case id @ (1 | 5 | 11) => // 火球术、大火球、雷电术
        run(enemy.flatMap { t =>
          if !w.terrain.flyable(t.cell) then Left(Effect.Fail("被挡住了"))
          else
            val undead = t match
              case m: Monster => m.defn.undead > 0
              case _          => false
            val power = if id == 11 && undead then jsRound(mcPower() * 1.5) else mcPower()
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
      case 33 => // 冰咆哮：目标周围 1 格
        val c     = t.fold(cell)(_.cell)
        val power = mcPower()
        enemiesAround(w, p, c, 1).foreach(hitMagic(w, p, _, power))
        fx("magic", 3850, 20, 60, Anchor.OnCell(c))
        Effect.Cast
      case 8 => // 抗拒火环：推开身边等级不比自己高的怪
        enemiesAround(w, p, p.cell, 1).collect { case m: Monster if m.defn.level <= p.level => m }.foreach { m =>
          w.move(m, p.cell.dirTo(m.cell), run = false)
        }
        fx("magic", 900, 6, 80, Anchor.OnActor(p.id))
        Effect.Cast
      case 9 => // 地狱火：直线 5 格
        val power = mcPower()
        val dir   = p.cell.dirTo(t.fold(cell)(_.cell))
        (1 to 5).foreach { k =>
          val c = p.cell.step(dir, k)
          w.occupant(c).foreach(hitMagic(w, p, _, power))
          fx("magic", 930, 6, 70, Anchor.OnCell(c))
        }
        Effect.Cast
      case 31 => // 魔法盾
        p.shieldUntil = w.now + Rules.skillPower(15 + Rules.rpow(mc), s.level, d.defPower) * 1000L
        p.shieldLevel = s.level
        w.notice(p, "魔法盾开启", Tone.System)
        Effect.Cast
      case 20 => // 诱惑之光
        t.collect { case m: Monster if m.master.isEmpty => m } match
          case None => Effect.Fail("需要选中一只怪")
          case Some(m) =>
            val ok = m.defn.level < p.level - 1 + s.level && rng.random(4 - s.level + 1) == 0 && p.pets.size < 5 && m.defn.race != 0
            fx("magic", 1570, 10, 60, Anchor.OnActor(m.id))
            if ok then
              m.master = Some(p.id)
              m.target = None
              p.pets += m.id
              w.notice(p, s"${w.catalog.displayName(m.defn.name).value} 成了你的宝宝", Tone.Good)
            Effect.Cast
      case 2 => // 治愈术：选中的是自己人就治它，否则治自己
        val who  = t.filter(a => !w.isEnemy(p, a) && !a.isInstanceOf[Npc]).getOrElse(p)
        val heal = Rules.attackPower(Rules.skillPower(Rules.magicBase(d.power), s.level, d.defPower) + sc.lo * 2, (sc.hi - sc.lo) * 2 + 1)
        val v    = math.max(0, math.min(heal, who.maxHp - who.hp))
        who.hp += v
        w.nearby(who.cell, ServerMsg.Heal(who.id, v))
        fx("magic", 200, 10, 60, Anchor.OnActor(who.id))
        Effect.Cast
      case 6 => // 施毒术：灰色药粉绿毒、黄色药粉红毒
        run(enemy.flatMap { t =>
          amulet(w, p).flatten.toRight(Effect.Fail("左手镯位要装备灰色或黄色药粉")).map { kind =>
            consumeAmulet(w, p)
            val secs = Rules.skillPower13(if kind == PoisonKind.Green then 40 else 30, s.level, d.defPower) + Rules.rpow(sc) * 2
            t.poison = Some(Poison(kind, w.now + secs * 1000L, math.max(1, jsRound(Rules.rpow(sc) / 2.0) + 1), w.now + 2000, p.id))
            t match
              case m: Monster if m.target.isEmpty => m.target = Some(p.id)
              case _                              => ()
            fx("magic", 610, 10, 60, Anchor.OnActor(t.id))
          }
        })
      case 13 => // 灵魂火符：要护身符
        run(enemy.flatMap { t =>
          needCharm(w, p).map(Effect.Fail(_)).toLeft(()).map { _ =>
            consumeAmulet(w, p)
            val power = Rules.attackPower(Rules.skillPower(Rules.magicBase(d.power), s.level, d.defPower) + sc.lo, sc.hi - sc.lo + 1)
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
                  val boost = 1 + s.level * 0.35
                  m.master = Some(p.id)
                  m.petLevel = s.level
                  m.maxHpNow = jsRound(md.hp * boost)
                  m.hp = m.maxHpNow
                  m.dc = mir.data.Span(jsRound(md.dc.lo * boost), jsRound(md.dc.hi * boost))
                  p.pets += m.id
                  fx("magic", 1500, 10, 60, Anchor.OnActor(m.id))
                }
                Effect.Cast
      case 18 => // 隐身术
        needCharm(w, p) match
          case Some(why) => Effect.Fail(why)
          case None =>
            consumeAmulet(w, p)
            val secs = Rules.skillPower13(30, s.level, d.defPower) + Rules.rpow(sc) * 3
            p.invisibleUntil = w.now + secs * 1000L
            w.monsters.filter(m => m.target.contains(p.id) && m.defn.coolEye == 0).foreach(_.target = None)
            w.notice(p, s"隐身 $secs 秒（打怪或被打会现身）", Tone.System)
            Effect.Cast
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

  def setSword(w: World, p: Player, art: mir.protocol.SwordArt, on: Boolean): Unit = art match
    case mir.protocol.SwordArt.WideHit =>
      p.wideHitOn = on
      w.notice(p, if on then "开启半月弯刀" else "关闭半月弯刀", Tone.System)
    case mir.protocol.SwordArt.LongHit =>
      p.longHitOn = on
      w.notice(p, if on then "开启刺杀剑术" else "关闭刺杀剑术", Tone.System)
