package mir.server.store

import mir.server.AccountId
import mir.server.board.*

/**
 * 留言板的帖子和回复（#1280，表 board_topics / board_replies）。删除只做标记。
 * 内容合不合规（字数、署名、谁能删）由 board 包的规则定，这里只存取。
 */
final class BoardStore(db: Db):

  /** 第 asked 页（夹到有效范围），按最后活跃倒序，每帖带全部没删的回复（按先后）。 */
  def page(asked: Int): TopicPage = db.run { c =>
    val total  = Db.query(c, "SELECT count(*) FROM board_topics WHERE deleted_at IS NULL")(_.getInt(1)).head
    val number = BoardRules.clampPage(asked, total)
    val heads = Db.query(c,
      """SELECT id, account_id, author, body, created_at, bumped_at FROM board_topics
        |WHERE deleted_at IS NULL ORDER BY bumped_at DESC, id DESC LIMIT ? OFFSET ?""".stripMargin,
      BoardRules.PageSize, (number - 1) * BoardRules.PageSize
    )(rs => Topic(TopicId(rs.getLong(1)), Author(AccountId(rs.getLong(2)), rs.getString(3)), rs.getString(4), rs.getLong(5), rs.getLong(6), Nil))
    val replies = heads.headOption.fold(Map.empty[TopicId, List[Reply]]) { _ =>
      val ids = heads.map(_.id.value)
      Db.query(c,
        s"""SELECT topic_id, id, account_id, author, body, created_at FROM board_replies
           |WHERE deleted_at IS NULL AND topic_id IN (${ids.map(_ => "?").mkString(",")}) ORDER BY id""".stripMargin,
        ids*
      )(rs => TopicId(rs.getLong(1)) -> Reply(ReplyId(rs.getLong(2)), Author(AccountId(rs.getLong(3)), rs.getString(4)), rs.getString(5), rs.getLong(6)))
        .groupMap(_._1)(_._2)
    }
    TopicPage(number, BoardRules.pages(total), total, heads.map(t => t.copy(replies = replies.getOrElse(t.id, Nil))))
  }

  def post(author: Author, body: String, now: Long): TopicId =
    TopicId(db.run(Db.insert(_,
      "INSERT INTO board_topics(account_id, author, body, created_at, bumped_at) VALUES (?, ?, ?, ?, ?)",
      author.account.value, author.name, body, now, now)))

  /** 回复：帖子在（没删）才回，并把帖子顶到最前。 */
  def reply(topic: TopicId, author: Author, body: String, now: Long): Either[Rejected, ReplyId] = db.transaction { c =>
    if Db.update(c, "UPDATE board_topics SET bumped_at = ? WHERE id = ? AND deleted_at IS NULL", now, topic.value) == 0 then Left(Rejected.NotFound)
    else
      Right(ReplyId(Db.insert(c,
        "INSERT INTO board_replies(topic_id, account_id, author, body, created_at) VALUES (?, ?, ?, ?, ?)",
        topic.value, author.account.value, author.name, body, now)))
  }

  /** 删（标记）：东西在、且 viewer 能删它的作者才删；返回被删那条的署名（记日志用）。 */
  def delete(target: Target, viewer: Viewer, now: Long): Either[Rejected, Author] = db.transaction { c =>
    val table = target match
      case Target.OfTopic(_) => "board_topics"
      case Target.OfReply(_) => "board_replies"
    val id = target match
      case Target.OfTopic(t) => t.value
      case Target.OfReply(r) => r.value
    val by = viewer match
      case Viewer.Member(acc, _, _) => acc.id.value
      case Viewer.Guest             => 0L
    Db.query(c, s"SELECT account_id, author FROM $table WHERE id = ? AND deleted_at IS NULL", id)(rs => Author(AccountId(rs.getLong(1)), rs.getString(2)))
      .headOption.toRight(Rejected.NotFound)
      .filterOrElse(a => viewer.canDelete(a.account), Rejected.Forbidden)
      .map { a =>
        Db.update(c, s"UPDATE $table SET deleted_at = ?, deleted_by = ? WHERE id = ?", now, by, id)
        a
      }
  }
