package mir.server.game

import mir.protocol.{Device, LoadStage}

/** 页面报来的加载耗时写成一行日志（#1327，查新人流失用）。 */
object LoadLog:
  /** who：账号；playing：这个连接正在玩的角色（进图后才有） */
  def line(account: String, playing: Option[String], device: Device, stage: LoadStage): String =
    val who = playing.fold(account)(n => s"$n（$account）")
    stage match
      case LoadStage.Connected(loginMs, dataMs) =>
        s"$who：${device.label}，连上服务器；打开页面到登录好 ${sec(loginMs)}，读游戏数据 ${sec(dataMs)}"
      case LoadStage.Playable(enterMs, pageMs) =>
        s"$who：${device.label}，进图可玩；点进游戏到可玩 ${sec(enterMs)}，打开页面到可玩 ${sec(pageMs)}"

  /** 毫秒写成秒，一位小数 */
  private def sec(ms: Long): String = String.format(java.util.Locale.ROOT, "%.1f 秒", ms / 1000.0)
