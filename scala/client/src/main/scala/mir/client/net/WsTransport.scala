package mir.client.net

import mir.protocol.{ClientCmd, ServerMsg, SessionCmd}
import org.scalajs.dom
import zio.json.*

/**
 * WebSocket 连服务端（#1154 约定：页面同目录下的 ws，升级时按登录 cookie 认人；一帧一条 JSON）。
 * 断了自动重连（节奏和提示见 Reconnect：服务器重启时每 0.5 秒一次），重连期间发的指令丢掉（游戏指令都是「此刻的意图」，重发旧的反而错）。
 */
final class WsTransport(listener: LinkEvent => Unit) extends Transport:
  private var socket  = Option.empty[dom.WebSocket]
  private var retries = 0
  /** 这次断线是哪种（连上就清掉） */
  private var outage  = Option.empty[Outage]
  private var closed  = false

  connect()

  private def url: String =
    val u = new dom.URL("ws", dom.window.location.href)
    u.protocol = if dom.window.location.protocol == "https:" then "wss:" else "ws:"
    u.href

  private def connect(): Unit =
    val ws = new dom.WebSocket(url)
    socket = Some(ws)
    ws.onopen = _ =>
      retries = 0
      outage = None
      listener(LinkEvent.Up)
    ws.onmessage = e =>
      e.data match
        case s: String =>
          s.fromJson[ServerMsg] match
            case Right(m)  => listener(LinkEvent.Received(m))
            case Left(err) =>
              dom.console.warn("看不懂的服务端消息", err, s.take(200))
              mir.client.ErrorReport.notice(s"看不懂的服务端消息：$err", s.take(200))
        case _ => ()
    ws.onclose = e =>
      socket = None
      if !closed then
        val now = dom.window.performance.now()
        val o   = Reconnect.classify(e.code, outage, now)
        outage = Some(o)
        listener(LinkEvent.Down(Reconnect.notice(o)))
        val wait = Reconnect.delayMs(o, retries, now)
        retries += 1
        dom.window.setTimeout(() => if !closed then connect(), wait): Unit

  def send(cmd: ClientCmd): Unit =
    socket.filter(_.readyState == dom.WebSocket.OPEN).foreach(_.send(SessionCmd.clientCmd.encoder.encodeJson(cmd, None).toString))

  def close(): Unit =
    closed = true
    socket.foreach(_.close())
