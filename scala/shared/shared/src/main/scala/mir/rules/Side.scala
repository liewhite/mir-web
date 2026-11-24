package mir.rules

/** 阵营：玩家和宝宝、野怪、守卫、发狂的怪（和谁都是敌人，原版 CrazyMode）。NPC 不属于任何阵营。 */
enum Side:
  case Players, Wild, Guards, Crazy

object Side:
  /**
   * a 阵营会不会把 b 阵营当目标（有方向）：
   *  - 同阵营不打（玩家之间的 PK 另见 Look.duel，#1225）；守卫和玩家（含宝宝）平时不互打
   *    （守卫砍红名见 Look.hostile；守卫和宝宝另见 Hostility.guardVsPet）；
   *  - 野怪不打守卫（#1217，原版 ObjBase.IsAttackTarget：没主人的怪只认玩家、宝宝），守卫照样打野怪；
   *  - 发狂的怪谁都打。
   */
  def attacks(a: Side, b: Side): Boolean = (a, b) match
    case _ if a == b                           => false
    case (Players, Guards) | (Guards, Players) => false
    case (Wild, Guards)                        => false
    case _                                     => true
