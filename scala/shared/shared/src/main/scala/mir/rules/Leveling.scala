package mir.rules

import mir.core.Exp
import mir.data.Catalog

/** 等级与经验：当前等级和这一级攒了多少经验。 */
final case class Progress(level: Int, exp: Exp)

object Leveling:
  /**
   * 本服不设等级上限（用户 11-26 在 #1335 定）：这一级及以上，每升一级要的经验都和这一级一样（46 → 47 原版表里是 4.8 亿），
   * 之后线性升级、经验条长度不再变；原版表里 47 → 48 的 10 亿也不用。
   */
  val LinearFrom = 46

  /** 从 level 升到下一级要的经验：原版经验表（Catalog.expToNext），LinearFrom 级起都按 LinearFrom 级那档。 */
  def expToNext(catalog: Catalog, level: Int): Exp =
    // 表里 1 ~ LinearFrom 级都有（GameDataSpec 核对），取不到只可能是数据坏了：按永远升不上去处理，不让升级算出 0 无限连升
    catalog.expToNext(math.min(level, LinearFrom)).getOrElse(Exp(Long.MaxValue))

  /**
   * 加经验，够了就连升（world.ts gainExp）：每升一级扣掉这一级要的经验（expToNext，没有满级）。
   * 返回新的等级经验，升了几级由调用方比较 level 得出（升级回满血等副作用归世界）。
   * 以前卡在满级攒下的经验，下次得经验时一并按这个算连升。
   */
  def gain(catalog: Catalog, from: Progress, amount: Exp): Progress =
    if amount.value <= 0 then from
    else
      @annotation.tailrec
      def loop(p: Progress): Progress =
        val need = expToNext(catalog, p.level)
        if p.exp >= need then loop(Progress(p.level + 1, p.exp - need)) else p
      loop(from.copy(exp = from.exp + amount))
