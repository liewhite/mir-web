package mir.server

import java.nio.file.{Path, Paths}

/**
 * 服务端配置，都从环境变量来（部署时在 /etc/mir176.env）：
 *   PORT=18176  HOST=127.0.0.1
 *   ASSETS_DIR  素材目录（public/assets：data/game.json、map/、img/ ……），登录后才能取
 *   WEB_DIR     客户端页面（scala/client/web：index.html、main.js ……），不用登录
 *   DATA_DIR    数据库和备份（mir.db、backup/）
 *   MIR_SESSION_SECRET  会话 cookie 的签名密钥（至少 16 字符）
 */
final case class ServerConfig(
    host: String,
    port: Int,
    assetsDir: Path,
    webDir: Path,
    dataDir: Path,
    sessionSecret: String,
    liveness: Liveness = Liveness()
):
  def dbFile: Path    = dataDir.resolve("mir.db")
  def backupDir: Path = dataDir.resolve("backup")
  /** 管理入口（#1243，mir.server.admin.AdminSocket）：只有服务账号连得上的 unix socket */
  def adminSocket: Path = dataDir.resolve("admin.sock")
  def gameJson: Path  = assetsDir.resolve("data").resolve("game.json")

/**
 * 判掉线（#1218、#1238）：服务端每 `pingEveryMs` 向每个连接发一个 WebSocket 协议层 ping 帧，浏览器在网络层自动回 pong，
 * 不受页面定时器节流影响（后台标签页的 setInterval 会被压到每分钟一次）；一个连接超过 `idleTimeoutMs` 既没发来消息也没回 pong
 * 就当它断了。不从环境变量读，测试里调小。
 */
final case class Liveness(pingEveryMs: Long = 10_000L, idleTimeoutMs: Long = 30_000L)

object ServerConfig:
  /** 没设的用默认值（相对 sbt 的工作目录 scala/）；命令行开号、迁移只用到 DATA_DIR。 */
  def fromEnv(env: Map[String, String] = sys.env): ServerConfig =
    def path(k: String, default: String) = Paths.get(env.getOrElse(k, default)).toAbsolutePath.normalize
    ServerConfig(
      host = env.getOrElse("HOST", "127.0.0.1"),
      port = env.get("PORT").flatMap(_.toIntOption).getOrElse(18176),
      assetsDir = path("ASSETS_DIR", "../public/assets"),
      webDir = path("WEB_DIR", "client/web"),
      dataDir = path("DATA_DIR", "server/data"),
      sessionSecret = env.getOrElse("MIR_SESSION_SECRET", "")
    )
