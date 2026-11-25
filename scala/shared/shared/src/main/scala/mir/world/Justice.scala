package mir.world

import mir.core.*
import mir.data.MapFlag
import mir.model.*
import mir.protocol.{Look, Tone}
import mir.rules.{Pk, Rng, SafeZone}

/**
 * PK（#1225，规则在 mir.rules.Pk / SafeZone，照原版 M2Server）：谁能打谁（玩家之间、玩家和别人的宝宝）、
 * 打清白的人变灰名、杀人涨 PK 值、PK 值在线慢慢降、红名死了掉东西。
 * PK 值和灰名只由这里改；名字颜色从它们现算（Player.look），变了由视野自动发出去。
 */
final class Justice(w: World):
  /** 竞技地图（MapFlag.Fight）：打人杀人都不算，死了也不掉东西（原版 boFightZone） */
  private def arena: Boolean = w.mapDef.has(MapFlag.Fight)

  def inSafeZone(a: Actor): Boolean = SafeZone.covers(w.mapDef, a.cell)

  /** 对象背后的玩家：玩家自己，或宝宝的主人（主人要在本图上）。 */
  private[world] def ownerOf(a: Actor): Option[Player] = a match
    case p: Player  => Some(p)
    case m: Monster => m.master.flatMap(w.player)
    case _: Npc     => None

  /**
   * 玩家 a 能不能打 b（玩家之间、玩家打别人的宝宝，双方都在安全区外）：规则在 Look.duel，客户端同一条；
   * 服务端另加一条：双方任一方刚进图、传送 Pk.ArrivalGraceMs 内不算（原版 IsProtectTarget，免得在传送点堵人；客户端不知道别人几时到的，打了也白打）。
   */
  private[world] def duel(a: Actor, b: Actor): Boolean =
    Look.duel(w.mapDef, a.look, a.id, a.cell, b.look, b.cell) && !justArrived(a) && !justArrived(b)

  private def justArrived(a: Actor): Boolean = a match
    case p: Player => w.now - p.arrivedAt < Pk.ArrivalGraceMs
    case _         => false

  /**
   * 宝宝 pet 会不会去打 b（b 是别的玩家或别人的宝宝；原版 IsAttackTarget 有主人那一段）：
   * 主人最近 Grudge.Ms 内挨过 b 那边的人打、主人正打着 b 或 b 的主人、b 正打着主人或主人的宝宝、b 正打着这只宝宝；
   * b 在安全区里不打。b 是野怪、守卫的另归 Look.hostile / Hostility.guardVsPet 管。
   */
  private[world] def petDefends(pet: Monster, b: Actor): Boolean =
    (for
      master <- pet.master.flatMap(w.player)
      rival  <- ownerOf(b) if rival.id != master.id
    yield
      val hitMaster = master.assailant.exists(g => g.by == rival.id && w.now - g.at <= Grudge.Ms)
      val hunted    = huntOf(master).exists(t => t == b.id || t == rival.id)
      val mine      = (id: ActorId) => id == master.id || id == pet.id || master.pets.contains(id)
      val threat    = targetOf(b).exists(mine)
      !inSafeZone(b) && (hitMaster || hunted || threat)
    ).getOrElse(false)

  /** 玩家正打着谁（按住攻击或持续施法的目标） */
  private def huntOf(p: Player): Option[ActorId] = p.intent.foe

  /** 这个对象正打着谁：玩家看按住攻击的目标，怪看它的目标 */
  private def targetOf(a: Actor): Option[ActorId] = a match
    case p: Player  => huntOf(p)
    case m: Monster => m.target
    case _: Npc     => None

  /**
   * victim 挨了 by 一下（打中、下毒；Vitals.damage 和下毒时调）。只算玩家亲手打的（原版宝宝打人不让主人变灰）：
   *  - 挨打的是玩家：记下是谁打的（宝宝护主用）；
   *  - 挨打的是玩家或他的宝宝：按 Pk.brands 让打人的变灰 Pk.FlagMs（原版 SetPKFlag，已灰的重新计时）。
   */
  private[world] def assaulted(victim: Actor, by: Actor): Unit = by match
    case a: Player if !arena =>
      victim match
        case v: Player if v.id != a.id => v.assailant = Some(Grudge(a.id, w.now))
        case _                         => ()
      ownerOf(victim).filter(_.id != a.id).foreach { v =>
        if Pk.brands(v.pkPoints, v.brawler, a.pkPoints) then
          val was = a.brawler
          a.statuses.put(Status.Brawler, w.now + Pk.FlagMs)
          if !was then w.out.notice(a, s"你先动手打了 ${v.charName}，名字变灰 ${Pk.FlagMs / 1000} 秒：这期间别人还手、杀你都不算犯罪", Tone.Bad)
      }
    case _ => ()

  /**
   * 玩家 v 死了（Vitals.kill 调，killer 是最后一下的来源）：
   *  - 凶手（玩家，或宝宝的主人）杀的不是红名：v 不是灰名就算谋杀，PK 值 +Pk.KillPoints；v 是灰名就是正当防卫，不加；
   *  - v 是红名：照 Pk.deathDrop 掉东西（不管是谁杀的，被守卫、怪杀也掉）。
   * 竞技地图里都不算。
   */
  private[world] def died(v: Player, killer: Option[ActorId]): Unit =
    if !arena then
      val murderer = killer.flatMap(w.actor).flatMap(ownerOf).filter(_.id != v.id)
      murderer.filter(_ => !Pk.isRed(v.pkPoints)).foreach { k =>
        if Pk.isMurder(v.pkPoints, v.brawler) then
          setPoints(k, k.pkPoints + Pk.KillPoints)
          w.out.notice(k, s"你犯了谋杀罪！！！PK 值 ${k.pkPoints}${standingNote(k.pkPoints)}", Tone.Bad)
          w.out.notice(v, s"你被 ${k.charName} 杀害了！！！", Tone.Bad)
        else w.out.notice(k, "[你受到正当规则保护。]", Tone.Good)
      }
      Pk.deathDrop(v.pkPoints).foreach(scatter(v, _))

  private def standingNote(points: Int): String =
    if Pk.isRed(points) then "，名字变红：守卫见了就砍，死了包裹全掉、身上的也可能掉"
    else if Pk.level(points) == 1 then "，名字变黄"
    else ""

  /** PK 值在线每 Pk.DecayMs 降 1（原版 DecPKPoint）；每次 tick 对每个玩家调。 */
  private[world] def decay(p: Player): Unit =
    if w.now >= p.pkDecayAt then
      p.pkDecayAt = w.now + Pk.DecayMs
      if p.pkPoints > 0 then
        val wasRed = Pk.isRed(p.pkPoints)
        setPoints(p, p.pkPoints - 1)
        if wasRed && !Pk.isRed(p.pkPoints) then w.out.notice(p, "PK 值降到 200 以下，名字不再是红色", Tone.Good)
        else if p.pkPoints == 0 then w.out.notice(p, "PK 值降到 0，名字恢复白色", Tone.Good)

  private def setPoints(p: Player, n: Int): Unit = p.pkPoints = math.max(0, n)

  /** 红名死了：身上每件按几率掉，包裹（含快捷栏）全掉在身边；身上掉了东西要重算属性。 */
  private def scatter(v: Player, d: Pk.DeathDrop): Unit =
    val rng: Rng = w.rngOf
    val worn  = v.worn.keys.toList.sortBy(_.ordinal).filter(_ => rng.random(d.wornOdds) == 0).map(ItemPlace.Worn(_))
    val bag   = if d.wholeBag then v.bag.indices.map(ItemPlace.Bag(_)) ++ v.belt.indices.map(ItemPlace.Belt(_)) else Nil
    val lost  = (worn ++ bag).flatMap(place => Inventory.at(v, place).map(place -> _))
    lost.foreach { (place, it) =>
      Inventory.put(v, place, None)
      w.dropAt(v.cell, Loot.Item(it))
    }
    if worn.exists(pl => lost.exists(_._1 == pl)) then w.vitals.recalc(v)
    if lost.nonEmpty then
      val names = lost.map(_._2.name.value).mkString("、")
      w.out.notice(v, s"红名死亡，掉了：$names", Tone.Bad)
