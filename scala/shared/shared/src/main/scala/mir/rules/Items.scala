package mir.rules

import mir.core.ItemUid
import mir.data.{ItemDef, Span}
import mir.model.*

/**
 * 物品：实例，以及「这件东西到底是什么、加什么」。
 *
 * 1.76 的物品表把很多属性藏在 AC/MAC 的高低字节里（不同 StdMode 含义不同），原版界面只显示
 * 「防御 0-2」这种字面值，玩家看不出项链其实加的是准确/敏捷。这里按服务端 ApplyItemParameters
 * （ref/Mir176 PlayObject.cs:4414）把每种 StdMode 翻成明确的效果，属性计算和物品说明都从这一处取。
 * 照 TS 版 game/src/world/items.ts 移植。
 */

/** 卷轴的作用（StdMode 3 的 Shape）。 */
enum ScrollEffect:
  /** 地牢逃脱卷 */
  case Escape
  /** 随机传送卷 */
  case Random
  /** 回城卷 */
  case Town
  case Other

/** 物品的用途，按 StdMode 分类。 */
enum ItemKind:
  /** 药：instant 为真的一口回满（太阳水），否则分几次慢慢回 */
  case Potion(hp: Int, mp: Int, instant: Boolean)
  case Scroll(effect: ScrollEffect)
  /** 技能书：职业、学习等级 */
  case Book(job: Option[Job], level: Int)
  /** 捆装：拆成 6 个 Ani 等于 of 的物品 */
  case Bundle(of: Int)
  case Equip(slots: List[EquipSlot])
  /** 护身符（poison 为空）、灰色药粉（绿毒）、黄色药粉（红毒）：可戴左手镯位，放包裹里也能用（#1202） */
  case Amulet(poison: Option[PoisonKind])
  case Material
  case Other

object ItemKind:
  /** 一个捆装药拆出几个 */
  val BundleSize = 6

  /** 靴子、腰带的 StdMode（1.80 数据） */
  val BootsMode = 52
  val BeltMode  = 54

  def of(d: ItemDef): ItemKind = d.mode match
    case 0 => Potion(d.ac.lo, d.mac.lo, d.shape == 1)
    case 3 =>
      Scroll(d.shape match
        case 1 => ScrollEffect.Escape
        case 2 => ScrollEffect.Random
        case 3 => ScrollEffect.Town
        case _ => ScrollEffect.Other
      )
    case 4       => Book(Job.values.find(_.code == d.shape), d.dura)
    case 31      => Bundle(d.shape)
    case 5 | 6   => Equip(List(EquipSlot.Weapon))
    case 10 | 11 => Equip(List(EquipSlot.Dress))
    case 15      => Equip(List(EquipSlot.Helmet))
    case 19 | 20 | 21 => Equip(List(EquipSlot.Necklace))
    case 22 | 23 => Equip(List(EquipSlot.RingL, EquipSlot.RingR))
    case 24 | 26 => Equip(List(EquipSlot.BraceletL, EquipSlot.BraceletR))
    // 1.80 的靴子、腰带（客户端 ClFunc.GetTakeOnPosition、服务端 M2Share.CheckTakeOnItems）；
    // 1.80 另有 62/64 号（加负重、回复的变种），本服不设负重，不收
    case BootsMode => Equip(List(EquipSlot.Boots))
    case BeltMode  => Equip(List(EquipSlot.Belt))
    case 25 =>
      Amulet(d.shape match
        case 1 => Some(PoisonKind.Green)
        case 2 => Some(PoisonKind.Red)
        case _ => None
      )
    case 42 | 43 => Material
    case _       => Other

  /** 能放哪些装备位（不能穿戴的为空）。 */
  def slots(k: ItemKind): List[EquipSlot] = k match
    case Equip(s)  => s
    case Amulet(_) => List(EquipSlot.BraceletL)
    case _         => Nil

  /**
   * 同名叠成一格、不设上限（用户 #1159 定，原版不叠）：药、卷轴、技能书、捆装药；护身符和药粉按「次」叠（#1213，见 Charges）。
   * 装备（每件持久不同）、矿石等材料（纯度不同）和其他都不叠。
   */
  def stacks(k: ItemKind): Boolean = k match
    case Potion(_, _, _) | Scroll(_) | Book(_, _) | Bundle(_) | Amulet(_) => true
    case Equip(_) | Material | Other                                    => false

  def stacks(d: ItemDef): Boolean = stacks(of(d))

  /** 能放快捷栏：药和卷轴。 */
  def beltable(k: ItemKind): Boolean = k match
    case Potion(_, _, _) | Scroll(_) => true
    case _                           => false

  /** 肉（StdMode 40）：ItemKind 里归「其他」（与 TS 版对照一致），自动捡物时和矿石、材料算一类。 */
  private val MeatMode = 40

  /** 自动捡物的分类（#1178）。 */
  def pickupKind(d: ItemDef): PickupKind = of(d) match
    case Potion(_, _, _) | Bundle(_)  => PickupKind.Potion
    case Equip(_)                     => PickupKind.Equip
    case Book(_, _)                   => PickupKind.Book
    case Amulet(_)                    => PickupKind.Amulet
    case Scroll(_)                    => PickupKind.Scroll
    case Material                     => PickupKind.Material
    case Other if d.mode == MeatMode  => PickupKind.Material
    case Other                        => PickupKind.Other

/** 一件装备带来的全部加成，全部是明确含义的字段。 */
final case class ItemEffects(
    ac: Span = Span.zero,
    mac: Span = Span.zero,
    dc: Span = Span.zero,
    mc: Span = Span.zero,
    sc: Span = Span.zero,
    /** 准确：命中率 */
    hit: Int = 0,
    /** 敏捷：闪避率 */
    agility: Int = 0,
    /** 攻击速度档位：每档攻击间隔缩短 60 毫秒 */
    attackSpeed: Int = 0,
    luck: Int = 0,
    curse: Int = 0,
    /** 魔法躲避 */
    magicAvoid: Int = 0,
    /** 毒物躲避 */
    poisonAvoid: Int = 0,
    /** 体力恢复、魔法恢复、中毒恢复 */
    hpRegen: Int = 0,
    mpRegen: Int = 0,
    poisonRegen: Int = 0
)

object ItemEffects:
  val none: ItemEffects = ItemEffects()

  /** 武器 MAC 高字节是攻速：≤10 为减速，>10 为加速（GameItemSystem.RealAttackSpeed）。 */
  private def weaponSpeed(v: Int): Int = if v <= 10 then -v else v - 10

  def of(d: ItemDef): ItemEffects =
    val base = ItemEffects(dc = d.dc, mc = d.mc, sc = d.sc)
    d.mode match
      case 5 | 6 =>
        base.copy(hit = d.ac.hi, attackSpeed = weaponSpeed(d.mac.hi), luck = d.ac.lo, curse = d.mac.lo)
      case 19      => base.copy(magicAvoid = d.ac.hi, curse = d.mac.lo, luck = d.mac.hi)
      case 20 | 24 => base.copy(hit = d.ac.hi, agility = d.mac.hi)
      case 21      => base.copy(hpRegen = d.ac.hi, mpRegen = d.mac.hi, attackSpeed = d.ac.lo - d.mac.lo)
      case 23      => base.copy(poisonAvoid = d.ac.hi, poisonRegen = d.mac.hi, attackSpeed = d.ac.lo - d.mac.lo)
      case 0 | 3 | 4 | 25 | 31 => base // 消耗品：AC/MAC 是药效，不是属性
      // 衣服、头盔，以及靴子（52）、腰带（54）：AC/MAC 就是防御、魔防（1.80 ApplyItemParameters 默认配置 boAddUserItemNewValue 关）
      case _       => base.copy(ac = d.ac, mac = d.mac)

  /**
   * 带极品附加的一件装备：照原版 GetUpgradeStdItem 把附加值加到物品表对应栏的上限（封顶 255），
   * 再按普通物品解读；武器攻速是真实值相加。强度不是属性，见 Durability.weaponWears。
   */
  def of(d: ItemDef, bonus: Option[ItemBonus]): ItemEffects = bonus match
    case None => of(d)
    case Some(ItemBonus.Weapon(dc, mc, sc, hit, speed, _)) =>
      val e = of(d.copy(dc = up(d.dc, dc), mc = up(d.mc, mc), sc = up(d.sc, sc), ac = up(d.ac, hit)))
      e.copy(attackSpeed = e.attackSpeed + speed)
    case Some(ItemBonus.Gear(ac, mac, dc, mc, sc)) =>
      of(d.copy(ac = up(d.ac, ac), mac = up(d.mac, mac), dc = up(d.dc, dc), mc = up(d.mc, mc), sc = up(d.sc, sc)))

  def of(d: ItemDef, it: ItemInstance): ItemEffects = of(d, it.bonus)

  private def up(s: Span, n: Int): Span = if n == 0 then s else s.copy(hi = math.min(255, s.hi + n))

/** 穿戴要求：要多少等级、攻击上限、魔法上限或道术上限。 */
enum Requirement(val label: String):
  case Level(value: Int) extends Requirement("等级")
  case Dc(value: Int)    extends Requirement("攻击上限")
  case Mc(value: Int)    extends Requirement("魔法上限")
  case Sc(value: Int)    extends Requirement("道术上限")

  def value: Int

object Requirement:
  def of(d: ItemDef): Requirement = d.need match
    case 1 => Dc(d.needLevel)
    case 2 => Mc(d.needLevel)
    case 3 => Sc(d.needLevel)
    case _ => Level(d.needLevel)

  /** 按要求取人物当前的值：等级或对应属性的上限。 */
  def current(r: Requirement, level: Int, stats: Stats): Int = r match
    case Level(_) => level
    case Dc(_)    => stats.dc.hi
    case Mc(_)    => stats.mc.hi
    case Sc(_)    => stats.sc.hi

  /** 这个人穿不上它的原因（等级/属性不够、性别不符；不看负重 #1169），穿得上为空。服务端穿戴用（高级物品提醒不看它，#1215）。 */
  def refusal(d: ItemDef, level: Int, stats: Stats, sex: Sex): Option[String] =
    val r    = of(d)
    val have = current(r, level, stats)
    if have < r.value then Some(s"需要${r.label} ${r.value}（你现在 $have）")
    else if dressSex(d).exists(_ != sex) then Some("性别不符")
    else None

/** 喝药：普通药分 Ticks 跳、每隔 TickMs 毫秒回一跳，每跳回 总量/Ticks（向上取整）；太阳水类（instant）一次回完。 */
object PotionRules:
  val Ticks  = 5
  val TickMs = 400
  def perTick(total: Int): Int = math.ceil(total / Ticks.toDouble).toInt

/**
 * 持久：存的是千分之一点（界面显示 存值/PerPoint 向上取整）。
 * 武器每次砍到目标有 1/WeaponWearOdds 的机会掉 WeaponWearPerHit；其他装备不掉持久。持久为 0 的装备不加任何属性。
 * 物品表里护身符、药粉的持久是一份的次数×PerUse（身上的按次叠，数量就是次数，见 Charges）。
 */
object Durability:
  val PerPoint         = 1000
  val WeaponWearOdds   = 3
  val WeaponWearPerHit = 1
  val PerUse           = 100

  /**
   * 这一刀武器磨不磨。原版每刀磨 max(0, Random(5)+2−强度) 点（强度 0 时平均 4），本服磨损比原版轻（平均每刀 1/3 点），
   * 强度（#1191 极品武器）按同样的比例减：磨损机会 = 1/3 × 原版这一刀的磨损点数 ÷ 4。
   */
  def weaponWears(strength: Int)(using rng: Rng): Boolean =
    rng.random(WeaponWearOdds * OrigMeanWear) < math.max(0, rng.random(5) + 2 - strength)

  /** 上面那个机会的精确值（说明用）。 */
  def weaponWearChance(strength: Int): Double =
    (0 until 5).map(r => math.max(0, r + 2 - strength)).sum / 5.0 / (WeaponWearOdds * OrigMeanWear)

  private val OrigMeanWear = 4

  def shown(raw: Int): Int    = math.ceil(raw / PerPoint.toDouble).toInt
  def usesLeft(raw: Int): Int = math.ceil(raw / PerUse.toDouble).toInt

/**
 * 护身符、药粉按「次」叠（用户 #1213：叠成一格、只显示还能用几次）：一格的数量（ItemInstance.count）就是剩余次数，
 * 用一次数量减 1、减到 0 就没了，和药一样走同一套叠放、拆分、数量框。原版一份符/毒的剩余次数记在持久里（持久 = 次数×PerUse），
 * 新拿到的和旧存档里的都由 settle 换成这种记法：数量 = 份数 × 每份剩余次数，持久固定为 PerUse（于是 settle 再做一次不变）。
 * 其他物品不受影响，一个就是一个。
 */
object Charges:
  /** 一份（物品表里的一个）能用几次：护身符 300、药粉 100–500；其他物品 1。 */
  def perPiece(d: ItemDef): Int = ItemKind.of(d) match
    case ItemKind.Amulet(_) => math.max(1, Durability.usesLeft(d.dura))
    case _                  => 1

  /** 换成按次计数（见上）；持久已用完的变成数量 0，由调用方丢掉。 */
  def settle(d: ItemDef, it: ItemInstance): ItemInstance = ItemKind.of(d) match
    case ItemKind.Amulet(_) =>
      it.copy(count = it.count * Durability.usesLeft(math.max(0, it.dura)), dura = Durability.PerUse, duraMax = Durability.PerUse)
    case _ => it

  /** 新的一份：护身符、药粉直接是「这份的次数」那么多。 */
  def fresh(uid: ItemUid, d: ItemDef): ItemInstance = settle(d, ItemInstance.fresh(uid, d))

/**
 * 商店买卖：买按表价（一份），卖给商店得表价的一半（向下取整）。
 * 按次叠的护身符、药粉（Charges）卖 n 次得 一份的卖价 × n ÷ 每份次数（向下取整），整份卖和原来一样。
 */
object Pricing:
  def buy(d: ItemDef): Int  = d.price
  /** 一份的卖价 */
  def sell(d: ItemDef): Int = d.price / 2
  /** 卖 n 个（按次叠的是 n 次）得多少 */
  def sell(d: ItemDef, n: Int): Int = (sell(d).toLong * n / Charges.perPiece(d)).toInt
  /** 界面上写单价：「每个 250」，按次叠的写「每 300 次 250」 */
  def sellUnit(d: ItemDef): String = Charges.perPiece(d) match
    case 1 => s"每个 ${sell(d)}"
    case k => s"每 $k 次 ${sell(d)}"

/** 男装/女装：StdMode 10 男、11 女，其余不限。 */
def dressSex(d: ItemDef): Option[Sex] = d.mode match
  case 10 => Some(Sex.Male)
  case 11 => Some(Sex.Female)
  case _  => None
