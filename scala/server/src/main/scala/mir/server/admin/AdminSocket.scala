package mir.server.admin

import zio.{Scope, ZIO}
import zio.json.*

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{Channels, ClosedChannelException, ServerSocketChannel, SocketChannel}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.util.Try
import scala.util.control.NonFatal

/**
 * 管理入口（#1243）：游戏进程在数据目录下开一个 unix socket（/var/lib/mir176/admin.sock，权限 600，只有服务账号 mir176
 * 和 root 连得上），不监听任何网络端口，所以不经 Ingress、外网和注册玩家都碰不到。
 * 一次连接一问一答：客户端发一行 JSON（AdminCmd），服务端回一行 JSON（AdminReply）后断开。
 * 服务器上用 `mir176ctl announce …`（Main 里的命令行，切到 mir176 账号跑）连它。
 */
object AdminSocket:
  /** 一行最多多少字节（公告 200 字的 UTF-8 加 JSON 外壳远不到） */
  val MaxLine = 16 * 1024

  /**
   * 在 path 上监听直到离开 Scope（关掉并删掉 socket 文件）。上次没删掉的旧文件先删。
   * exec 在连接各自的虚拟线程里调用，可以阻塞（等游戏线程回话）。
   * 开不了（目录不可写、路径太长）只打日志，游戏照常起：管理入口坏了不该让玩家进不了游戏。
   */
  def serve(path: Path, exec: AdminCmd => AdminReply): ZIO[Scope, Nothing, Unit] =
    ZIO.acquireRelease(ZIO.attemptBlocking(open(path, exec)).tapError(e => ZIO.logError(s"管理入口 $path 开不了：$e")).option) {
      case Some(close) => ZIO.succeed(close())
      case None        => ZIO.unit
    }.unit

  private def open(path: Path, exec: AdminCmd => AdminReply): () => Unit =
    Files.deleteIfExists(path)
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(path))
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    val acceptor = Thread.ofPlatform().daemon().name("mir-admin").start(() => acceptLoop(server, exec))
    () =>
      server.close()
      acceptor.join(1000)
      Files.deleteIfExists(path): Unit

  private def acceptLoop(server: ServerSocketChannel, exec: AdminCmd => AdminReply): Unit =
    try
      while true do
        val ch = server.accept()
        Thread.ofVirtual().name("mir-admin-conn").start(() => answer(ch, exec)): Unit
    catch
      case _: ClosedChannelException => () // 停服
      case NonFatal(e)               => System.err.println(s"管理入口停了：$e")

  private def answer(ch: SocketChannel, exec: AdminCmd => AdminReply): Unit =
    try
      val reply = readLine(Channels.newInputStream(ch)) match
        case Left(why)  => AdminReply.Failed(why)
        case Right(req) => req.fromJson[AdminCmd].fold(e => AdminReply.Failed(s"看不懂的指令：$e"), exec)
      writeLine(ch, reply.toJson)
    catch case NonFatal(e) => System.err.println(s"管理连接出错：$e")
    finally ch.close()

  /** 读到换行或对方关闭为止，超过 MaxLine 不要。 */
  private def readLine(in: InputStream): Either[String, String] =
    val buf = ByteArrayOutputStream()
    var b   = in.read()
    while b != -1 && b != '\n' && buf.size <= MaxLine do
      buf.write(b)
      b = in.read()
    if buf.size > MaxLine then Left(s"指令超过 $MaxLine 字节") else Right(buf.toString(UTF_8))

  private def writeLine(ch: SocketChannel, line: String): Unit =
    val out = Channels.newOutputStream(ch)
    out.write((line + "\n").getBytes(UTF_8))
    out.flush()

  /** 命令行这边：连上 path 发一条指令，等回话。服务没在跑、连不上时返回 Left。 */
  def request(path: Path, cmd: AdminCmd): Either[String, AdminReply] =
    Try {
      val ch = SocketChannel.open(UnixDomainSocketAddress.of(path))
      try
        writeLine(ch, cmd.toJson)
        readLine(Channels.newInputStream(ch)).flatMap(_.fromJson[AdminReply])
      finally ch.close()
    }.toEither.left.map(e => s"连不上管理入口 $path（游戏服务在跑吗？）：$e").flatten
