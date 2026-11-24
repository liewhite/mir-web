package mir.rules

import mir.core.ItemName
import mir.data.{ItemDef, Span}
import mir.model.*

/**
 * 高级物品提醒（用户 #1200）：一件装备换下身上同部位那件后，本职业的主属性（战士攻击、法师魔法、道士道术）
 * 是不是净提升。比的是「下限+上限」之和（含极品附加，#1191）。不看现在穿不穿得上等级/属性要求（用户 #1215：只要有净收益就提示，
 * 要求写在物品说明里），只排除性别不符的衣服（永远穿不上）。
 * 戒指、手镯有左右两个位置时和较弱的那只比（空位算 0）；戴着护身符/毒粉的手镯位不算（不会拿装备去换符）。
 * 持久为 0 的身上装备不加属性（Vitals 同此），按 0 算。
 */
object Upgrade:
  /** 穿的人：职业、性别、身上装备。 */
  final case class Wearer(job: Job, sex: Sex, worn: Map[EquipSlot, ItemInstance])

  /** 提升：主属性叫什么、和身上哪件比（空位为空）、下限+上限多了多少（总是正数）。 */
  final case class Gain(stat: String, over: Option[ItemName], amount: Int)

  /** 职业的主属性：名字和从装备效果里取它的办法。 */
  def mainStat(job: Job): (String, ItemEffects => Span) = job match
    case Job.Warrior => ("攻击", _.dc)
    case Job.Wizard  => ("魔法", _.mc)
    case Job.Taoist  => ("道术", _.sc)

  /**
   * 这件（表里的物品 d 加极品附加 bonus）对 w 是不是提升；不是装备、性别不符、不比身上的强都为空。
   * lookup 按名字查物品表（身上那件的数值）。
   */
  def gain(w: Wearer, d: ItemDef, bonus: Option[ItemBonus], lookup: ItemName => Option[ItemDef]): Option[Gain] =
    val (label, pick) = mainStat(w.job)
    def total(e: ItemEffects): Int = { val s = pick(e); s.lo + s.hi }
    ItemKind.of(d) match
      case ItemKind.Equip(slots) if !dressSex(d).exists(_ != w.sex) =>
        val mine = total(ItemEffects.of(d, bonus))
        // 每个能放的位置上现在那件：(名字, 主属性)；空位 (None, 0)
        val occupied = slots.map(s => w.worn.get(s).flatMap(it => lookup(it.name).map(it -> _)) match
          case None => Some(None -> 0)
          case Some((it, cd)) => ItemKind.of(cd) match
            case ItemKind.Equip(_) => Some(Some(it.name) -> (if it.dura > 0 then total(ItemEffects.of(cd, it.bonus)) else 0))
            case _                 => None // 护身符、毒粉占着的手镯位
        ).flatten
        occupied.minByOption(_._2).map((over, theirs) => Gain(label, over, mine - theirs)).filter(_.amount > 0)
      case _ => None
