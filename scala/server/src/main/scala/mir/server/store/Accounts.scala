package mir.server.store

import mir.server.{Account, AccountId}
import mir.server.auth.Passwords

/**
 * 账号：不开放注册，由命令行 `account add / passwd / list` 管理。
 * 账号名 2-16 位字母、数字、下划线，不分大小写。
 */
final class Accounts(db: Db):
  import Accounts.*

  def add(name: String, password: String): Either[String, Account] =
    for
      n <- validName(name)
      _ <- validPassword(password)
      _ <- Either.cond(find(n).isEmpty, (), s"账号 $n 已存在")
    yield
      val hash = Passwords.hash(password)
      val id   = db.run(Db.insert(_, "INSERT INTO accounts(name, password, created_at) VALUES (?, ?, ?)", n, hash, System.currentTimeMillis()))
      Account(AccountId(id), n)

  def setPassword(name: String, password: String): Either[String, Account] =
    for
      _   <- validPassword(password)
      acc <- find(name).toRight(s"没有账号 $name")
    yield
      db.run(Db.update(_, "UPDATE accounts SET password = ? WHERE id = ?", Passwords.hash(password), acc.id.value)): Unit
      acc

  /** 账号和密码对得上才返回账号（scrypt 在锁外算，慢的验证不挡别的查询）。 */
  def authenticate(name: String, password: String): Option[Account] =
    val row = db.run(Db.query(_, "SELECT id, name, password FROM accounts WHERE name = ?", name)(rs =>
      (Account(AccountId(rs.getLong(1)), rs.getString(2)), rs.getString(3))
    )).headOption
    row.collect { case (acc, hash) if Passwords.verify(password, hash) => acc }

  def find(name: String): Option[Account] =
    db.run(Db.query(_, "SELECT id, name FROM accounts WHERE name = ?", name)(rs => Account(AccountId(rs.getLong(1)), rs.getString(2)))).headOption

  def get(id: AccountId): Option[Account] =
    db.run(Db.query(_, "SELECT id, name FROM accounts WHERE id = ?", id.value)(rs => Account(AccountId(rs.getLong(1)), rs.getString(2)))).headOption

  /** 全部账号和各自的角色数（不含已删的角色）。 */
  def list(): List[(Account, Int)] =
    db.run(Db.query(_,
      """SELECT a.id, a.name, (SELECT count(*) FROM characters c WHERE c.account_id = a.id AND c.deleted_at IS NULL)
        |FROM accounts a ORDER BY a.id""".stripMargin
    )(rs => (Account(AccountId(rs.getLong(1)), rs.getString(2)), rs.getInt(3))))

object Accounts:
  private val NamePattern = "[A-Za-z0-9_]{2,16}".r

  def validName(name: String): Either[String, String] =
    Either.cond(NamePattern.matches(name), name, "账号名要 2-16 位字母、数字或下划线")

  def validPassword(pw: String): Either[String, Unit] =
    Either.cond(pw.length >= 6, (), "密码至少 6 位")
