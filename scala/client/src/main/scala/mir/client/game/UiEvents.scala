package mir.client.game

import mir.core.*
import mir.protocol.{ChatKind, CharSummary, Tone, WhisperWay}

/**
 * 客户端核心告诉界面的事（界面 ui.ClassicUi 实现它）。
 * 视野、属性、背包这些状态界面直接读 GameClient.view，这里只有一次性的事。
 */
trait UiEvents:
  /** 连接状态变了：None 是正常，Some(说明) 是断开、正在重连等 */
  def link(problem: Option[String]): Unit
  def loggedIn(account: String): Unit
  def charList(chars: List[CharSummary]): Unit
  /** 进了一张图（地图数据已下载好、可以画了） */
  def entered(map: MapId): Unit
  def notice(text: String, tone: Tone): Unit
  def chat(from: String, text: String, kind: ChatKind): Unit
  /** 私聊（#1221）：peer 是对方 */
  def whisper(peer: String, text: String, way: WhisperWay): Unit
  def npcOpened(npc: ActorId, merchant: MerchantRef): Unit
  /** 交易窗口打开了（#1222） */
  def dealOpened(partner: String): Unit
  def levelUp(level: Int): Unit
  def died(): Unit
  def kicked(reason: String): Unit

object UiEvents:
  /** 界面还没接上时：什么也不做 */
  object Ignore extends UiEvents:
    def link(problem: Option[String]): Unit                    = ()
    def loggedIn(account: String): Unit                        = ()
    def charList(chars: List[CharSummary]): Unit               = ()
    def entered(map: MapId): Unit                              = ()
    def notice(text: String, tone: Tone): Unit                 = ()
    def chat(from: String, text: String, kind: ChatKind): Unit = ()
    def whisper(peer: String, text: String, way: WhisperWay): Unit = ()
    def npcOpened(npc: ActorId, merchant: MerchantRef): Unit   = ()
    def dealOpened(partner: String): Unit                      = ()
    def levelUp(level: Int): Unit                              = ()
    def died(): Unit                                           = ()
    def kicked(reason: String): Unit                           = ()
