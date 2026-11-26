package mir.client.game

import mir.protocol.{Device, LoadStage, LoginPath, SessionCmd}

/** 登录框的经过（本地时钟时刻，#1345）：框出现、点下成功那次提交；register 是注册新号。带 cookie 回来、没出框的没有这条。 */
final case class LoginForm(shownAt: Double, submittedAt: Double, register: Boolean)

/**
 * 这一页的加载耗时（#1327）：连上服务器时报一次「怎么登录的、读数据花多久」，第一次进图可玩时报一次「选人停留、进图、打开页面到可玩各花多久」，
 * 服务端记日志查新人流失。#1345 起把人在操作的时间（输账号密码、选人建角色）和下载分开报。
 * 时刻都是本地时钟（performance.now，从打开页面算起，毫秒）。
 *
 * form：出过登录框的经过（带 cookie 回来为空）；loggedInAt：登录好的时刻；dataAt：游戏数据和素材索引读完的时刻。
 */
final class LoadTimer(device: Device, form: Option[LoginForm], loggedInAt: Double, dataAt: Double):
  private var phase: LoadTimer.Phase = LoadTimer.Phase.Connecting

  /** 连上并比过版本：第一次连上时给出要报的一条（断线重连不再报）。 */
  def connected(): Option[SessionCmd] = phase match
    case LoadTimer.Phase.Connecting =>
      phase = LoadTimer.Phase.Picking(None)
      Some(SessionCmd.Loaded(device, LoadStage.Connected(login, ms(dataAt - loggedInAt))))
    case _ => None

  /** 选人画面出来了：记下第一次出来的时刻（建完角色再列一次不算）。 */
  def listed(now: Double): Unit = phase match
    case LoadTimer.Phase.Picking(None) => phase = LoadTimer.Phase.Picking(Some(now))
    case _                             => ()

  /** 发出进游戏：记下这一页第一次发的时刻。 */
  def entering(now: Double): Unit = phase match
    case LoadTimer.Phase.Picking(listedAt) => phase = LoadTimer.Phase.Entering(now, listedAt.map(now - _))
    case _                                 => ()

  /** 进入画面：第一次可玩时给出要报的一条。 */
  def arrived(now: Double): Option[SessionCmd] = phase match
    case LoadTimer.Phase.Entering(at, picked) =>
      phase = LoadTimer.Phase.Done
      Some(SessionCmd.Loaded(device, LoadStage.Playable(picked.map(ms), ms(now - at), ms(now))))
    case _ => None

  private def login: LoginPath = form match
    case None    => LoginPath.Remembered(ms(loggedInAt))
    case Some(f) => LoginPath.Typed(ms(f.shownAt), ms(f.submittedAt - f.shownAt), ms(loggedInAt - f.submittedAt), f.register)

  private def ms(d: Double): Long = math.max(0L, math.round(d))

object LoadTimer:
  private enum Phase:
    /** 还没连上服务器 */
    case Connecting
    /** 连上了、报过一次，等进游戏；listedAt 选人画面第一次出来的时刻（刷新后直接接着玩的没有） */
    case Picking(listedAt: Option[Double])
    /** 发了进游戏，等地图下完；picked 在选人画面停留了多久 */
    case Entering(at: Double, picked: Option[Double])
    /** 两次都报过了 */
    case Done
