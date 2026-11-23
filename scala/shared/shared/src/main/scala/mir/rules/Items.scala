package mir.rules

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
  /** 护身符（poison 为空）、灰色药粉（绿毒）、黄色药粉（红毒）：戴在左手镯位 */
  case Amulet(poison: Option[PoisonKind])
  case Material
  case Other

object ItemKind:
  /** 一个捆装药拆出几个 */
  val BundleSize = 6

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
   * 同名叠成一格、不设上限（用户 #1159 定，原版不叠）：药、卷轴、技能书、捆装药。
   * 装备（每件持久不同）、护身符和毒粉（剩余次数记在持久里）、矿石等材料（纯度不同）和其他都不叠。
   */
  def stacks(k: ItemKind): Boolean = k match
    case Potion(_, _, _) | Scroll(_) | Book(_, _) | Bundle(_) => true
    case Equip(_) | Amulet(_) | Material | Other            => false

  def stacks(d: ItemDef): Boolean = stacks(of(d))

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
      case _       => base.copy(ac = d.ac, mac = d.mac)

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

/** 喝药：普通药分 Ticks 跳、每隔 TickMs 毫秒回一跳，每跳回 总量/Ticks（向上取整）；太阳水类（instant）一次回完。 */
object PotionRules:
  val Ticks  = 5
  val TickMs = 400
  def perTick(total: Int): Int = math.ceil(total / Ticks.toDouble).toInt

/**
 * 持久：存的是千分之一点（界面显示 存值/PerPoint 向上取整）。
 * 武器每次砍到目标有 1/WeaponWearOdds 的机会掉 WeaponWearPerHit；其他装备不掉持久。持久为 0 的装备不加任何属性。
 * 护身符、药粉的持久是剩余次数：每用一次扣 PerUse。
 */
object Durability:
  val PerPoint         = 1000
  val WeaponWearOdds   = 3
  val WeaponWearPerHit = 1
  val PerUse           = 100

  def shown(raw: Int): Int    = math.ceil(raw / PerPoint.toDouble).toInt
  def usesLeft(raw: Int): Int = math.ceil(raw / PerUse.toDouble).toInt

/** 商店买卖：买按表价，卖给商店得表价的一半（向下取整）。 */
object Pricing:
  def buy(d: ItemDef): Int  = d.price
  def sell(d: ItemDef): Int = d.price / 2

/** 男装/女装：StdMode 10 男、11 女，其余不限。 */
def dressSex(d: ItemDef): Option[Sex] = d.mode match
  case 10 => Some(Sex.Male)
  case 11 => Some(Sex.Female)
  case _  => None
