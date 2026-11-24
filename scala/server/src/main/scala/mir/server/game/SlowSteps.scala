package mir.server.game

/**
 * 游戏一步卡顿记日志（#1228）：主循环每 50 毫秒一步，一步超过 `thresholdMs` 说明服务器卡了（玩家会看到怪和人停顿、瞬移）。
 * 记一行 `游戏一步耗时 N 毫秒`，两行之间至少隔 `quietMs`；中间没记的慢步数一并写上，免得一直卡时刷满日志。
 * 只在游戏线程里用。
 */
final class SlowSteps(thresholdMs: Long = 500, quietMs: Long = 60_000):
  private var lastLoggedAt = Option.empty[Long]
  private var muted        = 0

  /** 这一步用了 tookMs（结束时刻 nowMs）；该记日志就给出那一行。 */
  def observe(tookMs: Long, nowMs: Long, online: => Int): Option[String] =
    if tookMs <= thresholdMs then None
    else if lastLoggedAt.exists(nowMs - _ < quietMs) then
      muted += 1
      None
    else
      val extra = if muted > 0 then s"，上次记录之后另有 $muted 步超过 $thresholdMs 毫秒" else ""
      lastLoggedAt = Some(nowMs)
      muted = 0
      Some(s"游戏一步耗时 $tookMs 毫秒（在线 $online 人$extra）")
