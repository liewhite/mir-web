package mir.client.net

import mir.protocol.{ClientCmd, ServerMsg}

/** 连接上发生的事：连上了、断了（正在重连）、收到一条消息。 */
enum LinkEvent:
  case Up
  case Down(reason: String)
  case Received(msg: ServerMsg)

/**
 * 和服务端说话的通道：发 ClientCmd，收 ServerMsg。两种实现说同一套协议：
 * WebSocket 连真服务端（WsTransport），或在浏览器里直接跑世界当本地服务端（LocalServer，开发用）。
 */
trait Transport:
  def send(cmd: ClientCmd): Unit
  def close(): Unit
