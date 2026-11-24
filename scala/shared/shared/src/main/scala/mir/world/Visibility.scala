package mir.world

import mir.core.*
import mir.model.*
import mir.protocol.*

import scala.collection.mutable

/**
 * 视野：flush 时把攒下的消息按视野分给每个玩家，并比出他身边的变化——对象出现/消失/动作/血量/外观、
 * 地上物品、地上法术，以及自己界面上变了的部分（SelfView）。
 */
final class Visibility(w: World):
  import World.ViewRange

  /** 一个对象上次广播出去的样子（变了才再发）。 */
  private final case class Shown(cell: Cell, dir: Dir, action: ActionView, hp: Int, maxHp: Int, look: Look, tint: Option[BodyTint])
  private val lastBroadcast = mutable.Map.empty[ActorId, Shown]

  /** 一个玩家已经知道的对象、地上物品，和上次发给他的「自己」的状态。 */
  private final class Viewer:
    val actors = mutable.Set.empty[ActorId]
    val ground = mutable.Set.empty[GroundId]
    val fields = mutable.Set.empty[FieldId]
    var self   = Option.empty[SelfView]

  private val viewers = mutable.Map.empty[ActorId, Viewer]

  /** 对象离开本图：不再记它的样子，它是玩家的话也不再记他知道什么。 */
  private[world] def forget(id: ActorId): Unit =
    lastBroadcast.remove(id)
    viewers.remove(id): Unit

  /** 玩家的视野重来（同图传送）：下次 flush 把身边的一切当作新出现的重新发。 */
  private[world] def restart(id: ActorId): Unit = viewers.remove(id): Unit

  /** 一个对象此刻的样子（进入视野时发的全貌）。 */
  def viewOf(a: Actor): ActorView =
    ActorView(a.id, a.look, a.cell, a.dir, Hp(a.hp), Hp(a.maxHp), a.action, a.dead, a.tint)

  /** 某玩家此刻能看到的对象和地上物品。 */
  def visibleTo(p: Player): (Iterable[Actor], Iterable[GroundItem]) =
    (w.eachActor.filter(a => sees(p, a.cell)).toVector, w.groundItems.filter(g => sees(p, g.cell)))

  /** 某玩家此刻能看到的地上法术。 */
  def fieldsVisibleTo(p: Player): Iterable[Field] = w.activeFields.filter(f => sees(p, f.cell))

  private def sees(p: Player, c: Cell): Boolean = p.cell.chebyshev(c) <= ViewRange

  private def shown(a: Actor): Shown = Shown(a.cell, a.dir, a.action, a.hp, a.maxHp, a.look, a.tint)

  /** 自己的全部界面状态。 */
  private[world] def selfView(p: Player): SelfView =
    SelfView(
      stats = SelfStats(p.level, p.exp, w.catalog.expToNext(p.level), Hp(p.hp), Mp(p.mp), p.gold, p.stats),
      bag = p.bag.toVector, belt = p.belt.toVector, worn = p.worn.toMap, storage = p.storage.toList, skills = p.skills.toList,
      cooldowns = p.cooldown.toMap,
      status = selfStatus(p),
      sword = SwordState(p.longHitOn, p.wideHitOn, p.fireReady),
      settings = p.settings
    )

  /** 自己身上的状态（界面上的图标和倒计时）。 */
  private def selfStatus(p: Player): SelfStatus =
    val s = p.statuses
    // 界面只显示一种毒：绿毒优先（玩家只会被怪下绿毒）
    val poison = s.find { case q: Status.Poisoned if q.poison == PoisonKind.Green => q.poison }.orElse(s.find { case q: Status.Poisoned => q.poison })
    SelfStatus(
      poison = poison, poisonUntil = poison.flatMap(k => s.until(StatusKind.poison(k))),
      shieldUntil = s.until(StatusKind.Shield),
      invisibleUntil = s.until(StatusKind.Invisible),
      paralyzedUntil = s.until(StatusKind.Paralyzed),
      acUpUntil = s.until(StatusKind.ArmorUp),
      macUpUntil = s.until(StatusKind.MagicArmorUp)
    )

  /** 把攒下的消息和身边的变化分给每个玩家。 */
  private[world] def flush(pending: Pending): Outbox =
    val out = mutable.LinkedHashMap.empty[ActorId, mutable.ArrayBuffer[ServerMsg]]
    def to(id: ActorId) = out.getOrElseUpdate(id, mutable.ArrayBuffer.empty)
    pending.personal.foreach((id, msgs) => to(id) ++= msgs)

    // 每个对象变了什么（全图算一次）
    val changes = mutable.Map.empty[ActorId, List[ServerMsg]]
    val present = mutable.Set.empty[ActorId]
    w.eachActor.foreach { a =>
      val now = shown(a)
      present += a.id
      lastBroadcast.get(a.id).foreach { was =>
        val act  = Option.when(was.cell != now.cell || was.dir != now.dir || was.action != now.action)(ServerMsg.Act(a.id, now.cell, now.dir, now.action))
        val hp   = Option.when(was.hp != now.hp || was.maxHp != now.maxHp)(ServerMsg.HpChanged(a.id, Hp(now.hp), Hp(now.maxHp)))
        val look = Option.when(was.look != now.look)(ServerMsg.LookChanged(a.id, now.look))
        val tint = Option.when(was.tint != now.tint)(ServerMsg.TintChanged(a.id, now.tint))
        val all  = List(look, act, hp, tint).flatten
        if all.nonEmpty then changes(a.id) = all
      }
      lastBroadcast(a.id) = now
    }
    lastBroadcast.keySet.diff(present).foreach(lastBroadcast.remove)

    w.players.foreach { p =>
      val v   = viewers.getOrElseUpdate(p.id, Viewer())
      val buf = to(p.id)
      val (seenActors, seenGround) = visibleTo(p)
      val nowIds = seenActors.map(_.id).toSet
      v.actors.diff(nowIds).foreach(id => buf += ServerMsg.Disappear(id))
      seenActors.foreach { a =>
        if v.actors.contains(a.id) then changes.get(a.id).foreach(buf ++= _)
        else buf += ServerMsg.Appear(viewOf(a))
      }
      v.actors.clear()
      v.actors ++= nowIds
      val gIds = seenGround.map(_.id).toSet
      v.ground.diff(gIds).foreach(id => buf += ServerMsg.GroundDisappear(id))
      seenGround.filterNot(g => v.ground.contains(g.id)).foreach(g => buf += ServerMsg.GroundAppear(g.view))
      v.ground.clear()
      v.ground ++= gIds
      val seenFields = fieldsVisibleTo(p)
      val fIds       = seenFields.map(_.id).toSet
      v.fields.diff(fIds).foreach(id => buf += ServerMsg.FieldDisappear(id))
      seenFields.filterNot(f => v.fields.contains(f.id)).foreach(f => buf += ServerMsg.FieldAppear(f.view))
      v.fields.clear()
      v.fields ++= fIds
      pending.local.foreach((c, m) => if sees(p, c) then buf += m)
      val sv = selfView(p)
      buf ++= SelfView.diff(v.self, sv)
      v.self = Some(sv)
    }
    Outbox(out.view.mapValues(_.toVector).toMap.filter(_._2.nonEmpty), pending.departed)

/** 一个玩家界面上「自己」的全部状态；变了的部分才发。 */
final case class SelfView(
    stats: SelfStats,
    bag: Vector[Option[ItemInstance]],
    belt: Vector[Option[ItemInstance]],
    worn: Map[EquipSlot, ItemInstance],
    storage: List[ItemInstance],
    skills: List[LearnedSkill],
    cooldowns: Map[SkillId, Millis],
    status: SelfStatus,
    sword: SwordState,
    settings: CharSettings
)

object SelfView:
  def diff(was: Option[SelfView], now: SelfView): List[ServerMsg] =
    def changed[A](f: SelfView => A): Boolean = !was.map(f).contains(f(now))
    List(
      Option.when(changed(_.stats))(ServerMsg.Stats(now.stats)),
      Option.when(changed(v => (v.bag, v.belt)))(ServerMsg.Bag(now.bag, now.belt)),
      Option.when(changed(_.worn))(ServerMsg.Equipment(now.worn)),
      Option.when(changed(_.storage))(ServerMsg.Storage(now.storage)),
      Option.when(changed(_.skills))(ServerMsg.Skills(now.skills)),
      Option.when(changed(_.cooldowns))(ServerMsg.Cooldowns(now.cooldowns)),
      Option.when(changed(_.status))(ServerMsg.Status(now.status)),
      Option.when(changed(_.sword))(ServerMsg.Sword(now.sword)),
      Option.when(changed(_.settings))(ServerMsg.Settings(now.settings))
    ).flatten
