package mir.client.game

import mir.core.*
import mir.model.ActKind
import mir.protocol.ActionView
import mir.rules.Pace

/**
 * 自己什么时候能做下一个动作（出刀、走、跑），照服务端同样的「忙到」规则算（#1321）。
 *
 * 服务端收下一刀后要忙 max(动画, 攻击间隔)、收一个魔法后忙 SpellIntervalMs，忙的时候来的指令被丢掉（攻击）或拉回（走路）；
 * 只看动画会早发：普攻 600 毫秒那刀被丢、要等下一次，砍完马上走被拉回。
 *
 * 两样都看、取晚的：
 * - 自己发的攻击/魔法：从发出时刻起算（服务端从收到时刻起算，网络延迟稳定时两边节奏一样）。
 * - 视野里自己正在做的动作（含服务端替你砍的「按住攻击」、自动放的魔法）：从它在服务端开始的时刻起算，
 *   减去服务端允许的早到量、再留 LagMs 余地。自己发的那个动作这条算出来更早（单程延迟不超过 Pace.EarlyMs − LagMs 时），不拖慢节奏。
 * 不可变：每个操作返回新状态。
 */
final case class SelfPace(
    /** 自己最近发出的攻击/魔法按服务端规则忙到的时刻（服务端时钟） */
    sentUntil: Millis = Millis.zero
):
  /** 刚发出一个会让服务端忙 ms 毫秒的动作 */
  def sent(now: Millis, ms: Long): SelfPace = copy(sentUntil = now + ms)

  /** 视野里自己正在做的动作 a 之后，最早什么时候能发下一个动作。 */
  def readyAt(a: ActionView, hitInterval: Int): Millis =
    val shown = a.kind match
      // 走跑由 SelfMotion 管（等服务端回音），这里不拦
      case ActKind.Walk | ActKind.Run => Millis.zero
      case _                          => a.startedAt + a.duration
    val server = Pace.busyMs(a.kind, hitInterval).map(ms => a.startedAt + (ms - Pace.EarlyMs + SelfPace.LagMs).toLong)
    List(Some(sentUntil), Some(shown), server).flatten.max

  def ready(a: ActionView, hitInterval: Int, now: Millis): Boolean = now >= readyAt(a, hitInterval)

object SelfPace:
  /**
   * 服务端早到余量里留给误差的部分：服务端每 50 毫秒走一步，收到的指令按上一步的时刻处理（看起来早到了最多 50 毫秒），
   * 加上对时误差。
   */
  val LagMs = 100
