package mir.rules

import mir.model.ActKind

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
  /**
   * 玩家指令早到这么多毫秒以内也算数（网络抖动）。#1319 起提前量不累积（Actor.busyFor），作弊最多一次性快这么多，
   * 所以放宽到 300（原 200），网络忽快忽慢、砍完马上走时少被拒（用户 11-26 要求尽量不影响体验）。
   */
  val EarlyMs = 300

  /** 出一刀（含挖矿）后多久才能接受下一个动作：动画和攻击间隔取长的 */
  def hitBusyMs(hitInterval: Int): Int = math.max(HitAnimMs, hitInterval)

  /**
   * 玩家做了这个动作后多久才能接受下一个动作（服务端按它定「忙到」，客户端按它排下一刀、下一步，#1321）；
   * None：除了动画本身没有额外的忙（走跑、被打等）。
   */
  def busyMs(kind: ActKind, hitInterval: Int): Option[Int] = kind match
    case ActKind.Attack | ActKind.Heavy => Some(hitBusyMs(hitInterval))
    case ActKind.Spell                  => Some(SpellIntervalMs)
    case _                              => None

  /** 野蛮冲撞每冲一格的时长（原版跑步半圈）、冲完的晕眩（原版 g_dwDizzyDelayTime） */
  val RushStepMs  = RunMs / 2
  val RushDizzyMs = 300
  /** 被推后退一格的时长（原版倒放走路、跳帧，约走路的一半）；怪被推后多歇的时间（CharPushed 的 WalkTick + 800） */
  val BackstepMs    = WalkMs / 2
  val PushedPauseMs = 800
  /** 离 NPC 几格以内能对话、买卖 */
  val NpcRange = 6
