package mir.rules

/**
 * 动作的节奏和距离：服务端按它判定、客户端按它预测（本地先走、攻击键连发、冲撞动画），两边共用。
 * 时长单位毫秒。
 */
object Pace:
  val WalkMs      = 600
  val RunMs       = 600
  val HitAnimMs   = 510
  val SpellAnimMs = 600
  /** 放完一个魔法到能放下一个 */
  val SpellIntervalMs = SpellRules.SpellIntervalMs
  /** 野蛮冲撞每冲一格的时长（原版跑步半圈）、冲完的晕眩（原版 g_dwDizzyDelayTime） */
  val RushStepMs  = RunMs / 2
  val RushDizzyMs = 300
  /** 被推后退一格的时长（原版倒放走路、跳帧，约走路的一半）；怪被推后多歇的时间（CharPushed 的 WalkTick + 800） */
  val BackstepMs    = WalkMs / 2
  val PushedPauseMs = 800
  /** 离 NPC 几格以内能对话、买卖 */
  val NpcRange = 6
