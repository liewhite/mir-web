package mir.server.game

import mir.core.Millis

/**
 * 定时全服公告注册/在线人数（#1244）：每 `everyMs`（默认 5 分钟）发一行「本服已注册 N 人，当前在线 M 人」到所有连接的聊天框
 * （和管理入口的公告同一种〖公告〗行）。游戏里没人在线的那一轮不发。只在游戏线程里用。
 *
 * registered：查已注册账号数（数据库 count，每 5 分钟一次，开销可忽略）。
 */
final class Headcount(registered: () => Int, start: Millis, everyMs: Long = Headcount.DefaultEveryMs):
  private var lastAt = start

  /** 到点且有人在线就给出这一轮的公告；到点没人也算过了这一轮（下一轮再看）。 */
  def due(now: Millis, online: Int): Option[String] =
    if now - lastAt < everyMs then None
    else
      lastAt = now
      Option.when(online > 0)(Headcount.text(registered(), online))

object Headcount:
  val DefaultEveryMs: Long = 5 * 60_000L

  def text(registered: Int, online: Int): String = s"本服已注册 $registered 人，当前在线 $online 人"
