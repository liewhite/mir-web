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
  case Teleport
  case CloseAll

object UiAction:
  /** 键盘键位（KeyboardEvent.key → 操作） */
  val Keys: Map[String, UiAction] =
    def all(a: UiAction, ks: String*) = ks.map(_ -> a)
    (all(Chat, "Enter") ++
      (1 to 6).map(i => i.toString -> Belt(i - 1)) ++
      all(Bag, "F9", "b", "B", "i", "I") ++
      all(State(StatePage.Equip), "F10", "c", "C") ++
      all(State(StatePage.Magic), "F11", "k", "K") ++
      all(MiniMap, "Tab", "m", "M") ++
      all(Teleport, "t", "T") ++
      all(CloseAll, "Escape")).toMap

  /** 设置窗底部的键位说明 */
  val Help = "F1-F8 技能  1-6 快捷栏  空格 捡取  回车 说话  T 传送  Esc 关闭"
