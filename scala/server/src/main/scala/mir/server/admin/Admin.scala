package mir.server.admin

import zio.json.*

/**
 * 管理指令（#1243）：从游戏进程外、只在服务器本机发（`mir176ctl` → AdminSocket 的 unix socket），不经网页和 WebSocket，
 * 所以注册玩家碰不到。交给游戏线程执行（GameServer.admin），回一个 AdminReply。
 * 以后加管理功能（踢人、查在线）在这里加分支。
 */
enum AdminCmd:
  /** 全服公告：发到所有连接的聊天框 */
  case Announce(text: String)
  /** 列出各 boss 刷怪点现在活着还是几点刷（#1277） */
  case Bosses()

object AdminCmd:
  given JsonCodec[AdminCmd] = DeriveJsonCodec.gen

  /** 公告最长多少字（聊天框一行约 30 字，折几行还看得过来） */
  val MaxAnnounce = 200

  /** 命令行给的公告内容：去掉首尾空白，空的、太长的不发。 */
  def announce(text: String): Either[String, AdminCmd] =
    val t = text.trim
    if t.isEmpty then Left("公告内容是空的")
    else if t.length > MaxAnnounce then Left(s"公告最长 $MaxAnnounce 字，现在 ${t.length} 字")
    else Right(Announce(t))

/** 管理指令的结果：给命令行打印的一句话。 */
enum AdminReply:
  case Done(text: String)
  case Failed(reason: String)

object AdminReply:
  given JsonCodec[AdminReply] = DeriveJsonCodec.gen
