package mir.world

import mir.core.*
import mir.model.PickupKind
import mir.rules.{ItemKind, Skill, SpellRules}
import mir.terrain.{MeleeReach, Path, Reach}

/**
 * 玩家的自动功能，按角色设置（CharSettings）在服务端执行：自动喝药、按住攻击（打到死）、被打还手、自动烈火、自动捡物、法师自动魔法盾、道士自动召唤。
 * 照 TS 版 game/controller.ts 移植。玩家亲手走动后 1 秒内不自动走动，免得和摇杆抢。
 */
object AutoPlay:
  def run(w: World, p: Player): Unit =
    if !p.dead then
      autoPotion(w, p)
      if w.now >= p.busyUntil then autoShield(w, p)
      if w.now >= p.busyUntil then autoSummon(w, p)
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
      hitter match
        case Some(a) => p.intent = Intent.Hunt(a.id, once = false)
        case None =>
          if p.settings.autoPickup then nearestLoot(w, p, 6).foreach(g => p.intent = Intent.Fetch(g.id))

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

  /** 自动捡物要不要这件：设置里勾了这一类，且包裹放得下。 */
  private def worth(w: World, p: Player, loot: Loot): Boolean =
    val wanted = p.settings.pickupKinds
    loot match
      case Loot.Coins(_) => wanted(PickupKind.Gold)
      case Loot.Item(it) =>
        w.catalog.item(it.name).exists(d => wanted(ItemKind.pickupKind(d)) && PlayerOps.fits(p, d).isRight)

  /** 自动召唤、自动魔法盾隔多久试一次（放不出来——没符、没蓝——也不刷提示，过会儿再试） */
  val AutoCastRetryMs = 3000

  /** 法师自动魔法盾（设置「法师」页）：学了、身上没盾就开；蓝不够时不发提示，过会儿再试。 */
  private def autoShield(w: World, p: Player): Unit =
    val id = Skill.MagicShield.id
    if p.settings.autoMagicShield && w.now - p.lastAutoShield >= AutoCastRetryMs && p.skill(id).isDefined &&
      !p.statuses.has(StatusKind.Shield)
    then
      p.lastAutoShield = w.now
      Combat.cast(w, p, id, SpellTarget(p.cell, None)): Unit

  /**
   * 道士自动召唤（设置「道士」页勾选）：勾了的种类不满 SpellRules.SummonsEach 只就放一次召唤技能，
   * 先骷髅后神兽，一次只放一个；技能没学、没符、蓝不够时 Combat.cast 拒绝，不发提示。
   */
  private def autoSummon(w: World, p: Player): Unit =
    if w.now - p.lastAutoSummon >= AutoCastRetryMs then
      val s = p.settings
      def short(pet: MonsterName): Boolean =
        p.pets.flatMap(w.actor).count {
          case m: Monster => SpellRules.summonedAs(m.defn.name).contains(pet)
          case _          => false
        } < SpellRules.SummonsEach
      val want = List(
        (s.autoSummonSkeleton, Skill.SummonSkeleton, SpellRules.SkeletonPet),
        (s.autoSummonShinsu, Skill.SummonShinsu, SpellRules.ShinsuPet)
      ).collectFirst { case (true, sk, pet) if p.skill(sk.id).isDefined && short(pet) => sk }
      want.foreach { sk =>
        p.lastAutoSummon = w.now
        Combat.cast(w, p, sk.id, SpellTarget(p.cell, None)): Unit
      }

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
