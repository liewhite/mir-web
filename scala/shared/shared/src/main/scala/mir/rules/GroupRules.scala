package mir.rules

import mir.core.Exp

/**
 * 组队（#1220，照原版 M2Server ObjPlay.pas）：人数上限和打怪分经验（TPlayObject.GainExp）。
 * 经验倍率照旧在 Rates.killExp 里乘，这里只管分。
 */
object GroupRules:
  /** 一队最多几人：原版 GroupMembersMax 10 是「已有超过 10 人就加不进」，连队长 11 人 */
  val MaxMembers = 11
  /** 一起分经验的队友：同一张图、活着、离打死怪的人（宝宝打死的算主人）横竖都不超过这么多格 */
  val ShareRange = 12
  /** 分经验的总经验倍数，下标是一起分的人数（原版 bonus 表：2 人 ×1.3、3 人 ×1.4 … 11 人 ×2.2） */
  val Bonus: Vector[Double] = Vector(1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2, 2.1, 2.2)

  /**
   * 一只怪的经验怎么分：levels 是一起分的各人等级（含打死怪的人）。
   * 不到 2 人返回 None（打死怪的人照常全拿）；否则总经验按人数加成后，各人按等级占比分（原版 HighLevelKillMonFixExp 默认关）。
   */
  def share(exp: Exp, levels: Vector[Int]): Option[Vector[Exp]] =
    val n   = levels.size
    val sum = levels.sum
    Option.when(n > 1 && sum > 0) {
      val total = math.round(exp.value * Bonus.lift(n).getOrElse(Bonus.last)).toDouble
      levels.map(lv => Exp(math.round(total / sum * lv)))
    }
