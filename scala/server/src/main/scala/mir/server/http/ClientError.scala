package mir.server.http

import zio.json.*

/**
 * 玩家浏览器上报的报错（`POST api/client-error`，#1228）：客户端的 window.onerror、未处理的 Promise 失败、主循环里接住的异常、
 * 素材 404。服务端把它打成一行日志加缩进的堆栈，巡检（watch/mir-health/check.py）按行首「客户端报错」发现。
 *
 * 日志是给人和巡检看的，上报内容是玩家浏览器给的、不可信：每个字段去掉换行等控制字符（防止伪造日志行），截短。
 */
final case class ClientError(
    message: String,
    stack: Option[String] = None,
    /** 当时在玩的角色名（还没进游戏为空） */
    char: Option[String] = None,
    /** 页面版本（构建时的 git 提交） */
    version: Option[String] = None
)

object ClientError:
  given JsonDecoder[ClientError] = DeriveJsonDecoder.gen

  /** 请求体上限（字节）：超过直接拒（413），不读进日志 */
  val MaxBody    = 16 * 1024
  val MaxMessage = 300
  val MaxFrames  = 8
  val MaxFrame   = 200

  /**
   * 日志正文：首行 `客户端报错：消息 ｜ 账号 a 角色 c 版本 v ｜ UA`，下面每行一帧 `    at …`（和 JVM 堆栈同样缩进，
   * 巡检把它们当作这条报错的堆栈）。消息放最前面，巡检按首行前 160 字归类，同一个 bug 不同账号算一类。
   */
  def render(e: ClientError, account: String, userAgent: String): String =
    val who   = s"账号 $account 角色 ${e.char.map(oneLine(_, 20)).filter(_.nonEmpty).getOrElse("-")} 版本 ${e.version.map(oneLine(_, 40)).filter(_.nonEmpty).getOrElse("-")}"
    val head  = s"客户端报错：${oneLine(e.message, MaxMessage)} ｜ $who ｜ ${oneLine(userAgent, 200)}"
    val frames = e.stack.toList
      .flatMap(_.linesIterator)
      .map(l => oneLine(l, MaxFrame).stripPrefix("at ").trim)
      .filter(_.nonEmpty)
      .take(MaxFrames)
    (head :: frames.map(f => s"    at $f")).mkString("\n")

  /** 控制字符（含换行）换成空格，截到 max 字。 */
  private[http] def oneLine(s: String, max: Int): String =
    s.map(c => if c.isControl then ' ' else c).trim.take(max)
