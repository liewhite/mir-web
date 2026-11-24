package mir.rules

import mir.data.ItemDef
import mir.model.{ItemBonus, ItemInstance}

/**
 * 极品（#1191）：怪物爆出的装备照原版 1.76 随机加属性，不另加倍率（用户 11-24 定）。
 * 原版 WorldServer.MonGen.cs:467：每件爆物 1/MonOdds 的机会进入随机升级；GameItemSystem.cs RandomUpgradeItem
 * 再按部位逐项掷骰（UpgradeRandomWeapon/Dress/Necklace/…）。每项是「1/odds 的机会加 1+档」，档数由 tier 掷出：
 * 连续掷 Random(ran)==0 最多 count 次，成功几次就几档，所以越高越难。持久另掷，只加持久不算极品（原版也不计数）。
 */
object RandomUpgrade:
  /** 原版 Config.MonRandomAddValue */
  val MonOdds = 10

  /** 原版 GetUpgrade(count, ran)。 */
  private def tier(count: Int, ran: Int)(using rng: Rng): Int =
    Iterator.continually(rng.random(ran) == 0).take(count).takeWhile(identity).size

  /** 「tier(count, ran) 掷档，1/odds 的机会生效」的一项；value 把档数换成加的值（0 表示不加）。 */
  private final case class Roll(count: Int, ran: Int, odds: Int, value: Int => Int = 1 + _):
    def apply()(using rng: Rng): Int =
      val t = tier(count, ran)
      if rng.random(odds) == 0 then value(t) else 0

  /** 加持久：tier(count, ran) 掷档，Random(den) < num 时加 (1+档)×step（千分之一点），封顶 65000。 */
  private final case class DuraRoll(count: Int, ran: Int, num: Int, den: Int, step: Int):
    def apply()(using rng: Rng): Int =
      val t = tier(count, ran)
      if rng.random(den) < num then (1 + t) * step else 0

  /** 防具类各部位的表：AC、MAC、DC、MC、SC 五项（None 不掷）和持久。 */
  private final case class GearTable(ac: Option[Roll], mac: Option[Roll], dc: Roll, mc: Roll, sc: Roll, dura: DuraRoll):
    def apply()(using Rng): (ItemBonus, Int) =
      val g = ItemBonus.Gear(ac.fold(0)(_()), mac.fold(0)(_()), dc(), mc(), sc())
      (g, dura())

  private def r(count: Int, ran: Int, odds: Int) = Roll(count, ran, odds)

  // 各部位的数据照 GameItemSystem.cs UpgradeRandom*，顺序与原版一致
  private val dress      = GearTable(Some(r(6, 15, 30)), Some(r(6, 15, 30)), r(6, 20, 40), r(6, 20, 40), r(6, 20, 40), DuraRoll(6, 10, 6, 8, 2000))
  private val necklace   = GearTable(Some(r(6, 30, 60)), Some(r(6, 30, 60)), r(6, 20, 30), r(6, 20, 30), r(6, 20, 30), DuraRoll(6, 12, 15, 20, 1000))
  private val necklace19 = GearTable(Some(r(6, 20, 40)), Some(r(6, 20, 40)), r(6, 20, 30), r(6, 20, 30), r(6, 20, 30), DuraRoll(6, 10, 3, 4, 1000))
  private val bracelet   = GearTable(Some(r(6, 20, 20)), Some(r(6, 20, 20)), r(6, 20, 30), r(6, 20, 30), r(6, 20, 30), DuraRoll(6, 12, 15, 20, 1000))
  private val ring       = GearTable(None, None, r(6, 20, 30), r(6, 20, 30), r(6, 20, 30), DuraRoll(6, 12, 3, 4, 1000))
  private val ring23     = GearTable(Some(r(6, 20, 40)), Some(r(6, 20, 40)), r(6, 20, 30), r(6, 20, 30), r(6, 20, 30), DuraRoll(6, 12, 3, 4, 1000))
  private val helmet     = GearTable(Some(r(6, 20, 40)), Some(r(6, 20, 30)), r(6, 20, 30), r(6, 20, 30), r(6, 20, 30), DuraRoll(6, 12, 3, 4, 1000))

  /**
   * 武器（UpgradeRandomWeapon）。攻速：档数 t，(1+t)/3 > 0 才加，2/3 的机会是减速、1/3 是加速。
   * 原版把攻速存成 Desc[6]（≤10 减速、>10 加速），这里直接存真实值。
   */
  private def weapon()(using rng: Rng): (ItemBonus, Int) =
    val dc    = r(12, 15, 15)()
    val speed = Roll(12, 15, 20, t => (1 + t) / 3)() match
      case 0 => 0
      case n => if rng.random(3) != 0 then -n else n
    val mc       = r(12, 15, 15)()
    val sc       = r(12, 15, 15)()
    val hit      = Roll(12, 15, 24, t => 1 + t / 2)()
    val dura     = DuraRoll(12, 12, 2, 3, 2000)()
    val strength = Roll(12, 15, 10, t => 1 + t / 2)()
    (ItemBonus.Weapon(dc, mc, sc, hit, speed, strength), dura)

  /** 这件物品按部位掷一次随机升级：附加属性（全 0 为 None）和加的持久。不是装备的返回 None。 */
  def roll(d: ItemDef)(using Rng): Option[(Option[ItemBonus], Int)] =
    val table: Option[() => (ItemBonus, Int)] = d.mode match
      case 5 | 6        => Some(() => weapon())
      case 10 | 11      => Some(() => dress())
      case 19           => Some(() => necklace19())
      case 20 | 21 | 24 => Some(() => necklace())
      case 26           => Some(() => bracelet())
      case 22           => Some(() => ring())
      case 23           => Some(() => ring23())
      case 15           => Some(() => helmet())
      case _            => None
    table.map { t =>
      val (b, dura) = t()
      (Option.when(!isEmpty(b))(b), dura)
    }

  private def isEmpty(b: ItemBonus): Boolean = b match
    case w: ItemBonus.Weapon => w == ItemBonus.Weapon()
    case g: ItemBonus.Gear   => g == ItemBonus.Gear()

  /** 原版持久上限 65000（千分之一点）。 */
  private val DuraCap = 65000

  /** 怪物爆出的一件物品：1/MonOdds 的机会随机升级（MonGen.cs:467）。 */
  def monsterDrop(it: ItemInstance, d: ItemDef)(using rng: Rng): ItemInstance =
    if rng.random(MonOdds) != 0 then it
    else
      roll(d).fold(it) { (bonus, dura) =>
        it.copy(
          bonus = bonus,
          duraMax = math.min(DuraCap, it.duraMax + dura),
          dura = math.min(DuraCap, it.dura + dura)
        )
      }
