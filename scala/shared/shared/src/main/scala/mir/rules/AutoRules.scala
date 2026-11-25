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
