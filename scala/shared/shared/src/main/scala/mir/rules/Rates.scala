package mir.rules

import mir.core.Exp
import mir.data.{DropDef, ItemDef, SkillDef, Span}
import mir.model.Job

/**
 * 倍率（用户 11-23 在 #1137 定：大幅降低升级难度、大幅提高爆率、装备爆率至少 20%、每只怪能爆什么不变）。
 * 原版数值在 game.json 里不动，只在这里换算。照 TS 版 game/src/world/rates.ts 移植。
 */
object Rates:
  /** 打怪经验倍数 */
  val exp: Int = 100
  /** 非装备物品（金币、药、书、材料）的掉落概率倍数，最高 100% */
  val drop: Int = 20
  /** 高级装备（要求 20 级以上，或要求攻击/魔法/道术）的掉落概率下限 */
  val highEquipMin: Double = 0.2
  val highEquipLevel: Int  = 20
  /** 诱惑之光收服概率倍数（用户 11-23 在 #1163 要求提升；原版 3 级技能对低级怪约 5%），最高 100% */
  val tame: Int = 10
  /** 战士的攻击、防御、魔防（上下限都乘，含装备加成，四舍五入）倍数（用户 11-24 在 #1176 定） */
  val warriorBody: Double = 1.5
  /** 技能每级要的熟练度除以这个数（向上取整、至少 1；用户 11-24 在 #1179 定） */
  val skillTrain: Int = 10

  /** 技能从 level（0..2）升到 level+1 实际要的熟练度：数据表数值 ÷ skillTrain。 */
  def trainNeed(d: SkillDef, level: Int): Int = math.max(1, (d.train(level) + skillTrain - 1) / skillTrain)

  /** 职业倍率作用到一段属性区间上（只有战士有）。 */
  def jobSpan(job: Job, s: Span): Span = job match
    case Job.Warrior =>
      def scale(v: Int) = math.floor(v * warriorBody + 0.5).toInt
      Span(scale(s.lo), scale(s.hi))
    case _ => s

  /** 一行爆率实际的掉落概率（0..1）。def 为空表示金币。 */
  def dropChance(d: DropDef, item: Option[ItemDef]): Double =
    val scaled = math.min(1.0, d.numerator.toDouble / d.denominator * drop)
    if item.exists(isHighEquip) then math.max(scaled, highEquipMin) else scaled

  /** 打死一只怪给的经验：数据表经验 × 复活折扣（复活一次减半），取整后乘经验倍数。 */
  def killExp(baseExp: Int, scale: Double): Exp = Exp(math.floor(baseExp * scale).toLong * exp)

  def isHighEquip(d: ItemDef): Boolean = ItemKind.of(d) match
    case ItemKind.Equip(_) => d.need != 0 || d.needLevel >= highEquipLevel
    case _                 => false
