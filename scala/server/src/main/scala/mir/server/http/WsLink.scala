package mir.server.http

import mir.protocol.{CloseCode, ServerMsg}
import mir.server.{Account, LinkId}
import mir.server.game.{Farewell, Link}
import zio.*
import zio.http.*
import zio.json.*

import java.util.concurrent.LinkedBlockingQueue

/**
 * 一条 WebSocket 连接的发送端：游戏线程把消息放进队列（不阻塞），一个专门的 fiber 取出来编成 JSON 一帧一条发出去。
 */
final class WsLink(val id: LinkId, val account: Account) extends Link:
  import WsLink.Out

  private val queue = LinkedBlockingQueue[Out]()

  def send(msg: ServerMsg): Unit = queue.add(Out.Msg(msg)): Unit
  def close(why: Farewell): Unit = queue.add(Out.Close(why)): Unit
  /** 发一个协议层 ping 帧（判掉线用，见 Liveness）；和消息走同一个队列，一个 fiber 写 */
  def ping(): Unit               = queue.add(Out.Ping): Unit

  /** 发送循环：遇到 Close 或连接断了就结束。 */
  def pump(channel: WebSocketChannel): Task[Unit] =
    ZIO.attemptBlockingInterrupt(queue.take()).flatMap {
      case Out.Msg(m) => channel.send(ChannelEvent.Read(WebSocketFrame.text(m.toJson))).as(true)
      case Out.Ping   => channel.send(ChannelEvent.Read(WebSocketFrame.Ping)).as(true)
      case Out.Close(Farewell.Dropped) => channel.shutdown.as(false)
      case Out.Close(Farewell.Restarting) =>
        // 先发关闭帧带上「重启」关闭码，浏览器的 onclose 才拿得到它（直接断 TCP 拿到的是 1006）
        channel.send(ChannelEvent.Read(WebSocketFrame.Close(CloseCode.Restarting, Some("server restart")))).ignore *>
          channel.shutdown.as(false)
    }.repeatWhile(identity).unit

object WsLink:
  private enum Out:
    case Msg(msg: ServerMsg)
    case Ping
    case Close(why: Farewell)
