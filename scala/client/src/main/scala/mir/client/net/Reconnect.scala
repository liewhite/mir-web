package mir.client.net

import mir.protocol.CloseCode

/**
 * 断线后怎么重连（#1290）：看服务端关连接时带的关闭码分三种。
 * - 服务器说它在重启（部署更新，CloseCode.Restarting）：提示「更新中」，每 0.5 秒试一次，新服一起来就连上；
 *   重启一般 3–5 秒，试满 1 分钟还没回来就当普通断线慢下来。重启期间连不上（关闭码 1006）不改变这个判断。
 * - 登录失效：提示重新登录（照旧也会重试，cookie 可能在别的标签页重新拿到了）。
 * - 其余（断网、服务端掉线判定）：0.5、1、2、4 秒，之后每 5 秒。
 */
enum Outage:
  case Restarting(since: Double)
  case Unauthorized
  case Lost

object Reconnect:
  val RestartPollMs    = 500
  val RestartPatienceMs = 60_000
  private val LostDelaysMs = Vector(500, 1000, 2000, 4000)
  private val LostMaxMs    = 5000

  /** 这次断开属于哪种：before 是上一次断开的判断（还没连上过），now 是本地时钟（毫秒） */
  def classify(code: Int, before: Option[Outage], now: Double): Outage = (code, before) match
    case (CloseCode.Restarting, Some(r: Outage.Restarting)) => r
    case (CloseCode.Restarting, _)                         => Outage.Restarting(now)
    case (1008 | 4401, _)                                  => Outage.Unauthorized
    case (_, Some(r: Outage.Restarting)) if now - r.since < RestartPatienceMs => r
    case _                                                 => Outage.Lost

  /** 第 attempt 次（0 起）重连前等多久 */
  def delayMs(o: Outage, attempt: Int, now: Double): Int = o match
    case Outage.Restarting(since) if now - since < RestartPatienceMs => RestartPollMs
    case _ => LostDelaysMs.lift(attempt).getOrElse(LostMaxMs)

  /** 断线时给玩家看的一句话 */
  def notice(o: Outage): String = o match
    case _: Outage.Restarting => "服务器更新中，几秒后自动回来"
    case Outage.Unauthorized  => "登录已失效"
    case Outage.Lost          => "连接断开，正在重连"
