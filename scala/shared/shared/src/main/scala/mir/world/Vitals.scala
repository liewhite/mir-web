package mir.world

import mir.core.*
import mir.data.DropDef
import mir.model.*
import mir.protocol.*
import mir.rules.{Charges, ItemEffects, ItemKind, Leveling, PassiveSkills, PetBody, Progress, RandomUpgrade, Rates, Recovery, Rng, Rules, Skill, SpellRules}

/**
 * 血、蓝与生死：一下攻击的结算、扣血、死亡（经验、掉落）、复活、回血回蓝、属性重算。
 * 对象的血量、蓝量、血上限只在这里改（别处调 heal / spendMp / damage / setMaxHp），死亡也只从这里发生。
 */
final class Vitals(w: World):
  import World.CorpseMs
  private given Rng = w.rngOf

  // ---------------- 挨打 ----------------

  /**
   * 一下攻击的完整结算（所有攻击都走这里）：是不是敌人 → 命中 → 扣防/魔防 → damage（红毒、魔法盾、扣血）。
   * by 是攻击方，要还在这张图上、活着、打得了目标（canAttack：敌人，或玩家亲手打守卫；范围攻击在调用方先按 isEnemy 筛）；
   * None 是没有攻击方的伤害（冲撞撞墙自己挨的），不看敌我。
   */
  private[world] def strike(by: Option[Actor], target: Actor, blow: Blow): StrikeResult =
    val reachable = !target.isInstanceOf[Npc] && !target.dead && w.present(target) &&
      by.forall(b => w.present(b) && w.canAttack(b, target))
    if reachable then by.foreach(b => provokeGuard(target, b.id))
    if !reachable then StrikeResult.Ignored
    else if blow.accuracy.exists(acc => !Rules.hits(acc, target.agility)) then
      miss(target)
      StrikeResult.Dodged
    else
      val dmg = blow.defence match
        case Defence.Armor      => Rules.afterArmor(blow.power, target.ac)
        case Defence.MagicArmor => Rules.afterMagicArmor(blow.power, target.mac)
        case Defence.Pierce     => blow.power
        case Defence.Blast      => Rules.afterArmor(blow.power / 2, target.ac) + Rules.afterMagicArmor(blow.power / 2, target.mac)
      if dmg <= 0 then
        miss(target)
        StrikeResult.Blocked
      else
        damage(target, dmg, by.map(_.id), interrupt = blow.staggers)
        StrikeResult.Wounded(dmg)

  /** 扣血：红毒加伤、魔法盾减伤、被打动作、死亡、经验。攻击走 strike；这里直接用的是不判敌我防御的（毒发、秒杀）。 */
  private[world] def damage(target: Actor, amount: Int, source: Option[ActorId], interrupt: Boolean = true, struck: Boolean = true): Unit =
    target match
      case _: Npc => ()
      case _ if target.dead => ()
      case _ =>
        // 中红毒的挨打按它的强度加深（绿毒自己掉的血不算挨打）
        val redPct   = target.statuses.find { case p: Status.Poisoned if p.poison == PoisonKind.Red => p.power }
        val poisoned = redPct.filter(_ => struck).fold(amount)(SpellRules.redPoisonDamage(amount, _))
        val shield    = target match
          case _: Player => target.statuses.find { case s: Status.Shielded => s.level }
          case _         => None
        val dmg = shield.fold(poisoned)(lv => math.max(1, math.round(poisoned * (1 - SpellRules.shieldAbsorb(lv))).toInt))
        target.hp -= dmg
        target.lastHitBy = source
        source.foreach(provokeGuard(target, _))
        val src = source.flatMap(w.actor)
        target match
          case m: Monster =>
            val shape = src.collect { case p: Player => p }.flatMap(weaponShape)
            w.out.sound(m.cell, Sounds.struckBody(shape))
            if m.hp > 0 then w.out.sound(m.cell, Sounds.monster(m.defn.appr, Sounds.MonsterCry.Struck))
          case p: Player if p.hp > 0 => w.out.sound(p.cell, if p.sex == Sex.Female then Sounds.womStruck else Sounds.manStruck)
          case _                     => ()
        w.out.nearby(target.cell, ServerMsg.Damage(target.id, dmg, source))
        if target.hp <= 0 then
          target.hp = 0
          kill(target, source)
        else
          target match
            case m: Monster if interrupt =>
              // 被定住的挨打就醒（AnimalObject 收到 RM_STRUCK 时 BreakHolySeizeMode）
              m.statuses.remove(StatusKind.Seized)
              val dur = w.actTable(m).fold(200)(t => math.max(200, t.struck.frames * t.struck.frameTime))
              if m.action.kind == ActKind.Stand || m.action.kind == ActKind.Struck then
                w.grid.setAction(m, ActKind.Struck, dur)
                if m.busyUntil < w.now + dur then m.busyUntil = w.now + dur
              if m.isGuard then { if m.target.isEmpty then m.target = source }
              else src.foreach(MonsterAi.struck(w, m, _))
            case p: Player if p.action.kind == ActKind.Stand => w.grid.setAction(p, ActKind.Struck, 210)
            case _                                           => ()

  /**
   * 打守卫就被它记仇（Hostility.isEnemy），打空、被防住也算：守卫敏捷 200、防御 100~200，大多数攻击打不动它，
   * 只认打出血的话用户要的「打它会还手」几乎看不到（#1205；原版只在打中时记 LastHiter）。
   */
  private def provokeGuard(target: Actor, by: ActorId): Unit = target match
    case m: Monster if m.isGuard && m.id != by =>
      m.grudge = Some(Grudge(by, w.now))
      if m.target.isEmpty then m.target = Some(by)
    case _ => ()

  private[world] def miss(t: Actor): Unit = w.out.nearby(t.cell, ServerMsg.Miss(t.id))

  /** 武器外观（挥刀、打中的声音） */
  private[world] def weaponShape(p: Player): Option[Int] =
    p.worn.get(EquipSlot.Weapon).flatMap(it => w.catalog.item(it.name)).map(_.shape)

  /** 绿毒每 PoisonTickMs 掉一次血（不算挨打：红毒不加深、不打断动作）。 */
  private[world] def tickPoison(a: Actor): Unit =
    a.statuses.find { case p: Status.Poisoned if p.poison == PoisonKind.Green => p }.filter(p => !a.dead && w.now >= p.nextTick).foreach { p =>
      a.statuses.revise(p.copy(nextTick = w.now + SpellRules.PoisonTickMs))
      damage(a, math.max(1, p.power), p.by, interrupt = false, struck = false)
    }

  // ---------------- 死与复活 ----------------

  private def kill(target: Actor, killer: Option[ActorId]): Unit =
    target.dead = true
    target.mending = None
    StatusKind.poisons.foreach(target.statuses.remove)
    w.grid.setAction(target, ActKind.Die, 1200)
    target match
      case m: Monster =>
        w.out.sound(m.cell, Sounds.monster(m.defn.appr, Sounds.MonsterCry.Die))
        w.grid.vacate(m)
        m.removeAt = w.now + CorpseMs
        val owner = killer.flatMap(w.actor).flatMap {
          case p: Player  => Some(p)
          case k: Monster => k.master.flatMap(w.player)
          case _: Npc     => None
        }
        owner.filter(_ => m.master.isEmpty).foreach { o =>
          gainExp(o, Rates.killExp(m.defn.exp, m.expScale))
          dropLoot(m)
        }
        m.master.flatMap(w.player).foreach(_.pets -= m.id)
        MonsterAi.onDeath(w, m)
      case p: Player =>
        w.out.sound(p.cell, if p.sex == Sex.Female then Sounds.womDie else Sounds.manDie)
        p.intent = Intent.Idle
        p.potions = Nil
        w.out.tell(p, ServerMsg.Died)
        w.out.notice(p, "你死了。点「回城复活」回到城里。", Tone.Bad)
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
    w.grid.occupy(m)

  /** 死了的玩家回城复活：血蓝回三分之一。 */
  private[world] def revivePlayer(p: Player): Unit =
    p.dead = false
    p.hp = math.max(1, p.stats.maxHp / 3)
    p.mp = p.stats.maxMp / 3
    w.grid.setAction(p, ActKind.Stand, 0)
    w.roster.transfer(p, p.home)

  // ---------------- 经验与掉落 ----------------

  private[world] def gainExp(p: Player, exp: Exp): Unit =
    if exp.value > 0 then
      w.out.notice(p, s"获得经验 ${exp.value}", Tone.Good)
      val before = p.level
      val after  = Leveling.gain(w.catalog, Progress(p.level, p.exp), exp)
      p.level = after.level
      p.exp = after.exp
      if after.level > before then
        recalc(p)
        p.hp = p.stats.maxHp
        p.mp = p.stats.maxMp
        (before + 1 to after.level).foreach { lv =>
          w.out.tell(p, ServerMsg.LevelUp(lv))
          w.out.notice(p, s"升级了！现在 $lv 级", Tone.Good)
        }

  /** 按爆率表掉落（概率按 Rates 换算；金币数量 amount/2 + Random(amount)；装备照原版可能是极品，见 RandomUpgrade）。 */
  private def dropLoot(m: Monster): Unit = w.catalog.drops(m.defn.name).foreach { (d: DropDef) =>
    given rng: Rng = w.rngOf
    val itemDef = if d.isGold then None else w.catalog.item(d.item)
    val roll    = rng.random(1_000_000) / 1_000_000.0
    if roll < Rates.dropChance(d, itemDef) then
      if d.isGold then
        val g = d.amount / 2 + rng.random(d.amount)
        if g > 0 then w.dropAt(m.cell, Loot.Coins(Gold(g)))
      else
        itemDef.foreach { id =>
          val n = math.max(1, d.amount)
          // 可叠的一次掉多个就掉成一叠（#1159）；护身符、药粉的数量是次数（#1213）
          if ItemKind.stacks(id) then w.dropAt(m.cell, Loot.Item(Charges.settle(id, ItemInstance.fresh(w.newUid(), id).copy(count = n))))
          else (1 to n).foreach(_ => w.dropAt(m.cell, Loot.Item(RandomUpgrade.monsterDrop(ItemInstance.fresh(w.newUid(), id), id))))
        }
  }

  // ---------------- 回血回蓝、血上限 ----------------

  /** 回血回蓝（不超过上限，死了不回）。返回实际回了多少血。 */
  private[world] def heal(a: Actor, hp: Int, mp: Int = 0): Int =
    if a.dead then 0
    else
      val h = math.max(0, math.min(hp, a.maxHp - a.hp))
      a.hp += h
      a match
        case p: Player => p.mp += math.max(0, math.min(mp, p.stats.maxMp - p.mp))
        case _         => ()
      h

  /** 花蓝（放技能、半月）：不会扣成负数。 */
  private[world] def spendMp(p: Player, n: Int): Unit = p.mp = math.max(0, p.mp - n)

  /** 换血上限（召唤的宝宝按等级加成、僵尸复活减半、换图带过来的宝宝）：血按 hp 给，默认满血。 */
  private[world] def setMaxHp(m: Monster, max: Int, hp: Option[Int] = None): Unit =
    m.maxHpNow = max
    m.hp = hp.fold(max)(math.min(_, max))

  /** 换一副身板（召唤加成、换图还原）；不给 hp 就是满血。 */
  private[world] def shape(m: Monster, b: PetBody, hp: Option[Int] = None): Unit =
    setMaxHp(m, b.hp, hp)
    m.dc = b.dc
    m.baseAc = b.ac
    m.baseMac = b.mac

  /** 进图时放上存档里的血蓝（重算属性后按上限截；死着下线的，回来时活着）。 */
  private[world] def restore(p: Player, hp: Hp, mp: Mp): Unit =
    p.hp = hp.value
    p.mp = mp.value
    recalc(p)
    if p.hp <= 0 then p.hp = math.max(1, p.stats.maxHp / 3)

  /** 喝下的普通药：分几跳慢慢回（PotionRules），到点的跳都补上。 */
  private[world] def drinkPotions(p: Player): Unit =
    @scala.annotation.tailrec def run(c: PotionCourse): Option[PotionCourse] =
      if c.next > w.now then Some(c)
      else
        heal(p, c.hpPerTick, c.mpPerTick): Unit
        c.advanced match
          case Some(rest) => run(rest)
          case None       => None
    if !p.dead then p.potions = p.potions.flatMap(run)

  /** 治愈术落到身上：加进待回治疗量（封顶见 SpellRules.addHealing）；本来没有的从现在起过一拍开始回。 */
  private[world] def mend(a: Actor, amount: Int): Unit =
    if !a.dead then
      a.mending = Some(a.mending match
        case Some(m) => m.copy(left = SpellRules.addHealing(m.left, amount))
        case None    => Mending(SpellRules.addHealing(0, amount), w.now + SpellRules.healingTickMs(levelOf(a)).toLong))

  /** 治愈术慢慢回：到点的拍都补上，每拍 SpellRules.HealingPerTick 点；回完、血满或死了就作废。 */
  private[world] def tickMending(a: Actor): Unit =
    @scala.annotation.tailrec def run(m: Mending): Option[Mending] =
      if m.next > w.now then Some(m)
      else
        val step = math.min(SpellRules.HealingPerTick, m.left)
        heal(a, step): Unit
        if m.left <= step || a.hp >= a.maxHp then None
        else run(Mending(m.left - step, m.next + SpellRules.healingTickMs(levelOf(a)).toLong))
    a.mending = if a.dead then None else a.mending.flatMap(run)

  private def levelOf(a: Actor): Int = a match
    case p: Player  => p.level
    case m: Monster => m.defn.level
    case _: Npc     => 0

  /** 自然回复（规则见 Recovery）。 */
  private[world] def regen(p: Player): Unit =
    if !p.dead && w.now - p.lastRegen >= Recovery.IntervalMs then
      p.lastRegen = w.now
      heal(p, Recovery.hpPerTick(p.stats), Recovery.mpPerTick(p.stats)): Unit

  /** 重算属性：等级 + 身上装备（持久用完的不算；护身符和毒药不加属性）+ 被动技能。血蓝按新上限截。 */
  private[world] def recalc(p: Player): Unit =
    val effects = p.worn.values.flatMap { it =>
      w.catalog.item(it.name).filter(d => it.dura > 0 && !ItemKind.of(d).isInstanceOf[ItemKind.Amulet]).map(ItemEffects.of(_, it))
    }
    def lv(s: Skill): Option[Int] = p.skill(s.id).map(_.level)
    val base = Rules.computeStats(p.job, p.level, effects, PassiveSkills(lv(Skill.OneSword), lv(Skill.SpiritSword), lv(Skill.PowerHit)))
    p.stats = base.copy(ac = p.buffedAc(base.ac), mac = p.buffedMac(base.mac))
    p.hp = math.min(p.hp, p.stats.maxHp)
    p.mp = math.min(p.mp, p.stats.maxMp)
