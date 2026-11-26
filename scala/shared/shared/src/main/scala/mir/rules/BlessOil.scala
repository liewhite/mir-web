package mir.rules

import mir.data.ItemDef
import mir.model.{ItemBonus, ItemInstance}

/**
 * 祝福油（#1320）：给手上拿着的武器加幸运，用一瓶消耗一瓶；手上没武器用不了、不消耗。
 * 照原版 ObjPlay.pas TPlayObject.WeaptonMakeLuck 和 ObjBase.pas MakeWeaponUnlock，概率按用户 11-26 定「比原版稍微容易点」改过：
 * - 先有 1/CurseOdds 机会武器被诅咒（原版 1/20，本服 1/25）：武器上有祝福来的幸运就减 1，否则诅咒 +1（诅咒最多 MaxCurse 点）。
 * - 没被诅咒时：武器有诅咒就先去掉 1 点（必成，原版）；没有诅咒就往上加 1 点幸运，机会看现在几点（odds）：
 *   - 0→1：必成（原版）；
 *   - 1→2、2→3：1/(MidBase + k)，原版 1/(6 + k)，本服 1/(4 + k)；
 *   - 3→4 … 6→7：1/(HighFactor × max(1, k))，原版 1/(40 × k)（k = 0 的武器原版永远加不上），本服 1/(30 × max(1, k))；
 *   - 到 MaxLuck（7）封顶。
 *   k = 武器物品表攻击上限减下限 ÷ 5（向下取整）：攻击区间越宽的好武器越难加。
 * - 这里的幸运、诅咒只算祝福油加上去的部分（ItemBonus.Weapon.luck/curse），物品表自带的幸运另算、不占 7 点上限（原版同）。
 * - 原版杀人扣幸运、武器 1/5 机会被诅咒不做（用户 11-26 #1320 定暂时不做）。
 */
object BlessOil:
  /** 每次用油先有 1/CurseOdds 机会武器被诅咒 */
  val CurseOdds = 25
  /** 祝福幸运上限 */
  val MaxLuck = 7
  /** 诅咒上限（原版） */
  val MaxCurse = 10
  /** 幸运低于它时必成（0→1） */
  val SureBelow = 1
  /** 幸运低于它时用中档机会（1→2、2→3） */
  val MidBelow = 3
  val MidBase  = 4
  val HighFactor = 30

  enum Outcome:
    /** 武器被诅咒了（祝福幸运 −1 或诅咒 +1；诅咒已满时武器不变） */
    case Cursed(weapon: ItemInstance)
    /** 加上了 1 点幸运，或去掉了 1 点诅咒 */
    case Blessed(weapon: ItemInstance)
    /** 这次没加上 */
    case NoEffect

  /** k：武器物品表攻击上限减下限 ÷ 5 */
  def steps(d: ItemDef): Int = math.abs(d.dc.hi - d.dc.lo) / 5

  /** 祝福幸运从 luck 加到 luck + 1 的机会是「N 次里 1 次」；已到上限为 None。 */
  def odds(luck: Int, k: Int): Option[Int] =
    if luck >= MaxLuck then None
    else if luck < SureBelow then Some(1)
    else if luck < MidBelow then Some(MidBase + k)
    else Some(HighFactor * math.max(1, k))

  /** 武器上祝福来的幸运、诅咒 */
  def blessing(weapon: ItemInstance): ItemBonus.Weapon = weapon.bonus match
    case Some(b: ItemBonus.Weapon) => b
    case _                         => ItemBonus.Weapon()

  /** 对手上的武器 weapon（物品表定义 d）用一瓶祝福油。 */
  def apply(weapon: ItemInstance, d: ItemDef)(using rng: Rng): Outcome =
    val b = blessing(weapon)
    def put(nb: ItemBonus.Weapon): ItemInstance = weapon.copy(bonus = Option.when(nb != ItemBonus.Weapon())(nb))
    if rng.random(CurseOdds) == 0 then
      if b.luck > 0 then Outcome.Cursed(put(b.copy(luck = b.luck - 1)))
      else Outcome.Cursed(put(b.copy(curse = math.min(MaxCurse, b.curse + 1))))
    else if b.curse > 0 then Outcome.Blessed(put(b.copy(curse = b.curse - 1)))
    else
      odds(b.luck, steps(d)).filter(n => rng.random(n) == 0) match
        case Some(_) => Outcome.Blessed(put(b.copy(luck = b.luck + 1)))
        case None    => Outcome.NoEffect
