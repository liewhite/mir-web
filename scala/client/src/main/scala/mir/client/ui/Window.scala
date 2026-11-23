package mir.client.ui

import mir.client.input.Pt
import mir.model.ItemInstance

/**
 * 游戏画面里一个可开可关的窗口（包裹、状态窗、商人、设置……）。ClassicUi 持有按层次排好的窗口列表（Windows），
 * 绘制、点击叠放、全部关闭、悬停说明都遍历它：新加窗口只写一个实现、在列表里排好位置，不用改别处。
 */
trait Window:
  def isOpen: Boolean
  /** 画并登记点击区（只在 isOpen 时调用）；登记顺序就是叠放顺序 */
  def draw(m: Me): Unit
  def close(): Unit
  /** 鼠标下身上的物品（包裹格、装备格）：悬停说明和包裹信息栏用 */
  def itemAt(m: Me, p: Pt): Option[ItemInstance] = None
  /** 鼠标下别的东西的说明（商品、属性、技能）；电脑悬停用 */
  def hover(m: Me, p: Pt): Option[List[Line]] = None

/** 只有开、关两种状态的窗口。 */
abstract class ToggleWindow extends Window:
  private var open = false
  def isOpen: Boolean = open
  def close(): Unit   = open = false
  /** 打开；子类要在打开时复位的覆写它 */
  def show(): Unit    = open = true
  def toggle(): Unit  = if open then close() else show()
