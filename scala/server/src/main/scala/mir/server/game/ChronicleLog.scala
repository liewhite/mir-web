package mir.server.game

import mir.core.*
import mir.data.Catalog
import mir.world.{Chronicle, DeathVerdict, Slayer}

/**
 * 世界报来的事（Chronicle）写成服务端日志的一行（journalctl 里查）：
 * boss 刷出、死亡（#1277，deploy/jvm/bosses.sh log 查），玩家死亡（#1338，deploy/jvm/deaths.sh 查）。
 * 改这里的行首字样要同步改那两个脚本的 grep。
 */
object ChronicleLog:
  import BossReport.{cell, mapName, time}

  /** map：事发的图；account：角色名 → 账号（在线的才查得到） */
  def line(catalog: Catalog, map: MapId, e: Chronicle, account: String => Option[String]): String = e match
    case Chronicle.BossSpawned(m, at) => s"boss 刷出：${m.value} ${mapName(catalog, map)} ${cell(at)}"
    case Chronicle.BossSlain(m, at, owner, killer, respawn) =>
      val next = respawn.fold("不是刷怪点刷的，不会补")(t => s"下次刷新 ${time(t)}")
      s"boss 死亡：${m.value} ${mapName(catalog, map)} ${cell(at)}，归属 ${owner.getOrElse("无")}，最后一下 ${killer.getOrElse("无")}，$next"
    case Chronicle.PlayerSlain(victim, level, at, by, verdict, pk, lost) =>
      val who  = account(victim).fold(victim)(a => s"$victim（账号 $a）")
      val drop = if lost.isEmpty then "" else s"，红名掉了：${lost.map(_.value).mkString("、")}"
      s"玩家死亡：$who $level 级 ${mapName(catalog, map)} ${cell(at)}，被${slayer(by)}杀死，${judged(verdict)}，死时 PK 值 $pk$drop"

  private def slayer(s: Slayer): String = s match
    case Slayer.Mob(m)       => s"怪物「${m.value}」"
    case Slayer.Human(n)     => s"玩家「$n」"
    case Slayer.Pet(m, host) => s"${host.getOrElse("（主人不在图上）")}的宝宝「${m.value}」"
    case Slayer.Unknown      => "不明来源（如下毒的人已离开）"

  private def judged(v: DeathVerdict): String = v match
    case DeathVerdict.Arena       => "竞技地图不算 PK"
    case DeathVerdict.Murder      => "算谋杀（凶手 PK 值涨）"
    case DeathVerdict.SelfDefense => "死者先动手，凶手正当防卫"
    case DeathVerdict.Ordinary    => "不算 PK"
