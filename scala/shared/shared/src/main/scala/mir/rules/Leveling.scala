package mir.rules

import mir.core.Exp
import mir.data.Catalog

/** 等级与经验：当前等级和这一级攒了多少经验。 */
final case class Progress(level: Int, exp: Exp)

object Leveling:
  /**
   * 加经验，够了就连升（world.ts gainExp）：每升一级扣掉这一级要的经验；满级（表里没有下一级）后经验只累加。
   * 返回新的等级经验，升了几级由调用方比较 level 得出（升级回满血等副作用归世界）。
   */
  def gain(catalog: Catalog, from: Progress, amount: Exp): Progress =
    if amount.value <= 0 then from
    else
      @annotation.tailrec
      def loop(p: Progress): Progress = catalog.expToNext(p.level) match
        case Some(need) if p.exp >= need => loop(Progress(p.level + 1, p.exp - need))
        case _                           => p
      loop(from.copy(exp = from.exp + amount))
