package mir.server

import mir.core.Millis
import mir.data.Catalog
import mir.server.admin.{AdminCmd, AdminReply, AdminSocket}
import mir.server.auth.SessionTokens
import mir.server.game.{GameServer, Headcount, Maps}
import mir.server.http.{BoardApi, HttpServer}
import mir.server.store.*
import mir.server.tools.{Migration, Smoke}
import zio.{durationInt, Exit, Runtime, Scope, Unsafe, ZEnvironment, ZIO, ZLayer}
import zio.http.Server

import java.nio.file.{Files, Paths}

/** 一个打开的数据库和上面的账号、角色、物品编号。 */
final class Store(val db: Db):
  val accounts = Accounts(db)
  val chars    = Characters(db)
  val uids     = UidBlocks(db, chars)
  val board    = BoardStore(db)
  val frozen   = Snapshots(db)

object Store:
  def open(cfg: ServerConfig): ZIO[Scope, Throwable, Store] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Store(Db.open(cfg.dbFile))))(s => ZIO.succeed(s.db.close()))

/**
 * 起服务：游戏线程 + HTTP。返回实际监听的端口（PORT=0 时随机，测试用）。
 * 离开 Scope（停服）的顺序（#1290）：先停游戏（在线角色存档、存停服快照，再叫每个连接带「重启」关闭码断开），
 * 等连接关完（最多 1 秒），再停 HTTP，最后关库。先停 HTTP 的话连接先断、人先按掉线下线，快照里就没有他们带的宝宝和状态。
 */
object Boot:
  /** Netty 停机不等默认的 2 秒「安静期」：连接都已经关了，多等只会拉长重启时间（#1290） */
  private val netty = zio.http.netty.NettyConfig.defaultWithFastShutdown

  def start(cfg: ServerConfig): ZIO[Scope, Throwable, Int] =
    for
      _       <- ServeLock.acquire(cfg.dataDir) // 先于开库：已有服务端在跑就到此为止（#1292）
      tokens  <- ZIO.attempt(SessionTokens(cfg.sessionSecret))
      json    <- ZIO.attemptBlocking(Files.readString(cfg.gameJson))
      catalog <- ZIO.fromEither(Catalog.decode(json)).mapError(e => Exception(s"${cfg.gameJson} 解码失败：$e"))
      store   <- Store.open(cfg)
      clock    = () => Millis(java.lang.System.currentTimeMillis())
      social   = mir.social.Social()
      maps     = Maps(catalog, cfg.assetsDir, store.uids, clock, social)
      game     = GameServer(catalog, maps, store.chars, store.uids, Some(Backups(store.db, cfg.backupDir)), clock,
                   idleTimeoutMs = cfg.liveness.idleTimeoutMs, social = social,
                   headcount = Some(Headcount(() => store.accounts.count(), clock())), freezer = store.frozen)
      stopGame <- ZIO.acquireRelease(ZIO.succeed(GameServer.run(game)))(stop => ZIO.attemptBlocking(stop()).orDie)
      _       <- AdminSocket.serve(cfg.adminSocket, game.adminAndWait)
      boardApi = BoardApi(store.board, store.chars, cfg.boardAdmins)
      http     = HttpServer(store.accounts, tokens, game, boardApi, cfg.assetsDir, cfg.webDir, cfg.liveness.pingEveryMs)
      env     <- ((ZLayer.succeed(Server.Config.default.binding(cfg.host, cfg.port)) ++ ZLayer.succeed(netty)) >>> Server.customized).build
      server   = env.get[Server]
      // 环境里只有 Scope 时 zio 2.1.27 的 ZEnvironment.prune 会误报缺 Any（测试里环境不空所以不出错），给它空环境
      _       <- server.install(http.routes).provideEnvironment(ZEnvironment.empty)
      // 收尾是倒着执行的：这一条在 HTTP 停之前跑。停游戏的函数只停一次，上面那条到时什么也不做
      _       <- ZIO.addFinalizer(ZIO.attemptBlocking(stopGame()).orDie *> http.drained(1.second))
      port    <- server.port
      _       <- ZIO.logInfo(s"mir176 listening on ${cfg.host}:$port, assets=${cfg.assetsDir}, web=${cfg.webDir}, data=${cfg.dataDir}")
    yield port

/**
 * 入口：
 *   serve                              起服务（必须明说：误敲不带参数的命令不该在线上再起一个，#1292）
 *   account add <账号>                 开号，密码从标准输入读一行
 *   account passwd <账号>              改密码，同上
 *   account list                       列出账号和角色数
 *   migrate <账号> <save.json> [角色名]  把旧单机版存档导成这个账号下的一个角色
 *   announce [内容]                    给正在跑的服务发全服公告（#1243）；不给内容就读标准输入
 *   bosses                             问正在跑的服务各 boss 现在活着还是几点刷（#1277）
 *   check                              打开 DATA_DIR 的库（会升级表结构），检查全部角色存档都解得开（#1288，部署试启动对副本库用）
 *   smoke <网址>                       冒烟检查：注册一次性账号、建角色、进图（#1288，只对试启动的副本跑，见 tools.Smoke）
 */
object Main:
  def main(args: Array[String]): Unit =
    val code = run(args.toList, ServerConfig.fromEnv(), () => readPassword())
    if code != 0 then sys.exit(code)

  /** 执行一条命令，返回退出码。readPw：读一行密码（测试里换掉）；readAll：读完标准输入（公告内容）。 */
  def run(
      args: List[String],
      cfg: ServerConfig,
      readPw: () => Option[String],
      readAll: () => String = () => String(java.lang.System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
  ): Int = args match
    case List("serve")       => serve(cfg)
    case "account" :: rest   => withStore(cfg)(account(_, rest, readPw))
    case List("migrate", acc, file)       => withStore(cfg)(migrate(_, acc, file, None))
    case List("migrate", acc, file, name) => withStore(cfg)(migrate(_, acc, file, Some(name)))
    case "announce" :: words =>
      val text = if words.isEmpty then readAll() else words.mkString(" ")
      report(AdminCmd.announce(text).flatMap(admin(cfg, _)))
    case List("bosses") => report(admin(cfg, AdminCmd.Bosses()))
    case List("check")  => withStore(cfg)(_.chars.checkAll().map(n => s"$n 个角色存档都解得开").left.map(bad => s"${bad.size} 个角色存档解不开：\n${bad.mkString("\n")}"))
    case List("smoke", url) => report(Smoke.run(url))
    case _ =>
      java.lang.System.err.println("用法：serve | account add|passwd <账号> | account list | migrate <账号> <save.json> [角色名] | announce [内容] | bosses | check | smoke <网址>")
      2

  /** 发给正在跑的服务一条管理指令。 */
  private def admin(cfg: ServerConfig, cmd: AdminCmd): Either[String, String] =
    AdminSocket.request(cfg.adminSocket, cmd).flatMap {
      case AdminReply.Done(out)   => Right(out)
      case AdminReply.Failed(why) => Left(why)
    }

  /** 一直跑到进程收到 SIGTERM（systemctl stop）：钩子里中断服务，走完 Scope 的收尾（在线角色存档、关库）再退出。 */
  private def serve(cfg: ServerConfig): Int =
    Unsafe.unsafe { implicit u =>
      val fiber = Runtime.default.unsafe.fork(ZIO.scoped(Boot.start(cfg) *> ZIO.never))
      java.lang.Runtime.getRuntime.addShutdownHook(Thread(() => Runtime.default.unsafe.run(fiber.interrupt): Unit))
      Runtime.default.unsafe.run(fiber.join) match
        case Exit.Failure(c) if !c.isInterruptedOnly =>
          java.lang.System.err.println(c.failureOption.collect { case h: ServeLock.Held => h.getMessage }.getOrElse(c.prettyPrint))
          1
        case _ => 0
    }

  private def withStore(cfg: ServerConfig)(f: Store => Either[String, String]): Int =
    val store = Store(Db.open(cfg.dbFile))
    try report(f(store))
    finally store.db.close()

  /** 成功打印到标准输出、退出码 0；失败打印到标准错误、退出码 1。 */
  private def report(result: Either[String, String]): Int = result match
    case Right(out) =>
      println(out)
      0
    case Left(err) =>
      java.lang.System.err.println(err)
      1

  /** 终端里不回显；管道里读标准输入一行。 */
  private def readPassword(): Option[String] =
    Option(java.lang.System.console()).map(c => String(c.readPassword("密码："))).orElse(Option(scala.io.StdIn.readLine()))

  private def account(store: Store, args: List[String], readPw: () => Option[String]): Either[String, String] =
    def pw = readPw().map(_.trim).filter(_.nonEmpty).toRight("没有读到密码（从标准输入给一行）")
    args match
      case List("add", name)    => pw.flatMap(store.accounts.add(name, _).left.map(_.message)).map(a => s"已开号 ${a.name}")
      case List("passwd", name) => pw.flatMap(store.accounts.setPassword(name, _)).map(a => s"已改密码 ${a.name}")
      case List("list") => Right(store.accounts.list().map((a, n) => s"${a.name}\t$n 个角色").mkString("\n"))
      case _            => Left("用法：account add|passwd <账号> | account list")

  private def migrate(store: Store, accountName: String, file: String, name: Option[String]): Either[String, String] =
    for
      acc  <- store.accounts.find(accountName).toRight(s"没有账号 $accountName")
      json <- scala.util.Try(Files.readString(Paths.get(file))).toEither.left.map(e => s"读不了 $file：${e.getMessage}")
      data <- Migration.fromTsSave(json, store.uids, name)
      sc   <- store.chars.create(acc.id, data)
    yield s"已导入角色「${sc.data.name}」（${sc.data.job.label} ${sc.data.level} 级）到账号 ${acc.name}"
