package mir.world

import mir.core.*

/**
 * 世界里值得写进服务端日志的事（#1277）：随 Outbox 交给服务端打日志，不发给玩家。
 * 现在只记 boss（Rates.bosses）的刷出和死亡，用来回答「某个 boss 被谁杀了、几点刷」。
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
 * 一个 boss 刷怪点此刻的样子（管理命令查，#1277）。
 * nextRefill：下次补怪的时刻；早于现在说明到点时图上没人，有人进图就补。数据表里没有这种怪（刷不出来）为空。
 */
final case class BossSpot(monster: MonsterName, at: Cell, count: Int, alive: Vector[BossAlive], nextRefill: Option[Millis])

/** 刷怪点刷出、还活着的一只 boss */
final case class BossAlive(at: Cell, hp: Int, maxHp: Int)
