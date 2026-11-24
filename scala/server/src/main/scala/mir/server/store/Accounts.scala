package mir.server.store

import mir.server.{Account, AccountId}
import mir.server.auth.Passwords

/**
 * 账号：网页自助注册（`POST api/register`，#1197）或命令行 `account add`，改密码、列账号用命令行 `account passwd / list`。
 * 账号名 2-16 位字母、数字、下划线，不分大小写。
 */
final class Accounts(db: Db):
  import Accounts.*

  /** 开号（注册和命令行共用）：查重和插入在同一把锁里，两个人同时注册同名只有一个成功；scrypt 在锁外先算。 */
  def add(name: String, password: String): Either[Rejected, Account] =
    for
      n    <- validName(name)
      _    <- validPassword(password)
      hash  = Passwords.hash(password)
      acc  <- db.run { c =>
                val taken = Db.query(c, "SELECT id FROM accounts WHERE name = ?", n)(_.getLong(1)).nonEmpty
                Either.cond(!taken, (), Rejected.Taken(n)).map { _ =>
                  Account(AccountId(Db.insert(c, "INSERT INTO accounts(name, password, created_at) VALUES (?, ?, ?)", n, hash, System.currentTimeMillis())), n)
                }
              }
    yield acc

  def setPassword(name: String, password: String): Either[String, Account] =
    for
      _   <- validPassword(password).left.map(_.message)
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
  val MinPassword = 6
  /** 上限防有人塞超长密码拖慢 scrypt */
  val MaxPassword = 64

  /** 开号被拒的原因：格式不对（BadName/BadPassword）、重名（Taken）。 */
  enum Rejected(val message: String):
    case BadName     extends Rejected("账号名要 2-16 位字母、数字或下划线")
    case BadPassword extends Rejected(s"密码要 $MinPassword-$MaxPassword 位")
    case Taken(name: String) extends Rejected(s"账号 $name 已被注册")

  def validName(name: String): Either[Rejected, String] =
    Either.cond(NamePattern.matches(name), name, Rejected.BadName)

  def validPassword(pw: String): Either[Rejected, Unit] =
    Either.cond(pw.length >= MinPassword && pw.length <= MaxPassword, (), Rejected.BadPassword)
