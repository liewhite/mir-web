package mir.world

import mir.core.*
import mir.model.*
import mir.protocol.Tone
import mir.rules.SpellRules

import scala.collection.mutable

/** 身上的限时状态（中毒、麻痹、战甲、定住、发狂……）和地上持续的法术（火墙、困魔咒光圈）。 */
final class Conditions(w: World):
  private val fields    = mutable.LinkedHashMap.empty[FieldId, Field]
  private var nextField = 1

  // ---------------- 身上的限时状态 ----------------

  /** 到期的状态去掉；战甲、幽灵盾到期要重算属性并提示。 */
  private[world] def expire(a: Actor): Unit =
    val ended = a.statuses.expire(w.now).map(_.status.kind)
    a match
      case p: Player if ended.exists(k => k == StatusKind.ArmorUp || k == StatusKind.MagicArmorUp) =>
        w.vitals.recalc(p)
        if ended.contains(StatusKind.ArmorUp) then w.out.notice(p, "防御力回复正常", Tone.System)
        if ended.contains(StatusKind.MagicArmorUp) then w.out.notice(p, "魔法防御力回复正常", Tone.System)
      case _ => ()

  /** 给对象上一个持续 ms 毫秒的状态（同种覆盖）。 */
  private[world] def addStatus(a: Actor, s: Status, ms: Long): Unit = a.statuses.put(s, w.now + ms)

  /**
   * 中毒：绿毒 PoisonTickMs 后掉第一次血。已中同种毒再下（#1212）：强度取新旧较大的，持续时间从现在重新算，
   * 下一跳的时刻不变（再下毒不推迟掉血），经验归新下毒的人。
   */
  private[world] def poison(a: Actor, kind: PoisonKind, ms: Long, power: Int, by: ActorId): Unit =
    val old = a.statuses.find { case p: Status.Poisoned if p.poison == kind => p }
    val fresh = old.fold(Status.Poisoned(kind, power, w.now + SpellRules.PoisonTickMs, Some(by)))(o =>
      o.copy(power = math.max(o.power, power), by = Some(by)))
    addStatus(a, fresh, ms)
    // 给人下毒也算动手（原版 RM_POISON → SetPKFlag）
    w.actor(by).foreach(w.justice.assaulted(a, _))

  /** 麻痹（原版 STONE 状态）：这段时间不能动；不会把还没到期的缩短。 */
  private[world] def paralyze(a: Actor, ms: Long): Unit =
    a.statuses.extend(Status.Paralyzed, w.now + ms)
    a.statuses.until(StatusKind.Paralyzed).foreach(u => if a.busyUntil < u then a.busyUntil = u)

  /** 给对象加上神圣战甲术或幽灵盾；再放一次不会把还没到期的时间缩短。 */
  private[world] def defenceUp(a: Actor, magic: Boolean, amount: Int, secs: Int): Unit =
    a.statuses.extend(if magic then Status.MagicArmorUp(amount) else Status.ArmorUp(amount), w.now + secs * 1000L)
    a match
      case p: Player =>
        w.vitals.recalc(p)
        w.out.notice(p, s"${if magic then "魔法防御" else "防御"}上限 +$amount，$secs 秒", Tone.Good)
      case _ => ()

  /** 怪物被定住 / 发狂。 */
  private[world] def seize(m: Monster, secs: Int): Unit =
    addStatus(m, Status.Seized, secs * 1000L)
    m.target = None

  private[world] def enrage(m: Monster, secs: Int): Unit =
    addStatus(m, Status.Crazy, secs * 1000L)
    m.target = None

  // ---------------- 地上持续的法术 ----------------

  def activeFields: Iterable[Field] = fields.values

  /** 放一格地上法术；这格已有的不重叠（原版 GetEvent == null 才放）。放了返回 true。 */
  private[world] def addField(cell: Cell, effect: FieldEffect, ms: Long): Boolean =
    if !w.terrain.inside(cell) || fields.valuesIterator.exists(_.cell == cell) then false
    else
      val f = Field(FieldId(nextField), cell, effect, w.now + ms, w.now)
      nextField += 1
      fields(f.id) = f
      true

  /** 到期的去掉；火墙每 3 秒烧一次站在上面的敌人（放下就烧第一次，按魔防减，经验归放火的人）。 */
  private[world] def tickFields(): Unit =
    fields.values.toVector.foreach { f =>
      if w.now >= f.until then fields.remove(f.id): Unit
      else
        f.effect match
          case FieldEffect.Fire(owner, damage) if w.now >= f.nextBurn =>
            fields(f.id) = f.copy(nextBurn = w.now + SpellRules.FireWallTickMs)
            for
              o <- w.actor(owner)
              t <- w.occupants(f.cell)
            do w.vitals.strike(Some(o), t, Blow(Defence.MagicArmor, damage, staggers = false)): Unit
          case _ => ()
    }
