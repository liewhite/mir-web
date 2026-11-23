package mir.world

import mir.core.*
import mir.rules.ItemKind

/**
 * 玩家的自动功能，按角色设置（CharSettings）在服务端执行：自动喝药、按住攻击（打到死）、被打还手、自动烈火、自动捡物。
 * 照 TS 版 game/controller.ts 移植。玩家亲手走动后 1 秒内不自动走动，免得和摇杆抢。
 */
object AutoPlay:
  def run(w: World, p: Player): Unit =
    if !p.dead then
      autoPotion(w, p)
      if w.now >= p.busyUntil then
        p.intent match
          case Intent.Hunt(target, once) => hunt(w, p, target, once)
          case Intent.Fetch(item)        => fetch(w, p, item)
          case Intent.Idle               => idle(w, p)

  private def free(w: World, p: Player): Boolean = w.now >= p.manualUntil

  private def hunt(w: World, p: Player, id: ActorId, once: Boolean): Unit =
    w.actor(id).filter(t => !t.dead && w.isEnemy(p, t)) match
      case None => p.intent = Intent.Idle
      case Some(t) =>
        val d = p.cell.chebyshev(t.cell)
        val longOk = p.longHitOn && p.skill(SkillId(12)).isDefined && d == 2 && inLine(p.cell, t.cell) &&
          w.occupant(p.cell.step(p.cell.dirTo(t.cell))).isEmpty
        if d == 1 || longOk then
          maybeFire(w, p)
          Combat.attack(w, p, p.cell.dirTo(t.cell))
          if once then p.intent = Intent.Idle
        else if free(w, p) then approach(w, p, t.cell, adjacent = true)

  private def fetch(w: World, p: Player, id: GroundId): Unit =
    w.ground.get(id) match
      case None => p.intent = Intent.Idle
      case Some(g) if g.cell == p.cell =>
        PlayerOps.pickUp(w, p)
        p.intent = Intent.Idle
      case Some(g) => if free(w, p) then approach(w, p, g.cell, adjacent = false)

  private def idle(w: World, p: Player): Unit =
    if free(w, p) then
      // 被打自动还手
      val hitter = p.lastHitBy.flatMap(w.actor).filter(a => p.settings.autoFightBack && !a.dead && w.isEnemy(p, a) && p.cell.chebyshev(a.cell) <= 3)
      p.lastHitBy = None
      hitter match
        case Some(a) => p.intent = Intent.Hunt(a.id, once = false)
        case None =>
          if p.settings.autoPickup then nearestLoot(w, p, 6).foreach(g => p.intent = Intent.Fetch(g.id))

  /** 走到目标旁边（或站上去）；走不了就转过去等一下。 */
  private def approach(w: World, p: Player, target: Cell, adjacent: Boolean): Unit =
    val moved = Path.find(w.canEnter, p.cell, target, 3000, adjacent).flatMap(_.headOption).exists(w.move(p, _, run = false))
    if !moved then
      w.face(p, p.cell.dirTo(target))
      p.busyUntil = w.now + 200

  private def inLine(a: Cell, b: Cell): Boolean =
    val dx = b.x - a.x
    val dy = b.y - a.y
    dx == 0 || dy == 0 || math.abs(dx) == math.abs(dy)

  private def maybeFire(w: World, p: Player): Unit =
    val id = SkillId(26)
    if p.settings.autoFireSword && !p.fireReady && p.skill(id).isDefined && !p.cooldown.get(id).exists(_ > w.now) then
      Combat.cast(w, p, id, SpellTarget(p.cell, None)) match
        case _ => ()

  private def nearestLoot(w: World, p: Player, radius: Int): Option[GroundItem] =
    w.groundItems.filter { g =>
      val worth = g.loot match
        case Loot.Coins(_) => true
        case Loot.Item(it) =>
          w.catalog.item(it.name).exists { d =>
            val valuable = ItemKind.of(d) match
              case ItemKind.Equip(_) | ItemKind.Book(_, _) | ItemKind.Amulet(_) => true
              case _                                                          => false
            (!p.settings.pickupValuableOnly || valuable) &&
            PlayerOps.fits(w, p, d, it.count).isRight
          }
      // 有别人站着的格子捡不了
      worth && p.cell.chebyshev(g.cell) <= radius && w.occupant(g.cell).forall(_.id == p.id)
    }.minByOption(g => p.cell.chebyshev(g.cell))

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
