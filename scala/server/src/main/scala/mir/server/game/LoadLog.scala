package mir.server.game

import mir.protocol.{Device, LoadStage, LoginPath}

/**
 * 页面报来的加载耗时写成一行日志（#1327，查新人流失用）。
 * #1345 起人在操作的时间（输账号密码、选人建角色）和下载分开打；没带 cookie 的打开标「新号注册」「没 cookie 登录」。
 */
object LoadLog:
  /** who：账号；playing：这个连接正在玩的角色（进图后才有） */
  def line(account: String, playing: Option[String], device: Device, stage: LoadStage): String =
    val who = playing.fold(account)(n => s"$n（$account）")
    stage match
      case LoadStage.Connected(login, dataMs) =>
        s"$who：${device.label}，连上服务器；${loginPart(login)}；读游戏数据 ${sec(dataMs)}"
      case LoadStage.Playable(pickMs, enterMs, pageMs) =>
        val pick = pickMs.fold("没经过选人（刷新后接着玩）")(p => s"选人画面停留 ${sec(p)}")
        s"$who：${device.label}，进图可玩；$pick，点进游戏到可玩 ${sec(enterMs)}，打开页面到可玩 ${sec(pageMs)}"

  private def loginPart(login: LoginPath): String = login match
    case LoginPath.Remembered(checkMs) => s"带 cookie 回来，打开页面到确认登录 ${sec(checkMs)}"
    case LoginPath.Typed(formMs, typingMs, openMs, register) =>
      val how = if register then "新号注册" else "没 cookie 登录"
      s"$how，打开页面到出登录框 ${sec(formMs)}（下载），输账号密码 ${sec(typingMs)}，提交到开门完 ${sec(openMs)}"

  /** 毫秒写成秒，一位小数 */
  private def sec(ms: Long): String = String.format(java.util.Locale.ROOT, "%.1f 秒", ms / 1000.0)
