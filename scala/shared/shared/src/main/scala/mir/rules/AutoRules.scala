package mir.rules

/**
 * 自动功能（设置里的开关，服务端 mir.world.AutoPlay 执行）的门槛和间隔。设置窗的说明文字也从这里取，改了说明跟着变。
 */
object AutoRules:
  /** 自动技能放不出来（没符、没蓝）时隔多久再试；不刷提示 */
  val CastRetryMs = 3000

  /** 自动治愈术：血（加上还没回完的治疗量）低于上限的这个百分比就治（#1307） */
  val HealBelowPercent = 70

  /** 自动治愈术两次之间至少隔多久：等上一发落到身上（SpellRules.HealingDelayMs）算进待回治疗量，免得重复治 */
  val HealRetryMs = SpellRules.HealingDelayMs + 400

  /** 自动隐身：出手（打怪、放技能）现身后等这么久才补，不在打架时来回隐身耗符（#1307） */
  val ReHideAfterMs = 5000

  /** 自动打怪找怪的范围（格，#1307） */
  val HuntRadius = 8

  /**
   * 持续施法（#1308）管的技能：对着选中的敌人放、放一次打一次的攻击技能。
   * 不含施毒术（自动施毒另管）、火墙（留在地上持续烧）、抗拒火环、困魔咒、诱惑之光这类不是用来打血的，
   * 也不含地狱火、地狱雷光（只打身前几格或身边，目标在远处时白耗蓝）。
   */
  val Sustained: Set[Skill] = Set(
    Skill.FireBall, Skill.GreatFireBall, Skill.Lightning, Skill.Thunder, Skill.FireBang, Skill.IceStorm, Skill.TurnUndead,
    Skill.SoulFireBall
  )

  /** 持续施法放不出来（没蓝、没符）停下后，自动打怪、还手隔多久再试这个技能（期间用普通攻击） */
  val BarrageRetryMs = 10000

  /** 自动施毒两次尝试之间至少隔多久（绿毒下完接着下红毒，不用等 CastRetryMs） */
  val PoisonRetryMs = 1000

  /** 自动打怪配持续施法时，还没亲手放过攻击技能就按这个顺序用学会的第一个 */
  val DefaultBarrage: List[Skill] = List(Skill.SoulFireBall, Skill.Thunder, Skill.GreatFireBall, Skill.FireBall)
