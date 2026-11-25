package mir.server.game

import mir.core.*
import mir.data.Catalog
import mir.rules.Rates
import mir.world.{BossSpot, Chronicle}

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

/**
 * boss 的服务端日志和管理命令「各 boss 现在怎样」（#1277）的文字。时刻按北京时间写（玩家和用户都在国内，服务器时区不一定是）。
 */
object BossReport:
  private val Zone = ZoneId.of("Asia/Shanghai")
  private val Fmt  = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(Zone)

  def time(t: Millis): String = Fmt.format(Instant.ofEpochMilli(t.value))

  private def mapName(catalog: Catalog, id: MapId): String =
    catalog.map(id).fold(id.value)(d => s"${d.title}(${id.value})")

  private def cell(c: Cell): String = s"(${c.x},${c.y})"

  /** 世界报来的一件事写成一行日志。 */
  def line(catalog: Catalog, map: MapId, e: Chronicle): String = e match
    case Chronicle.BossSpawned(m, at) => s"boss 刷出：${m.value} ${mapName(catalog, map)} ${cell(at)}"
    case Chronicle.BossSlain(m, at, owner, killer, respawn) =>
      val next = respawn.fold("不是刷怪点刷的，不会补")(t => s"下次刷新 ${time(t)}")
      s"boss 死亡：${m.value} ${mapName(catalog, map)} ${cell(at)}，归属 ${owner.getOrElse("无")}，最后一下 ${killer.getOrElse("无")}，$next"

  /**
   * 全部 boss 刷怪点的现状，一个刷怪点一行，按怪名排。spots：开过的图里各 boss 刷怪点（没开过的图为空，即没人进过）。
   */
  def status(catalog: Catalog, spots: MapId => Option[Vector[BossSpot]], now: Millis): String =
    val bossMaps = catalog.data.spawns.filter(s => Rates.bosses(s.monster)).map(_.map).distinct
    val rows = bossMaps.flatMap { map =>
      spots(map) match
        case Some(list) => list.map(s => (s.monster.value, mapName(catalog, map), s"${cell(s.at)}：${spotState(s, now)}"))
        case None =>
          catalog.data.spawns.filter(s => s.map == map && Rates.bosses(s.monster))
            .map(s => (s.monster.value, mapName(catalog, map), s"${cell(s.at)}：没人进过这张图，有人进图就刷"))
    }
    val lines = rows.sortBy(r => (r._1, r._2)).map((m, map, rest) => s"$m $map $rest")
    (s"boss 现状（${time(now)}，北京时间）：" +: lines).mkString("\n")

  private def spotState(s: BossSpot, now: Millis): String =
    val alive = s.alive.map(a => s"${cell(a.at)} 血 ${a.hp}/${a.maxHp}").mkString("，")
    val refill = s.nextRefill match
      case None                => "数据表里没有这种怪，刷不出来"
      case Some(t) if t <= now => "到点时图上没人，有人进图就刷"
      case Some(t)             => s"${time(t)} 刷（还有 ${(t - now + 59_999) / 60_000} 分钟）"
    if s.alive.size >= s.count then s"活着 $alive"
    else if s.alive.isEmpty then s"死了，$refill"
    else s"活着 ${s.alive.size}/${s.count}：$alive；缺的 $refill"
