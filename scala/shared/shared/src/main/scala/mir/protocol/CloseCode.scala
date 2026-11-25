package mir.protocol

/**
 * 服务端关 WebSocket 时带的关闭码：客户端据此决定提示什么、多快重连。
 * 用关闭码而不是加一种 ServerMsg：旧页面不认识也不出错（当普通断线），也不改通讯格式。
 */
object CloseCode:
  /** 服务器正常停服、马上重启（部署更新，#1290）：RFC 6455 的 1012 Service Restart。客户端提示「更新中」并每 0.5 秒重连 */
  val Restarting = 1012
