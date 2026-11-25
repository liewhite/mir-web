package mir.server.store

import zio.{Scope, ZIO}

import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardOpenOption}

/**
 * 一个数据目录同时只许一个服务端（#1292）：起服务先在数据目录下的 serve.lock 上拿操作系统的独占文件锁，拿不到说明已有服务端
 * 在用这个库，直接拒绝启动——不开库、不起游戏线程、不碰管理入口，免得两个进程各自存档、互相盖掉停服快照。
 * 锁跟着进程：正常停服离开 Scope 时释放，进程被杀时操作系统自己放掉，所以不会留下「死锁文件」挡住下次启动；
 * 锁文件本身留着不删（删了再建会让两个进程各锁各的文件）。
 * 命令行的开号、迁移不拿这把锁：它们和服务同时用库是允许的（SQLite 自己管并发）。
 */
object ServeLock:
  val FileName = "serve.lock"

  /** 已有服务端拿着锁时的错误 */
  final case class Held(dataDir: Path)
      extends Exception(s"数据目录 $dataDir 已有服务端在跑（$FileName 被锁住），不再起第二个；要重启用 systemctl restart mir176")

  def acquire(dataDir: Path): ZIO[Scope, Throwable, Unit] =
    ZIO.acquireRelease(ZIO.attemptBlocking(lock(dataDir)))(l => ZIO.attemptBlocking { l.release(); l.channel.close() }.ignoreLogged).unit

  private def lock(dataDir: Path): FileLock =
    Files.createDirectories(dataDir)
    val ch = FileChannel.open(dataDir.resolve(FileName), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val got =
      try Option(ch.tryLock())
      catch case _: OverlappingFileLockException => None // 同一个 JVM 里已经拿着（测试里起两个服务）
    got.getOrElse {
      ch.close()
      throw Held(dataDir)
    }
