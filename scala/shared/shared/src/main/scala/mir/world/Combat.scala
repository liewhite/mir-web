package mir.world

import mir.core.*
import mir.model.*
import mir.protocol.{Anchor, Tone}
import mir.rules.{Durability, Pace, Rates, Rng, Rules, Skill, SwordRules}
import mir.world.skills.{Effect, SkillHandlers, SpellCtx}

/** 一个操作的结果：做了；在忙（冷却、上个动作没做完，不用提示）；不行（告诉玩家原因）。 */
enum Outcome:
  case Done
  case Busy
  case Refused(why: String)

/** 技能的目标：一格，和格上被选中的对象（可没有）。 */
final case class SpellTarget(cell: Cell, actor: Option[Actor])

/**
 * 玩家的攻击与技能结算。公式出处：ref/Mir176 PlayObject.Attack.cs（剑术）、MagicManager.cs（魔法）。
 * 各技能自己的施放效果在 mir.world.skills（按技能注册的处理器），这里只做共通的扣蓝、冷却、施法动作、熟练度。
 * 照 TS 版 combat.ts 和 world.ts playerAttack 移植。
 */
object Combat:
  import Sounds.SpellPhase

  /** 出手（攻击、放技能）就现身，记下时刻（自动隐身照它等一会儿再补，AutoPlay）。 */
  private def reveal(w: World, p: Player): Unit =
    p.statuses.remove(StatusKind.Invisible)
    p.revealedAt = Some(w.now)

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
      if trained >= Rates.trainNeed(d, s.level) then
        p.skills(i) = s.copy(level = s.level + 1, train = 0)
        w.vitals.recalc(p)
        w.out.notice(p, s"${d.name} 升到 ${s.level + 1} 级", Tone.Good)
      else p.skills(i) = s.copy(train = trained)

  // ---------------- 普通攻击与剑术 ----------------

  /** 朝某方向普通攻击（含战士剑术）。早到 slack 毫秒以内也算。 */
  def attack(w: World, p: Player, dir: Dir, slack: Int = 0): Outcome =
    if p.dead then Outcome.Refused("你已经死了")
    else if w.now + slack < p.busyUntil then Outcome.Busy
    else
      w.grid.face(p, dir)
      // 有人穿怪站在怪身上时一格叠着几个：先挑能打的
      val front  = w.occupants(p.cell.step(dir))
      val target = front.find(w.canAttack(p, _)).orElse(front.headOption)
      if target.isEmpty && w.quarry.facesRock(p, dir) then dig(w, p, dir)
      else swing(w, p, dir, target)
      reveal(w, p)
      Outcome.Done

  /** 拿鹤嘴锄朝矿壁挥（原版 CM_HEAVYHIT 挖矿：只出重击动作，不算剑术）。 */
  private def dig(w: World, p: Player, dir: Dir): Unit =
    w.grid.setAction(p, ActKind.Heavy, Pace.HitAnimMs)
    p.busyFor(w.now, Pace.hitBusyMs(p.stats.hitInterval))
    w.out.sound(p.cell, Sounds.weaponSwing(w.vitals.weaponShape(p)))
    w.quarry.dig(p, dir)

  private def swing(w: World, p: Player, dir: Dir, target: Option[Actor]): Unit =
    val style =
      if p.fireReady && target.exists(w.canAttack(p, _)) then HitStyle.Fire
      else if p.powerHitReady && p.knows(Skill.PowerHit) then HitStyle.Power
      else if p.knows(Skill.WideHit) && p.mp >= SwordRules.WideHitMp && (p.wideHitOn || smartWide(w, p)) then HitStyle.Wide
      else if p.longHitOn && p.knows(Skill.LongHit) then HitStyle.Long
      else HitStyle.Normal
    w.grid.setAction(p, if style == HitStyle.Normal then ActKind.Attack else ActKind.Heavy, Pace.HitAnimMs, hit = Some(style))
    p.busyFor(w.now, Pace.hitBusyMs(p.stats.hitInterval))
    w.out.sound(p.cell, Sounds.weaponSwing(w.vitals.weaponShape(p)))
    val styleSound = style match
      case HitStyle.Power  => Some(if p.sex == Sex.Female then Sounds.yedoWoman else Sounds.yedoMan)
      case HitStyle.Wide   => Some(Sounds.wideHit)
      case HitStyle.Fire   => Some(Sounds.fireHit)
      case HitStyle.Long   => Option.when(target.isDefined)(Sounds.longHit)
      case HitStyle.Normal => None
    styleSound.foreach(w.out.sound(p.cell, _))
    resolveHit(w, p, dir, style, target)

  /**
   * 智能半月（#1273，设置开关）：半月关着时，身边一圈 8 格里的野怪够 SwordRules.SmartWideHitMin 只就这一刀用半月。
   * 只数野怪（不数玩家、宝宝），免得 PK 规则下为了旁边的人自动出半月误伤。
   */
  private def smartWide(w: World, p: Player): Boolean =
    p.settings.smartWideHit && Dir.values.iterator
      .flatMap(d => w.occupants(p.cell.step(d)))
      .count {
        case m: Monster => m.master.isEmpty && w.isEnemy(p, m)
        case _          => false
      } >= SwordRules.SmartWideHitMin

  /** 一刀：判命中，扣防御（刺杀隔一格的那下不扣）。 */
  private def physicalHit(w: World, p: Player, t: Actor, power: Int, ignoreArmor: Boolean): Unit =
    w.vitals.strike(Some(p), t, Blow(if ignoreArmor then Defence.Pierce else Defence.Armor, power, accuracy = Some(p.stats.hit))): Unit

  private def resolveHit(w: World, p: Player, dir: Dir, style: HitStyle, target: Option[Actor]): Unit =
    given rng: Rng = w.rngOf
    var power = Rules.meleePower(p.stats.dc, p.stats.luck)
    style match
      case HitStyle.Power =>
        power = SwordRules.powerHitPower(power, p.skillLevel(Skill.PowerHit))
        p.powerHitReady = false
      case HitStyle.Fire =>
        power = SwordRules.firePower(power, p.skillLevel(Skill.FireSword))
        p.fireReady = false
        if target.isDefined then train(w, p, Skill.FireSword.id, 1)
      case HitStyle.Wide => w.vitals.spendMp(p, SwordRules.WideHitMp)
      case _             => ()
    target.foreach(physicalHit(w, p, _, power, ignoreArmor = false))
    if style == HitStyle.Long then
      // 刺杀：隔一格的目标威力按等级打折、不扣防御（SkillAttackDamage + AttackDirect）
      val lp = SwordRules.longPower(power, p.skillLevel(Skill.LongHit))
      w.occupants(p.cell.step(dir, 2)).filter(w.harms(p, _)).foreach { t2 =>
        if lp > 0 then physicalHit(w, p, t2, lp, ignoreArmor = true)
        train(w, p, Skill.LongHit.id, 1)
      }
    if style == HitStyle.Wide then
      // 半月：身边一圈（除了正前方）都打到
      val pw = SwordRules.widePower(power, p.skillLevel(Skill.WideHit))
      List(1, 2, 6, 7, 3, 5, 4).foreach { k =>
        w.occupants(p.cell.step(dir.turn(k))).filter(w.harms(p, _)).foreach(physicalHit(w, p, _, pw, ignoreArmor = false))
      }
      train(w, p, Skill.WideHit.id, 1)
    // 练剑术、攻杀计数（PlayObject.Attack.cs:360、943）
    if target.exists(w.canAttack(p, _)) then
      List(Skill.OneSword, Skill.SpiritSword).filter(p.knows).foreach(s => train(w, p, s.id, rng.random(3) + 1))
    val hasWeapon = p.worn.contains(EquipSlot.Weapon)
    p.skill(Skill.PowerHit.id).filter(_ => hasWeapon).foreach { ph =>
      if style == HitStyle.Power then train(w, p, Skill.PowerHit.id, 1)
      if p.powerHitCount <= 0 then
        p.powerHitCount = SwordRules.powerHitEvery(ph.level)
        p.powerHitPoint = rng.random(p.powerHitCount)
      p.powerHitCount -= 1
      if p.powerHitCount == p.powerHitPoint then p.powerHitReady = true
    }
    p.worn.get(EquipSlot.Weapon).foreach { wpn =>
      val strength = wpn.bonus.collect { case b: ItemBonus.Weapon => b.strength }.getOrElse(0)
      val wear     = if target.isDefined && Durability.weaponWears(strength) then Durability.WeaponWearPerHit else 0
      if wear > 0 then p.worn(EquipSlot.Weapon) = wpn.copy(dura = math.max(0, wpn.dura - wear))
    }

  // ---------------- 技能 ----------------

  /** 放技能：查表找到技能的处理器施放；成功则按技能的用法扣蓝、冷却、进施法动作、加熟练度。早到 slack 毫秒以内也算。 */
  def cast(w: World, p: Player, skillId: SkillId, tgt: SpellTarget, slack: Int = 0): Outcome =
    given rng: Rng = w.rngOf
    (p.skill(skillId), w.catalog.skill(skillId)) match
      case (Some(s), Some(d)) =>
        Skill.of(skillId) match
          case None => Outcome.Refused("这个技能还没做")
          case Some(skill) =>
            if p.dead then Outcome.Refused("你已经死了")
            // 冷却和「忙到」一样容忍早到 slack 毫秒，下一次冷却从原来的到期时刻接着算，提前量不累积（#1321）
            else if w.now + slack < p.busyUntil || p.cooldown.get(skillId).exists(_ > w.now + slack) then Outcome.Busy
            else
              val cost = Skill.manaCost(d, s.level)
              if p.mp < cost then Outcome.Refused("魔法值不够")
              else
                val t = tgt.actor.filter(a => !a.dead && w.actor(a.id).contains(a))
                t.map(_.cell).orElse(Option.when(tgt.cell != p.cell)(tgt.cell)).foreach(c => w.grid.face(p, p.cell.dirTo(c)))
                val handler = SkillHandlers.of(skill)
                def paid(): Unit =
                  if skill.costsMana then w.vitals.spendMp(p, cost)
                  skill.cooldownMs(d).foreach { ms =>
                    val from = p.cooldown.get(skillId).filter(_ > w.now).getOrElse(w.now)
                    p.cooldown(skillId) = from + ms
                  }
                handler.cast(SpellCtx(w, p, skill, s, d, t, tgt.cell)) match
                  case e @ (Effect.Cast | Effect.Spent) =>
                    paid()
                    w.out.sound(p.cell, Sounds.magic(skillId, SpellPhase.Start))
                    handler.castFx.foreach(f => w.out.effect(f.lib, f.start, f.frames, f.frameTime, Anchor.OnActor(p.id)))
                    w.grid.setAction(p, ActKind.Spell, Pace.SpellAnimMs, spellEffect = Some(d.effect))
                    p.busyFor(w.now, Pace.SpellIntervalMs)
                    if e == Effect.Cast then train(w, p, skillId, 1 + rng.random(3))
                    if !handler.keepsInvisible then reveal(w, p)
                    Outcome.Done
                  case Effect.Acted(trained) =>
                    paid()
                    if trained then train(w, p, skillId, 1 + rng.random(3))
                    reveal(w, p)
                    Outcome.Done
                  case Effect.Instant =>
                    paid()
                    Outcome.Done
                  case Effect.Fail(why) => Outcome.Refused(why)
      case _ => Outcome.Refused("还没学会")

  def setSword(w: World, p: Player, art: mir.protocol.SwordArt, on: Boolean): Unit = art match
    case mir.protocol.SwordArt.WideHit =>
      p.wideHitOn = on
      w.out.notice(p, if on then "开启半月弯刀" else "关闭半月弯刀", Tone.System)
    case mir.protocol.SwordArt.LongHit =>
      p.longHitOn = on
      w.out.notice(p, if on then "开启刺杀剑术" else "关闭刺杀剑术", Tone.System)
