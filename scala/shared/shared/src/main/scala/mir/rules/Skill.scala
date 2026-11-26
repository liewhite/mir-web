package mir.rules

import mir.core.SkillId
import mir.data.SkillDef

/** 一个技能怎么用：决定占不占技能环、耗不耗魔法、冷却怎么算。 */
enum SkillUse:
  /** 学会就一直生效，不占技能环和快捷键（基本剑术、精神力战法、攻杀剑术；刺杀剑术默认开着，放一下是开关） */
  case Passive
  /** 开关：放一下开、再放一下关，开关本身不耗魔法（半月弯刀） */
  case Toggle
  /** 蓄力：不进施法动作，下一刀生效；蓄好后 cooldownMs 内不能再蓄（烈火剑法） */
  case Charge(cooldownMs: Int)
  /** 人物自己做一个动作（野蛮冲撞），cooldownMs 一次 */
  case Act(cooldownMs: Int)
  /** 进施法动作的魔法，冷却按技能表 */
  case Spell

/** 技能对谁放：客户端据此决定把哪个锁定对象当目标发出（服务端按各技能处理器再判）。 */
enum SkillAim:
  /** 打敌人：锁定的敌人，没锁就最近的怪（原版操作 #1353：鼠标指着的敌人，其次锁定的，都没有就打向指着的格子） */
  case Enemy
  /** 给自己人：锁定的自己人（玩家、宝宝），没锁就是自己（原版 MagicTarget 取点中的任意对象） */
  case Friend
  /**
   * 放在一个格子上（火墙、困魔咒）：原版操作（#1353）放在鼠标指着的对象脚下或指着的格子，不管锁定；
   * 触屏没有鼠标，和 Enemy 一样放在锁定的或最近的怪脚下
   */
  case Ground

/**
 * 做好了的技能（名字代替数据表里的编号）。每个技能的数值（威力、耗蓝、熟练度）仍在数据表 SkillDef，
 * 施放效果在 mir.world.skills 里按技能注册（加一个技能：这里加一项，再写它的处理器并在 SkillHandlers 注册）。
 */
enum Skill(val code: Int, val use: SkillUse, val aim: SkillAim = SkillAim.Enemy):
  case FireBall       extends Skill(1, SkillUse.Spell)   // 火球术
  case Healing        extends Skill(2, SkillUse.Spell, SkillAim.Friend)   // 治愈术
  case OneSword       extends Skill(3, SkillUse.Passive) // 基本剑术
  case SpiritSword    extends Skill(4, SkillUse.Passive) // 精神力战法
  case GreatFireBall  extends Skill(5, SkillUse.Spell)   // 大火球
  case Poisoning      extends Skill(6, SkillUse.Spell)   // 施毒术
  case PowerHit       extends Skill(7, SkillUse.Passive) // 攻杀剑术
  case Repel          extends Skill(8, SkillUse.Spell)   // 抗拒火环
  case HellFire       extends Skill(9, SkillUse.Spell)   // 地狱火
  case Lightning      extends Skill(10, SkillUse.Spell)  // 疾光电影
  case Thunder        extends Skill(11, SkillUse.Spell)  // 雷电术
  case LongHit        extends Skill(12, SkillUse.Passive) // 刺杀剑术
  case SoulFireBall   extends Skill(13, SkillUse.Spell)  // 灵魂火符
  case SoulShield     extends Skill(14, SkillUse.Spell, SkillAim.Friend)  // 幽灵盾
  case HolyArmor      extends Skill(15, SkillUse.Spell, SkillAim.Friend)  // 神圣战甲术
  case HolyCurtain    extends Skill(16, SkillUse.Spell, SkillAim.Ground)  // 困魔咒
  case SummonSkeleton extends Skill(17, SkillUse.Spell)  // 召唤骷髅
  case Invisible      extends Skill(18, SkillUse.Spell)  // 隐身术
  case GroupInvisible extends Skill(19, SkillUse.Spell)  // 集体隐身术
  case Tame           extends Skill(20, SkillUse.Spell)  // 诱惑之光
  case SpaceMove      extends Skill(21, SkillUse.Spell)  // 瞬息移动
  case FireWall       extends Skill(22, SkillUse.Spell, SkillAim.Ground)  // 火墙
  case FireBang       extends Skill(23, SkillUse.Spell)  // 爆裂火焰
  case ThunderStorm   extends Skill(24, SkillUse.Spell)  // 地狱雷光
  case WideHit        extends Skill(25, SkillUse.Toggle) // 半月弯刀
  case FireSword      extends Skill(26, SkillUse.Charge(SwordRules.FireCooldownMs)) // 烈火剑法
  case Rush           extends Skill(27, SkillUse.Act(SpellRules.RushCooldownMs))    // 野蛮冲撞
  case ShowHp         extends Skill(28, SkillUse.Spell)  // 心灵启示
  case GroupHealing   extends Skill(29, SkillUse.Spell, SkillAim.Friend)  // 群体治疗术
  case SummonShinsu   extends Skill(30, SkillUse.Spell)  // 召唤神兽
  case MagicShield    extends Skill(31, SkillUse.Spell)  // 魔法盾
  case TurnUndead     extends Skill(32, SkillUse.Spell)  // 圣言术
  case IceStorm       extends Skill(33, SkillUse.Spell)  // 冰咆哮

  /** 数据表里的编号 */
  def id: SkillId = SkillId(code)

  def passive: Boolean = use == SkillUse.Passive

  /** 放一下扣不扣魔法（被动、开关不扣） */
  def costsMana: Boolean = use match
    case SkillUse.Passive | SkillUse.Toggle => false
    case _                                  => true

  /** 放成功后同一个技能多久才能再放（毫秒）；None 不用等。魔法按表里的间隔，至少一个施法动作。 */
  def cooldownMs(d: SkillDef): Option[Int] = use match
    case SkillUse.Passive | SkillUse.Toggle         => None
    case SkillUse.Charge(ms)                        => Some(ms)
    case SkillUse.Act(ms)                           => Some(ms)
    case SkillUse.Spell                             => Option.when(d.delay > 0)(math.max(SpellRules.SpellIntervalMs, d.delay))

object Skill:
  private val byId: Map[SkillId, Skill] = values.map(s => s.id -> s).toMap

  /** 数据表编号对应的技能；还没做的为 None。 */
  def of(id: SkillId): Option[Skill] = byId.get(id)

  /** 被动技能：学会就生效，不占技能环和快捷键。 */
  def isPassive(id: SkillId): Boolean = of(id).exists(_.passive)

  /** 魔法耗蓝：spell/4×(等级+1) + defSpell/4×(等级+1)，取整为 0 时用 spell。 */
  def manaCost(d: SkillDef, level: Int): Int =
    val v = math.floor(d.spell / 4.0 * (level + 1) + d.defSpell / 4.0 * (level + 1) + 0.5).toInt
    if v == 0 then d.spell else v
