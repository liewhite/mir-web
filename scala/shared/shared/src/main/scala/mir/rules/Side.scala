package mir.rules

/** 阵营：玩家和宝宝、野怪、守卫、发狂的怪（和谁都是敌人，原版 CrazyMode）。NPC 不属于任何阵营。 */
enum Side:
  case Players, Wild, Guards, Crazy

object Side:
  /** 两个阵营互为敌人：不同阵营，但守卫和玩家不互打（不做 PK）。 */
  def hostile(a: Side, b: Side): Boolean =
    a != b && !Set(a, b).subsetOf(Set(Players, Guards))
