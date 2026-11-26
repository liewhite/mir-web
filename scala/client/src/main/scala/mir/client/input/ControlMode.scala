package mir.client.input

import mir.client.render.ViewOptions

/** 地图上怎么操作（#1353 设置里切换）。 */
enum ControlMode:
  /** 摇杆（或 WASD）走跑、点怪锁定、屏幕上的攻击键和 F1–F8 技能环打锁定的（用户 #1140、#1189） */
  case Touch
  /**
   * 原版鼠标操作（用户 11-26 #1353，照原版 ClMain.pas）：左键按住地面走、右键按住跑，左键点怪打，
   * F1–F8 技能放向鼠标指着的对象/格子；不画摇杆、攻击键、技能环。WASD 照样能走。
   */
  case Classic

object ControlMode:
  /** 电脑默认原版操作，设置里能关；触屏设备没有鼠标，只能用摇杆 */
  def of(touch: Boolean, o: ViewOptions): ControlMode =
    if !touch && o.classicControls then Classic else Touch
