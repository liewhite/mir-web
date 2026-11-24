package mir.server.store

import mir.core.*
import mir.server.AccountId
import mir.world.{CharacterData, UidSource}
import zio.json.*

import java.nio.charset.Charset

/** 数据库里的一个角色。 */
final case class StoredChar(id: CharId, account: AccountId, data: CharacterData)

/**
 * 角色存档。每号最多 2 个角色（原版选人界面两个位），角色名全服唯一；删除只做标记。
 * 存档本体是 CharacterData 的 JSON。
 */
final class Characters(db: Db):
  import Characters.*

  /** 某账号的角色（按建立先后）。解不开的存档跳过并打日志，不让一个坏存档挡住整个号。 */
  def list(account: AccountId): List[StoredChar] =
    rows("SELECT id, account_id, data FROM characters WHERE account_id = ? AND deleted_at IS NULL ORDER BY id", account.value)

  def get(id: CharId): Option[StoredChar] =
    rows("SELECT id, account_id, data FROM characters WHERE id = ? AND deleted_at IS NULL", id.value).headOption

  /** 按名字（不分大小写）找没删的角色，给出它的真名字；加好友用（#1221）。 */
  def canonicalName(name: String): Option[String] =
    db.run(Db.query(_, "SELECT name FROM characters WHERE name = ? COLLATE NOCASE AND deleted_at IS NULL", name.trim)(_.getString(1))).headOption

  /** 建角色：名字合法、没人用、这个号还有空位。 */
  def create(account: AccountId, data: CharacterData): Either[String, StoredChar] =
    validName(data.name).flatMap { name =>
      db.transaction { c =>
        val count = Db.query(c, "SELECT count(*) FROM characters WHERE account_id = ? AND deleted_at IS NULL", account.value)(_.getInt(1)).head
        val taken = Db.query(c, "SELECT 1 FROM characters WHERE name = ? AND deleted_at IS NULL", name)(_ => ()).nonEmpty
        if count >= MaxPerAccount then Left(s"每个账号最多 $MaxPerAccount 个角色")
        else if taken then Left(s"角色名「$name」已被使用")
        else
          val now = System.currentTimeMillis()
          val id = Db.insert(c, "INSERT INTO characters(account_id, name, data, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            account.value, name, data.toJson, now, now)
          Right(StoredChar(CharId(id), account, data))
      }
    }

  /** 删除（标记）：只能删自己号上的。 */
  def delete(account: AccountId, id: CharId): Boolean =
    db.run(Db.update(_, "UPDATE characters SET deleted_at = ? WHERE id = ? AND account_id = ? AND deleted_at IS NULL",
      System.currentTimeMillis(), id.value, account.value)) == 1

  def save(id: CharId, data: CharacterData): Unit =
    db.run(Db.update(_, "UPDATE characters SET data = ?, updated_at = ? WHERE id = ?", data.toJson, System.currentTimeMillis(), id.value)): Unit

  /** 全部存档里（含已删的）最大的物品编号；没有物品为 0。 */
  def maxItemUid(): Long =
    db.run(Db.query(_, "SELECT data FROM characters")(_.getString(1)))
      .flatMap(_.fromJson[CharacterData].toOption)
      .flatMap(_.allItems.map(_.uid.value))
      .maxOption.getOrElse(0L)

  private def rows(sql: String, args: Any*): List[StoredChar] =
    db.run(Db.query(_, sql, args*)(rs => (rs.getLong(1), rs.getLong(2), rs.getString(3)))).flatMap { (id, acc, json) =>
      json.fromJson[CharacterData] match
        case Right(d) => Some(StoredChar(CharId(id), AccountId(acc), d))
        case Left(e) =>
          System.err.println(s"角色 $id 的存档解不开，跳过：$e")
          None
    }

object Characters:
  val MaxPerAccount = 2
  /** 原版角色名最长 14 字节（GBK：汉字 2 字节） */
  val MaxNameBytes = 14
  private val gbk = Charset.forName("GBK")

  /** 角色名：去掉首尾空白后非空、不含空白和符号、GBK 编码不超过 14 字节。 */
  def validName(raw: String): Either[String, String] =
    val name = raw.trim
    if name.isEmpty then Left("请输入角色名")
    else if !name.forall(ch => Character.isLetterOrDigit(ch) || ch == '_') then Left("角色名只能用汉字、字母、数字和下划线")
    else if !gbk.newEncoder().canEncode(name) then Left("角色名里有不支持的字")
    else if name.getBytes(gbk).length > MaxNameBytes then Left(s"角色名太长（最多 7 个汉字或 $MaxNameBytes 个字母）")
    else Right(name)

/**
 * 全服物品编号：从数据库按块领取（每块 1000 个），服务端和命令行（迁移旧存档）同时发编号也不会撞。
 * 第一次用时从全部存档里最大的编号往上数。
 */
final class UidBlocks(db: Db, chars: Characters, blockSize: Int = 1000) extends UidSource:
  private var cursor = 0L
  private var limit = 0L

  def next(): ItemUid = synchronized {
    if cursor >= limit then
      val start = db.transaction { c =>
        val cur = Db.query(c, "SELECT next FROM counters WHERE name = 'item_uid'")(_.getLong(1)).headOption.getOrElse {
          val first = chars.maxItemUid() + 1
          Db.update(c, "INSERT INTO counters(name, next) VALUES ('item_uid', ?)", first)
          first
        }
        Db.update(c, "UPDATE counters SET next = ? WHERE name = 'item_uid'", cur + blockSize)
        cur
      }
      cursor = start
      limit = start + blockSize
    val v = cursor
    cursor += 1
    ItemUid(v)
  }
