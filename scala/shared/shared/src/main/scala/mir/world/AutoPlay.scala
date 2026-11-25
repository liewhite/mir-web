package mir.world

import mir.core.*
import mir.model.{CharSettings, Cover, PickupKind}
import mir.rules.{AutoRules, GearTiers, ItemKind, Skill, SpellRules, Upgrade}
import mir.terrain.{MeleeReach, Path, Reach}

/**
 * 玩家的自动功能，按角色设置（CharSettings）在服务端执行：自动喝药、按住攻击（打到死）、被打还手、自动打怪、自动烈火、自动捡物，
 * 以及自动放的辅助技能（AutoCast：法师魔法盾，道士治愈、战甲、幽灵盾、召唤、隐身）。
 * 照 TS 版 game/controller.ts 移植。玩家亲手走动后 1 秒内不自动走动，免得和摇杆抢。
 */
object AutoPlay:
  import AutoRules.{CastRetryMs, HealBelowPercent, HealRetryMs, ReHideAfterMs}

  def run(w: World, p: Player): Unit =
    if !p.dead then
      autoPotion(w, p)
      if w.now >= p.busyUntil then autoCast(w, p)
      if w.now >= p.busyUntil then
        p.intent match
          case Intent.Hunt(target, once) => hunt(w, p, target, once)
          case Intent.Fetch(item)        => fetch(w, p, item)
          case Intent.Idle               => idle(w, p)

  private def free(w: World, p: Player): Boolean = w.now >= p.manualUntil

  private def hunt(w: World, p: Player, id: ActorId, once: Boolean): Unit =
    w.actor(id).filter(t => !t.dead && w.canAttack(p, t)) match
      case None => p.intent = Intent.Idle
      case Some(t) =>
        // 贴身，或开着刺杀时隔一格直线，就地出刀；都不是才走（#1264）
        val reach = Reach.of(p.longHitOn, p.knows(Skill.LongHit))
        if MeleeReach.canStrike(reach, p.cell, t.cell, w.terrain.walkable) then
          maybeFire(w, p)
          Combat.attack(w, p, p.cell.dirTo(t.cell))
          if once then p.intent = Intent.Idle
        else if free(w, p) then
          walk(w, p, t.cell, MeleeReach.path(reach, w.walkableFor(p), w.terrain.walkable, p.cell, t.cell, PathNodes))

  private def fetch(w: World, p: Player, id: GroundId): Unit =
    w.groundItem(id) match
      case None => p.intent = Intent.Idle
      case Some(g) if g.cell == p.cell =>
        // 同一格常叠着别的掉落，只捡勾了的种类（#1235）
        PlayerOps.pickUp(w, p, worth(w, p, _))
        p.intent = Intent.Idle
      case Some(g) => if free(w, p) then walk(w, p, g.cell, Path.find(w.walkableFor(p), p.cell, g.cell, PathNodes, adjacent = false))

  private def idle(w: World, p: Player): Unit =
    if free(w, p) then
      // 被打自动还手
      val hitter = p.lastHitBy.flatMap(w.actor).filter(a => p.settings.autoFightBack && !a.dead && w.isEnemy(p, a) && p.cell.chebyshev(a.cell) <= 3)
      p.lastHitBy = None
      // 先还手，再捡东西，最后才自己找怪打
      p.intent = hitter.map(a => Intent.Hunt(a.id, once = false))
        .orElse(Option.when(p.settings.autoPickup)(nearestLoot(w, p, 6)).flatten.map(g => Intent.Fetch(g.id)))
        .orElse(Option.when(p.settings.autoHunt)(nearestPrey(w, p)).flatten.map(m => Intent.Hunt(m.id, once = false)))
        .getOrElse(Intent.Idle)

  /** 自动打怪一次最多试几只怪的路（由近到远），免得周围怪多又都走不到时每一步算一堆路 */
  private val HuntTries = 3

  /**
   * 自动打怪（#1307）挑的怪：附近看得见的野怪（不是宝宝、没钻地或变石像），不归别人（别人正在打的不抢，队友的可以），
   * 走得到身边；取最近的。
   */
  private def nearestPrey(w: World, p: Player): Option[Monster] =
    val reach = Reach.of(p.longHitOn, p.knows(Skill.LongHit))
    w.monsters.iterator
      .filter(m => !m.dead && m.master.isEmpty && m.cover == Cover.Open && w.isEnemy(p, m) && p.cell.chebyshev(m.cell) <= AutoRules.HuntRadius)
      .filter(m => w.vitals.claimant(m).forall(o => w.allied(p, o)))
      .toVector.sortBy(m => p.cell.chebyshev(m.cell)).iterator.take(HuntTries)
      .find(m => MeleeReach.canStrike(reach, p.cell, m.cell, w.terrain.walkable) ||
        MeleeReach.path(reach, w.walkableFor(p), w.terrain.walkable, p.cell, m.cell, PathNodes).isDefined)

  private val PathNodes = 3000

  /** 沿算好的路朝 target 走一步；走不了就转过去等一下。 */
  private def walk(w: World, p: Player, target: Cell, path: Option[List[Dir]]): Unit =
    val moved = path.flatMap(_.headOption).exists(w.grid.move(p, _, run = false))
    if !moved then
      w.grid.face(p, p.cell.dirTo(target))
      p.busyUntil = w.now + 200

  private def maybeFire(w: World, p: Player): Unit =
    val id = Skill.FireSword.id
    if p.settings.autoFireSword && !p.fireReady && p.skill(id).isDefined && !p.cooldown.get(id).exists(_ > w.now) then
      Combat.cast(w, p, id, SpellTarget(p.cell, None)) match
        case _ => ()

  /** 附近最近的、设置里勾了这一类、包裹放得下、不在别人保护期内的地上物品（#1178、#1158）。 */
  private def nearestLoot(w: World, p: Player, radius: Int): Option[GroundItem] =
    w.groundItems.filter { g =>
      // 有别人站着的格子捡不了
      worth(w, p, g.loot) && PlayerOps.mayPick(w, p, g) && p.cell.chebyshev(g.cell) <= radius && w.occupant(g.cell).forall(_.id == p.id)
    }.minByOption(g => p.cell.chebyshev(g.cell))

  /**
   * 自动捡物要不要这件：设置里勾了这一类，且包裹放得下。装备还要过档位门槛（#1301），
   * 但比身上同部位那件强的（地上名字粉紫的那些，Upgrade.gain）不管什么档都捡（用户 11-25 定）。
   */
  private def worth(w: World, p: Player, loot: Loot): Boolean =
    val s = p.settings
    loot match
      case Loot.Coins(_) => s.pickupKinds(PickupKind.Gold)
      case Loot.Item(it) =>
        w.catalog.item(it.name).exists { d =>
          val kind = ItemKind.pickupKind(d)
          def upgrade = Upgrade.gain(Upgrade.Wearer(p.job, p.sex, p.worn.toMap), d, it.bonus, w.catalog.item).isDefined
          s.pickupKinds(kind) && (kind != PickupKind.Equip || GearTiers.passes(d.name, s.pickupTier) || upgrade) && PlayerOps.fits(p, d).isRight
        }

  /**
   * 一个自动技能：哪个设置开关管它、放不出来隔多久再试、现在要不要放、放给谁（aim 返回 None 就是不用放）。
   * 技能照常走 Combat.cast：耗蓝、护身符、施法间隔和手动放一样；没学会就跳过。
   */
  private final case class AutoCast(skill: Skill, on: CharSettings => Boolean, retryMs: Int)(val aim: (World, Player) => Option[SpellTarget])

  private def onSelf(p: Player): SpellTarget = SpellTarget(p.cell, None)

  /** 自己身上没有这个状态就给自己放 */
  private def keepUp(kind: StatusKind)(w: World, p: Player): Option[SpellTarget] =
    Option.when(!p.statuses.has(kind))(onSelf(p))

  /** 自动技能，按先后：保命的治愈最先，隐身最后（放别的技能会现身）。每次只放一个。 */
  private val autoCasts: List[AutoCast] = List(
    AutoCast(Skill.Healing, _.autoHeal, HealRetryMs)(woundedFriend),
    AutoCast(Skill.MagicShield, _.autoMagicShield, CastRetryMs)(keepUp(StatusKind.Shield)),
    AutoCast(Skill.HolyArmor, _.autoHolyArmor, CastRetryMs)(keepUp(StatusKind.ArmorUp)),
    AutoCast(Skill.SoulShield, _.autoSoulShield, CastRetryMs)(keepUp(StatusKind.MagicArmorUp)),
    AutoCast(Skill.SummonSkeleton, _.autoSummonSkeleton, CastRetryMs)(shortOf(SpellRules.SkeletonPet)),
    AutoCast(Skill.SummonShinsu, _.autoSummonShinsu, CastRetryMs)(shortOf(SpellRules.ShinsuPet)),
    AutoCast(Skill.Invisible, _.autoInvisible, CastRetryMs)(hideAgain)
  )

  private def autoCast(w: World, p: Player): Unit =
    autoCasts.iterator
      .filter(a => a.on(p.settings) && p.knows(a.skill) && p.autoCastTried.get(a.skill).forall(w.now - _ >= a.retryMs))
      .flatMap(a => a.aim(w, p).map(a.skill -> _))
      .nextOption()
      .foreach { (skill, target) =>
        p.autoCastTried(skill) = w.now
        Combat.cast(w, p, skill.id, target): Unit
      }

  /**
   * 自动治愈术的对象：施法范围内自己这边的（自己、队友、自己和队友的宝宝）里，
   * 血加上还没回完的治疗量仍低于 HealBelowPercent 的，取最缺血的（按比例）。
   */
  private def woundedFriend(w: World, p: Player): Option[SpellTarget] =
    def ratio(a: Actor): Double = (a.hp + a.mending.fold(0)(_.left)).toDouble / math.max(1, a.maxHp)
    w.actorsNow
      .filter(a => !a.dead && !a.isInstanceOf[Npc] && w.allied(p, a) && p.cell.chebyshev(a.cell) <= SpellRules.CastRange)
      .filter(a => ratio(a) * 100 < HealBelowPercent)
      .minByOption(ratio)
      .map(a => SpellTarget(a.cell, Some(a)))

  /** 自动召唤：这种宝宝不满 SpellRules.SummonsEach 只（变了身的大神兽也算神兽） */
  private def shortOf(pet: MonsterName)(w: World, p: Player): Option[SpellTarget] =
    val have = p.pets.flatMap(w.actor).count {
      case m: Monster => SpellRules.summonedAs(m.defn.name).contains(pet)
      case _          => false
    }
    Option.when(have < SpellRules.SummonsEach)(onSelf(p))

  /** 自动隐身：没隐身、不在安全区（不白费护身符）、没在追着怪打、上次出手已过 ReHideAfterMs */
  private def hideAgain(w: World, p: Player): Option[SpellTarget] =
    Option.when(
      !p.statuses.has(StatusKind.Invisible) && !w.justice.inSafeZone(p) && p.intent == Intent.Idle && p.revealedAt.forall(w.now - _ >= ReHideAfterMs)
    )(onSelf(p))

  /** 血蓝低于设定的百分比自动喝药：优先普通药（慢回），太阳水留着；每 1.5 秒最多一次。 */
  private def autoPotion(w: World, p: Player): Unit =
    if w.now - p.lastPotion >= 1500 then
      val s = p.settings
      def want(cur: Int, max: Int, pct: Int): Boolean = pct > 0 && cur * 100 < max * pct
      def pick(value: ItemKind.Potion => Int): Option[ItemUid] =
        (p.belt.iterator ++ p.bag.iterator).flatten.flatMap { it =>
          w.catalog.item(it.name).map(ItemKind.of).collect {
            case k: ItemKind.Potion if value(k) > 0 => it.uid -> (if k.instant then value(k) * 0.5 else value(k).toDouble)
          }
        }.maxByOption(_._2).map(_._1)
      val drank = List(
        Option.when(want(p.hp, p.stats.maxHp, s.autoHpPercent))(pick(_.hp)).flatten,
        Option.when(want(p.mp, p.stats.maxMp, s.autoMpPercent))(pick(_.mp)).flatten
      ).flatten.count(uid => PlayerOps.useItem(w, p, uid).isRight)
      if drank > 0 then p.lastPotion = w.now
