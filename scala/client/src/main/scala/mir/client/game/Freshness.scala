package mir.client.game

/**
 * 页面和服务端是不是同一版（#1242）：部署重启后，没刷新的页面会自动重连，但跑的还是旧脚本，协议改过就会出错。
 * 服务端登录时发来它的构建提交，和页面自己的比：不一样就刷新页面拿新脚本。
 */
enum Freshness:
  /** 同一版，照常 */
  case Current
  /** 页面是旧的：刷新（刷新前记下「为这一版刷新过」） */
  case Reload
  /** 已经为这一版刷新过、还是对不上（浏览器缓存、刷新中途又部署了等）：不再刷，免得一直刷；提示玩家自己刷新 */
  case Stuck

object Freshness:
  /**
   * mine：页面自己的构建；server：服务端发来的；reloadedFor：本标签页上次为哪一版刷新过（sessionStorage 里记的）。
   */
  def judge(mine: String, server: String, reloadedFor: Option[String]): Freshness =
    if server == mine then Current
    else if reloadedFor.contains(server) then Stuck
    else Reload
