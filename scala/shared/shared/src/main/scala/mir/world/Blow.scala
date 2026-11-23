package mir.world

/** 一下攻击的伤害怎么被挡。 */
enum Defence:
  /** 扣防御（物理） */
  case Armor
  /** 扣魔防（魔法） */
  case MagicArmor
  /** 不扣（刺杀剑术隔一格的那下） */
  case Pierce
  /** 一半扣防御、一半扣魔防，两半相加（自爆） */
  case Blast

/**
 * 一下攻击（玩家的剑术和魔法、怪物的各种出招、火墙、野蛮冲撞都造这个值，交给 World.strike 结算）。
 *
 * @param power    威力（扣防之前）
 * @param accuracy 要判命中时攻击方的准确；None 必中
 * @param staggers 挨打的要不要做被打动作、被定住的要不要醒（火墙不会）
 */
final case class Blow(defence: Defence, power: Int, accuracy: Option[Int] = None, staggers: Boolean = true)

/** 一下攻击的结果。 */
enum StrikeResult:
  /** 没打：不是敌人、不在这张图上、已经死了 */
  case Ignored
  /** 没命中 */
  case Dodged
  /** 命中了，但全被防住（显示 miss） */
  case Blocked
  /** 打掉了 damage 血（魔法盾、红毒之前的数） */
  case Wounded(damage: Int)

  /** 打到身上了（被防住也算）：毒液、毒气的附带效果按这个判 */
  def connected: Boolean = this match
    case Blocked | Wounded(_) => true
    case Ignored | Dodged     => false
