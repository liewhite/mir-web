package mir.server

/** 账号编号（数据库 accounts.id）。 */
opaque type AccountId = Long
object AccountId:
  def apply(n: Long): AccountId             = n
  extension (id: AccountId) def value: Long = id

/** 一个账号。 */
final case class Account(id: AccountId, name: String)

/** 一个网络连接的编号（日志、踢人用）。 */
opaque type LinkId = Long
object LinkId:
  def apply(n: Long): LinkId             = n
  extension (id: LinkId) def value: Long = id
