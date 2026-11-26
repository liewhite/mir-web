package mir.client.game

import mir.protocol.{Device, LoadStage, SessionCmd}

/**
 * 这一页的加载耗时（#1327）：连上服务器时报一次「登录、读数据各花多久」，第一次进图可玩时报一次「进图、打开页面到可玩各花多久」，
 * 服务端记日志查新人流失。时刻都是本地时钟（performance.now，从打开页面算起，毫秒）。
 *
 * loggedInAt：登录好的时刻；dataAt：游戏数据和素材索引读完的时刻。
 */
final class LoadTimer(device: Device, loggedInAt: Double, dataAt: Double):
  private var phase: LoadTimer.Phase = LoadTimer.Phase.Connecting

  /** 连上并比过版本：第一次连上时给出要报的一条（断线重连不再报）。 */
  def connected(): Option[SessionCmd] = phase match
    case LoadTimer.Phase.Connecting =>
      phase = LoadTimer.Phase.Picking
      Some(SessionCmd.Loaded(device, LoadStage.Connected(ms(loggedInAt), ms(dataAt - loggedInAt))))
    case _ => None

  /** 发出进游戏：记下这一页第一次发的时刻。 */
  def entering(now: Double): Unit = phase match
    case LoadTimer.Phase.Picking => phase = LoadTimer.Phase.Entering(now)
    case _                       => ()

  /** 进入画面：第一次可玩时给出要报的一条。 */
  def arrived(now: Double): Option[SessionCmd] = phase match
    case LoadTimer.Phase.Entering(at) =>
      phase = LoadTimer.Phase.Done
      Some(SessionCmd.Loaded(device, LoadStage.Playable(ms(now - at), ms(now))))
    case _ => None

  private def ms(d: Double): Long = math.max(0L, math.round(d))

object LoadTimer:
  private enum Phase:
    /** 还没连上服务器 */
    case Connecting
    /** 连上了、报过一次，等进游戏 */
    case Picking
    /** 发了进游戏，等地图下完 */
    case Entering(at: Double)
    /** 两次都报过了 */
    case Done
