package mir.client.net

import mir.core.Millis

/**
 * 估计服务器此刻的时钟：服务端给的动作开始时刻都是它的时钟，画动画、本地先走都要换算到它上面。
 * 进图时按收到的时刻粗定一次（当作没有延迟），之后对时（Ping/Pong）按往返时间的一半校正，只采信往返最快的几次。
 * local 是本地单调时钟（performance.now()，毫秒）。
 */
final class ServerClock:
  /** 服务器时钟 − 本地时钟 */
  private var offset: Option[Double] = None
  /** 采信过的最快往返 */
  private var bestRtt = Double.MaxValue

  def synced: Boolean = offset.isDefined

  /** 收到带服务器时刻的消息（EnterMap）：只在还没对过时用它粗定。 */
  def roughly(server: Millis, local: Double): Unit =
    if offset.isEmpty then offset = Some(server.value - local)

  /** 对时回应：sentLocal 是发 Ping 时的本地时钟，local 是收到的时刻。往返比最快的慢太多（网络抖）就不采信。 */
  def pong(sentLocal: Double, server: Millis, local: Double): Unit =
    val rtt = local - sentLocal
    if rtt >= 0 && rtt <= bestRtt + ServerClock.Jitter then
      bestRtt = math.min(bestRtt, rtt)
      offset = Some(server.value + rtt / 2 - local)

  /** 换了连接（断线重连、换服务器）：重新对。 */
  def reset(): Unit =
    offset = None
    bestRtt = Double.MaxValue

  def now(local: Double): Millis = Millis(math.round(local + offset.getOrElse(0.0)))

object ServerClock:
  /** 往返比最快的一次慢不超过这么多毫秒才采信 */
  val Jitter = 40.0
