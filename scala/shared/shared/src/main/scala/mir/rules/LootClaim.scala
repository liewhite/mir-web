package mir.rules

import mir.core.{ActorId, Millis}

/**
 * 怪的归属（#1158，照原版 TBaseObject.m_ExpHitter：ObjBase.pas SetLastHiter、第 2445 行的过期判断、Die 里的掉落）：
 * 第一个打出血的玩家（宝宝打的算主人）拿下归属，他再打一下就续上；停手超过 HoldMs、死了或不在这张图上，归属作废，
 * 下一个打它的人接手。别人打得再多、补最后一刀都抢不走。怪死时经验和掉落都归归属者。
 */
final case class Claim(owner: ActorId, lastHit: Millis):
  /** 只按时间看还算不算数（归属者死没死、在不在图上由调用方另判） */
  def freshAt(now: Millis): Boolean = now - lastHit <= Claim.HoldMs

object Claim:
  /** 归属者停手多久就作废（原版 6 秒） */
  val HoldMs: Long = 6000

  /** 被 by 打出血之后的归属：cur 是现在还算数的归属（作废的传 None）。 */
  def struck(cur: Option[Claim], by: ActorId, now: Millis): Claim = cur match
    case Some(c) if c.owner != by => c
    case _                        => Claim(by, now)

/**
 * 掉落保护（#1158，照原版 MapItem.OfBaseObject + dwCanPickUpTick，ObjPlay.pas 第 667 行）：
 * 怪掉的东西 until 之前只有归属者和他的队友捡得起，之后谁都能捡。玩家自己扔的、死了掉的不保护。
 */
final case class Ward(owner: String, until: Millis):
  /** 角色 name（队友名单 mates）现在捡不捡得起 */
  def admits(name: String, mates: Seq[String], now: Millis): Boolean =
    now >= until || name == owner || mates.contains(owner)
