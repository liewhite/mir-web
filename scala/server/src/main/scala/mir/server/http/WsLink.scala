package mir.server.http

import mir.protocol.ServerMsg
import mir.server.{Account, LinkId}
import mir.server.game.Link
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
  def close(): Unit              = queue.add(Out.Close): Unit

  /** 发送循环：遇到 Close 或连接断了就结束。 */
  def pump(channel: WebSocketChannel): Task[Unit] =
    ZIO.attemptBlockingInterrupt(queue.take()).flatMap {
      case Out.Msg(m) => channel.send(ChannelEvent.Read(WebSocketFrame.text(m.toJson))).as(true)
      case Out.Close  => channel.shutdown.as(false)
    }.repeatWhile(identity).unit

object WsLink:
  private enum Out:
    case Msg(msg: ServerMsg)
    case Close
