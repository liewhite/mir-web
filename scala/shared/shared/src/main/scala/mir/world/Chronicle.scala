package mir.world

import mir.core.*

/**
 * 世界里值得写进服务端日志的事（#1277）：随 Outbox 交给服务端打日志，不发给玩家。
 * 记 boss（Rates.bosses）的刷出和死亡，用来回答「某个 boss 被谁杀了、几点刷」；
 * 和玩家的死亡（#1338），用来回答「最近谁在哪死了、被谁杀的」。
 */
enum Chronicle:
  /** 刷怪点刷出一只 boss */
  case BossSpawned(monster: MonsterName, at: Cell)
  /**
   * boss 死了。owner：拿经验和掉落的归属者（Claim）；killer：最后一下是谁打的（宝宝算主人）；
   * respawnAt：它的刷怪点下次补怪的时刻（到时图上没人就等有人进图再补；不是刷怪点刷的为空）。
   */
  case BossSlain(monster: MonsterName, at: Cell, owner: Option[String], killer: Option[String], respawnAt: Option[Millis])
  /**
   * 玩家死了（#1338）。level：死时等级；by：最后一下是谁打的；verdict：PK 怎么算；
   * pkPoints：死者死时的 PK 值；lost：红名死亡掉在地上的东西（不是红名、竞技地图为空）。
   */
  case PlayerSlain(victim: String, level: Int, at: Cell, by: Slayer, verdict: DeathVerdict, pkPoints: Int, lost: Vector[ItemName])

/** 打死玩家的最后一下是谁 */
enum Slayer:
  /** 野怪、守卫 */
  case Mob(monster: MonsterName)
  /** 别的玩家（或自己） */
  case Human(name: String)
  /** 玩家的宝宝；master：主人名，主人不在这张图上为空 */
  case Pet(monster: MonsterName, master: Option[String])
  /** 来源已不在图上（如下毒的走了）或没有来源 */
  case Unknown

/** 玩家死了 PK 怎么算（Justice.died） */
enum DeathVerdict:
  /** 竞技地图：杀人不算、红名也不掉 */
  case Arena
  /** 被别的玩家（或其宝宝）谋杀：凶手 PK 值涨 */
  case Murder
  /** 死者是先动手的灰名，凶手正当防卫 */
  case SelfDefense
  /** 不是别的玩家杀的，或死者是红名（谁杀都不算） */
  case Ordinary

/**
 * 一个 boss 刷怪点此刻的样子（管理命令查，#1277）。
 * nextRefill：下次补怪的时刻；早于现在说明到点时图上没人，有人进图就补。数据表里没有这种怪（刷不出来）为空。
 */
final case class BossSpot(monster: MonsterName, at: Cell, count: Int, alive: Vector[BossAlive], nextRefill: Option[Millis])

/** 刷怪点刷出、还活着的一只 boss */
final case class BossAlive(at: Cell, hp: Int, maxHp: Int)
