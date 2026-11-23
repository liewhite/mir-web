package mir.server.store

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import scala.util.Using

/**
 * SQLite 数据库：账号、角色、全服物品编号。一个文件 DATA_DIR/mir.db。
 *
 * - 一条连接，所有访问串行（synchronized）：用的人少，游戏线程和 HTTP 线程偶尔才查一次，没必要上连接池。
 * - WAL 模式：服务端在跑时也能用命令行开号（另一个进程），互不锁死。
 * - 表结构按版本号升级（user_version），只加不删。
 */
final class Db private (conn: Connection):

  /** 在锁内用这条连接做事。 */
  def run[A](f: Connection => A): A = synchronized(f(conn))

  /** 在锁内开一个事务：f 正常返回才提交，抛错回滚。 */
  def transaction[A](f: Connection => A): A = synchronized {
    conn.setAutoCommit(false)
    var done = false
    try
      val a = f(conn)
      conn.commit()
      done = true
      a
    finally
      if !done then conn.rollback()
      conn.setAutoCommit(true)
  }

  /** 把整个库拷到 to（一致的快照，服务端在写也没关系）。 */
  def backupTo(to: Path): Unit = run { c =>
    Files.deleteIfExists(to)
    Using.resource(c.prepareStatement("VACUUM INTO ?")) { st =>
      st.setString(1, to.toString)
      st.execute(): Unit
    }
  }

  def close(): Unit = synchronized(conn.close())

object Db:
  /** 打开（没有就建）并升级表结构。 */
  def open(file: Path): Db =
    Option(file.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
    val conn = DriverManager.getConnection(s"jdbc:sqlite:${file.toAbsolutePath}")
    Using.resource(conn.createStatement()) { st =>
      st.execute("PRAGMA journal_mode=WAL")
      st.execute("PRAGMA synchronous=NORMAL")
      st.execute("PRAGMA foreign_keys=ON")
      st.execute("PRAGMA busy_timeout=5000")
    }
    val db = Db(conn)
    db.transaction(migrate)
    db

  /** 每一版的建表语句；新版本只在末尾追加。 */
  private val versions: Vector[List[String]] = Vector(
    List(
      """CREATE TABLE accounts(
        |  id INTEGER PRIMARY KEY,
        |  name TEXT NOT NULL UNIQUE COLLATE NOCASE,
        |  password TEXT NOT NULL,
        |  created_at INTEGER NOT NULL
        |)""".stripMargin,
      // 存档 data 是 CharacterData 的 JSON（zio-json），名字、等级等都从它取，不另存一份
      """CREATE TABLE characters(
        |  id INTEGER PRIMARY KEY,
        |  account_id INTEGER NOT NULL REFERENCES accounts(id),
        |  name TEXT NOT NULL,
        |  data TEXT NOT NULL,
        |  created_at INTEGER NOT NULL,
        |  updated_at INTEGER NOT NULL,
        |  deleted_at INTEGER
        |)""".stripMargin,
      // 角色名全服唯一（删掉的角色不占名字；删除只做标记，误删能从库里找回）
      "CREATE UNIQUE INDEX characters_name ON characters(name) WHERE deleted_at IS NULL",
      "CREATE INDEX characters_account ON characters(account_id)",
      // 全服物品编号：下一个可发的编号（服务端和命令行按块领取）
      "CREATE TABLE counters(name TEXT PRIMARY KEY, next INTEGER NOT NULL)"
    )
  )

  private def migrate(c: Connection): Unit =
    val current = Using.resource(c.createStatement())(st => Using.resource(st.executeQuery("PRAGMA user_version"))(rs => rs.getInt(1)))
    versions.zipWithIndex.drop(current).foreach { (stmts, i) =>
      Using.resource(c.createStatement()) { st =>
        stmts.foreach(st.execute)
        st.execute(s"PRAGMA user_version=${i + 1}")
      }
    }

  // ---- 小工具：参数化查询 ----

  def update(c: Connection, sql: String, args: Any*): Int =
    Using.resource(prepare(c, sql, args)) (_.executeUpdate())

  def query[A](c: Connection, sql: String, args: Any*)(row: ResultSet => A): List[A] =
    Using.resource(prepare(c, sql, args)) { st =>
      Using.resource(st.executeQuery()) { rs =>
        val b = List.newBuilder[A]
        while rs.next() do b += row(rs)
        b.result()
      }
    }

  def insert(c: Connection, sql: String, args: Any*): Long =
    Using.resource(prepare(c, sql, args)) { st =>
      st.executeUpdate(): Unit
      Using.resource(c.createStatement())(s => Using.resource(s.executeQuery("SELECT last_insert_rowid()"))(_.getLong(1)))
    }

  private def prepare(c: Connection, sql: String, args: Seq[Any]): PreparedStatement =
    val st = c.prepareStatement(sql)
    args.zipWithIndex.foreach {
      case (v: String, i)  => st.setString(i + 1, v)
      case (v: Long, i)    => st.setLong(i + 1, v)
      case (v: Int, i)     => st.setInt(i + 1, v)
      case (null, i)       => st.setNull(i + 1, java.sql.Types.NULL)
      case (v, i)          => throw IllegalArgumentException(s"不支持的参数类型 ${v.getClass} (#${i + 1})")
    }
    st
