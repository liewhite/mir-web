package mir.client.game

import mir.protocol.Release

/**
 * 页面和服务端是不是同一版（#1242、#1289）：部署重启后，没刷新的页面会自动重连，但跑的还是旧脚本。
 * 服务端登录时发来它的 Release（构建提交、通讯格式、游戏数据摘要），和页面自己的比。
 */
enum Freshness:
  /** 同一版，照常 */
  case Current
  /** 页面旧了，但通讯格式和游戏数据都一样：先接着玩，等下一次本来就会切画面的时候（换图、回城、复活）再刷新 */
  case Stale
  /** 通讯格式或游戏数据变了，旧页面会看错消息、点错东西：马上刷新（刷新前记下「为这一版刷新过」） */
  case Reload
  /** 已经为这一版刷新过、还是对不上（浏览器缓存、刷新中途又部署了等）：不再刷，免得一直刷；提示玩家自己刷新 */
  case Stuck

/** 页面旧了（Stale）之后等机会刷新（#1289）。 */
enum PendingUpdate:
  /** 不用刷新 */
  case Idle
  /** 刚重连、发了进游戏：接下来的进图是回到原处，不算切画面。commit 是服务端的提交（刷新时记下） */
  case AwaitRejoin(commit: String)
  /** 已经回到游戏：下一次进图（换图、回城、复活、同图传送）就刷新 */
  case AtNextCut(commit: String)

object Freshness:
  /**
   * mine：页面自己的；server：服务端发来的；reloadedFor：本标签页上次为服务端哪个提交刷新过（sessionStorage 里记的）。
   */
  def judge(mine: Release, server: Release, reloadedFor: Option[String]): Freshness =
    if server.commit == mine.commit then Current
    else if reloadedFor.contains(server.commit) then Stuck
    else if mine.compatible(server) then Stale
    else Reload
