package mir.rules

import mir.core.{ItemName, ItemUid}
import mir.data.{ItemDef, Span}
import mir.model.{ItemBonus, ItemInstance}

/**
 * 武器店升级武器（#1286）。照原版 ObjNpc.pas TMerchant.UpgradeWapon（交材料）、GetBackupgWeapon（定结果）和
 * ObjBase.pas CheckWeaponUpgradeStatus（揭晓），参数是 M2Share.pas 的默认配置；本服不用等 1 小时、当场出结果（用户 11-25 定）。
 * - 升级的是手上拿着的武器；交 Price 金币；包裹里全部黑铁矿石和全部「首饰类」物品（原版 ITEM_ACCESSORY：头盔、项链、手镯、戒指等）一并收走，
 *   包裹里至少要有一块黑铁矿石。
 * - 黑铁矿石：纯度最高的 TopOres 块定持久上限怎么变（Recipe.purity）。
 * - 首饰：每件的攻击/魔法/道术（下限+上限，手镯再 +1）各取最高的两件，最高的 ÷5 加第二的 ÷3，得出三项点数；
 *   点数最高的一项就是这次往哪项升（Attempt），并列时道术优先于魔法、魔法优先于攻击（原版后判的盖前面的）。
 * - 成功率 = min(85, 7×min(11, 点数) + 10)%；成功时加 1，成功率 > 63% 时 1/30 机会改为加 2，> 79% 时再 1/200 机会改为加 3；
 *   失败武器碎掉。武器已有的攻击+魔法+道术附加合计到了 MaxPoints，不论成败都碎。
 * - 原版升道术时成功率用的是魔法点数（GetBackupgWeapon 里 n90:=_MIN(11,btMc)），照搬。
 * 原版成功率里另有武器幸运、人物幸运两项，本服没有这两样，按 0 算。
 */
object WeaponRefine:
  val Price     = 10000
  val MaxPoints = 20
  val TopOres   = 5
  val BlackIron: ItemName = Mining.Ore.BlackIron.item

  /** 原版 ITEM_ACCESSORY（LocalDB.pas）：这些 StdMode 的物品都会被收走 */
  private val AccessoryModes = Set(15, 19, 20, 21, 22, 23, 24, 26, 51, 52, 53, 54, 62, 63, 64)

  enum Aspect(val label: String):
    case Dc extends Aspect("攻击")
    case Mc extends Aspect("魔法")
    case Sc extends Aspect("道术")

  /** 交给铁匠的材料：包裹里的全部黑铁矿石和首饰。 */
  final case class Materials(ores: Vector[ItemInstance], jewels: Vector[(ItemInstance, ItemDef)]):
    def items: Vector[ItemInstance] = ores ++ jewels.map(_._1)
    def uids: Set[ItemUid]          = items.map(_.uid).toSet

  def materials(bag: Iterable[ItemInstance], item: ItemName => Option[ItemDef]): Materials =
    val all = bag.toVector.flatMap(it => item(it.name).map(it -> _))
    Materials(
      all.collect { case (it, _) if it.name == BlackIron => it },
      all.filter((it, d) => it.name != BlackIron && AccessoryModes.contains(d.mode))
    )

  /** 材料折算出的点数（原版 UpgradeInfo.btDc/btMc/btSc/btDura）。 */
  final case class Recipe(dc: Int, mc: Int, sc: Int, purity: Int)

  /** 原版 Delphi 的 Round：银行家舍入 */
  private def round(x: Double): Int = math.rint(x).toInt

  /** 一件首饰在某项上的分量：下限+上限（含极品附加），手镯（StdMode 24、26）再 +1，其他首饰类（头盔等）0。 */
  private def weight(d: ItemDef, it: ItemInstance, pick: ItemEffects => Span): Int =
    val s = pick(ItemEffects.of(d, it.bonus))
    d.mode match
      case 19 | 20 | 21 | 22 | 23 => s.lo + s.hi
      case 24 | 26                => s.lo + s.hi + 1
      case _                      => 0

  /** 最高的 ÷5 + 第二的 ÷3 */
  private def topTwo(xs: Vector[Int]): Int =
    val s = xs.sorted(using Ordering[Int].reverse)
    s.headOption.getOrElse(0) / 5 + s.lift(1).getOrElse(0) / 3

  /** 没有黑铁矿石为 None。 */
  def recipe(m: Materials): Option[Recipe] =
    Option.when(m.ores.nonEmpty) {
      val top   = m.ores.map(o => round(o.dura / 1000.0)).sorted(using Ordering[Int].reverse).take(TopOres)
      val n     = top.size
      val score = (pick: ItemEffects => Span) => topTwo(m.jewels.map((it, d) => weight(d, it, pick)))
      Recipe(score(_.dc), score(_.mc), score(_.sc), round(n + n * (top.sum.toDouble / n) / 5.0))
    }

  /** 这次往哪项升、成功的百分率。 */
  final case class Attempt(aspect: Aspect, chance: Int)

  def attempt(r: Recipe): Attempt =
    val aspect =
      if r.sc >= r.mc && r.sc >= r.dc then Aspect.Sc
      else if r.mc >= r.dc && r.mc >= r.sc then Aspect.Mc
      else Aspect.Dc
    val pts = aspect match
      case Aspect.Dc => r.dc
      case Aspect.Mc => r.mc
      case Aspect.Sc => r.mc // 原版如此（见上）
    val n = math.min(11, pts)
    Attempt(aspect, math.min(85, n * 7 + 10))

  /** 武器已有的攻击+魔法+道术附加 */
  def points(weapon: ItemInstance): Int = weapon.bonus match
    case Some(b: ItemBonus.Weapon) => b.dc + b.mc + b.sc
    case _                         => 0

  def capped(weapon: ItemInstance): Boolean = points(weapon) >= MaxPoints

  /** 不能升级的原因（客户端确认前和服务端执行时同一套）。 */
  def refusal(weapon: Option[ItemInstance], gold: Int, m: Materials): Option[String] =
    if weapon.isEmpty then Some("手上没有武器：要升级哪把就先拿在手上")
    else if m.ores.isEmpty then Some("包裹里没有黑铁矿石")
    else if gold < Price then Some(s"金币不够，升级一次要 $Price 金币")
    else None

  /** 结果 */
  enum Result:
    /** 成功：升好的武器 */
    case Raised(aspect: Aspect, by: Int, weapon: ItemInstance)
    /** 失败或累计附加到了上限：武器碎了 */
    case Broken

  /** 掷成败：0 失败，1–3 加几点。 */
  private def roll(a: Attempt)(using rng: Rng): Int =
    if rng.random(100) >= a.chance then 0
    else
      val two   = a.chance > 63 && rng.random(30) == 0
      val three = a.chance > 79 && rng.random(200) == 0
      if three then 3 else if two then 2 else 1

  /** 持久上限怎么变（原始单位，Word 封顶 65535）：纯度低扣、高加。 */
  def duraMaxAfter(duraMax: Int, purity: Int)(using rng: Rng): Int =
    if purity <= 8 then (if duraMax > 3000 then duraMax - 3000 else duraMax >> 1)
    else if purity <= 15 then (if rng.random(purity) < 6 && duraMax > 1000 then duraMax - 1000 else duraMax)
    else if purity <= 17 then duraMax
    else
      val add = rng.random(purity - 18) match
        case n if n >= 8 => 4000
        case n if n >= 5 => 2000
        case n if n >= 1 => 1000
        case _           => 0
      math.min(65535, duraMax + add)

  /** 升级一次：先定持久，再定成败。 */
  def refine(weapon: ItemInstance, r: Recipe)(using Rng): Result =
    val max = duraMaxAfter(weapon.duraMax, r.purity)
    val a   = attempt(r)
    val by  = roll(a)
    if by == 0 || capped(weapon) then Result.Broken
    else
      val b = weapon.bonus match
        case Some(w: ItemBonus.Weapon) => w
        case _                         => new ItemBonus.Weapon()
      val raised = a.aspect match
        case Aspect.Dc => b.copy(dc = b.dc + by)
        case Aspect.Mc => b.copy(mc = b.mc + by)
        case Aspect.Sc => b.copy(sc = b.sc + by)
      Result.Raised(a.aspect, by, weapon.copy(duraMax = max, dura = math.min(weapon.dura, max), bonus = Some(raised)))

  /**
   * 确认前给玩家看的概率：raised 是加 1/2/3 各自的概率，broken 是碎掉的概率；
   * duraMax 是成功时持久上限变化（原始单位）→ 概率。
   */
  final case class Forecast(attempt: Attempt, capped: Boolean, raised: Map[Int, Double], broken: Double, duraMax: Map[Int, Double])

  def forecast(weapon: ItemInstance, r: Recipe): Forecast =
    val a  = attempt(r)
    val p  = a.chance / 100.0
    val p3 = if a.chance > 79 then p / 200 else 0.0
    val p2 = if a.chance > 63 then p / 30 * (1 - (if a.chance > 79 then 1.0 / 200 else 0.0)) else 0.0
    val raised = if capped(weapon) then Map.empty[Int, Double] else Map(1 -> (p - p2 - p3), 2 -> p2, 3 -> p3).filter(_._2 > 0)
    val dura = Odds.of()(rng => duraMaxAfter(weapon.duraMax, r.purity)(using rng) - weapon.duraMax).getOrElse(Map.empty)
    Forecast(a, capped(weapon), raised, 1 - raised.values.sum, dura)
