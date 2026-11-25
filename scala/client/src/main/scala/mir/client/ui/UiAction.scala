package mir.client.ui

/**
 * 界面操作：底栏按钮和键盘共用（键位表 UiAction.Keys 是数据，改键或加键只改表）。
 * F1–F8 技能、空格捡取由 input/Controller 管，不在这里。
 */
enum UiAction:
  case Chat
  /** 用快捷栏第几格（0 起） */
  case Belt(slot: Int)
  case Bag
  case State(page: StatePage)
  case Settings
  case MiniMap
  /** 全景地图 */
  case WorldMap
  case Teleport
  /** 组队窗口（#1220） */
  case Group
  /** 好友窗口（#1221） */
  case Friends
  /** 和锁定或身边的玩家请求交易（#1222，底栏交易键） */
  case Trade
  /** 私聊：聊天框预填「/最近私聊的人 」（原版按「/」键） */
  case Whisper
  /** 在新标签页打开留言板（#1280，左上角「留言」键） */
  case MessageBoard
  case CloseAll

object UiAction:
  /** 留言板页面（client/web/board.html，和游戏同一个登录） */
  val MessageBoardPage = "board.html"

  /** 键盘键位（KeyboardEvent.key → 操作） */
  val Keys: Map[String, UiAction] =
    def all(a: UiAction, ks: String*) = ks.map(_ -> a)
    (all(Chat, "Enter") ++
      (1 to 6).map(i => i.toString -> Belt(i - 1)) ++
      all(Bag, "F9", "b", "B", "i", "I") ++
      all(State(StatePage.Equip), "F10", "c", "C") ++
      all(State(StatePage.Magic), "F11", "k", "K") ++
      all(MiniMap, "Tab") ++
      all(WorldMap, "m", "M") ++
      all(Teleport, "t", "T") ++
      all(Group, "p", "P") ++
      all(Friends, "f", "F") ++
      all(Whisper, "/") ++
      all(CloseAll, "Escape")).toMap

  /** 设置窗底部的键位说明 */
  val Help = "WASD 跑(按住Shift走)  F1-F8 技能  1-6 快捷栏  空格 捡取  回车 说话  / 私聊  T 传送  M 全景地图  P 组队  F 好友  Esc 关闭"
