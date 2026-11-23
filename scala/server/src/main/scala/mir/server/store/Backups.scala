package mir.server.store

import java.nio.file.{Files, Path}
import java.time.{Instant, ZoneId}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** 数据库每天一份备份：DATA_DIR/backup/mir-<日期>.db，留最近 30 份。 */
final class Backups(db: Db, dir: Path, keep: Int = 30, zone: ZoneId = ZoneId.of("Asia/Shanghai")):
  private var lastDay = ""

  /** 今天还没备份过就备份一份（游戏线程每次定时存档时调用，便宜）。 */
  def daily(now: Long): Unit =
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate.toString
    if day != lastDay then
      Files.createDirectories(dir)
      val file = dir.resolve(s"mir-$day.db")
      if !Files.exists(file) then db.backupTo(file)
      lastDay = day
      prune()

  private def prune(): Unit =
    val all = Using.resource(Files.list(dir))(
      _.iterator().asScala.filter(_.getFileName.toString.matches("mir-\\d{4}-\\d{2}-\\d{2}\\.db")).toVector.sortBy(_.getFileName.toString)
    )
    all.dropRight(keep).foreach(Files.delete)
