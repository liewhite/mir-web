package mir.rules

/**
 * 战士剑术（和道士精神力战法）的数值：出处 ref/Mir176 PlayObject.Attack.cs、PlayObject.RecalcHitSpeed。
 * 技能等级 level 为 0–3；世界结算和技能说明都从这里取（#1165）。
 */
object SwordRules:
  private def round(x: Double): Int = math.floor(x + 0.5).toInt

  /** 基本剑术：准确 +等级×3。 */
  def oneSwordHit(level: Int): Int = round(3.0 * level)

  /** 精神力战法：准确 +等级×8/3（四舍五入）。 */
  def spiritSwordHit(level: Int): Int = round(8.0 / 3 * level)

  /** 攻杀剑术：准确 +等级。 */
  def powerHitHit(level: Int): Int = level

  /** 攻杀剑术：拿着武器每砍 (7 - 等级) 刀，其中随机一刀是攻杀。 */
  def powerHitEvery(level: Int): Int = 7 - level

  /** 攻杀那一刀的威力：+ (5 + 等级)（HitPlus）。 */
  def powerHitPower(power: Int, level: Int): Int = power + 5 + level

  /** 刺杀剑术最高等级（原版 TrainLv）。 */
  val LongHitMaxLevel = 3

  /** 刺杀隔一格那下的威力 = 这一刀 ÷(最高等级+2)×(等级+2)：0 级 40%、3 级 100%（PlayObject.Attack.cs:150）。 */
  def longPower(power: Int, level: Int): Int = round(power / (LongHitMaxLevel + 2.0) * (level + 2))

  /** 半月弯刀每刀耗的魔法。 */
  val WideHitMp = 3

  /** 智能半月（#1273）：身边至少几只怪才自动出半月。 */
  val SmartWideHitMin = 2

  /** 半月弯刀：身边另外 7 格每格的威力 = 这一刀威力 ×(等级+1)/4 + 1。 */
  def widePower(power: Int, level: Int): Int = round(power * (level + 1) / 4.0) + 1

  /** 烈火剑法那一刀的威力：×(1 + (4 + 等级×4)/10)（HitDouble）。 */
  def firePower(power: Int, level: Int): Int = power + round(power / 100.0 * (4 + level * 4) * 10)

  /** 烈火剑法放完后多久才能再蓄力（毫秒）。 */
  val FireCooldownMs = 10_000
