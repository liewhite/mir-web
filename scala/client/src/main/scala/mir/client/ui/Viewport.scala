package mir.client.ui

/**
 * 按屏幕定地图和界面的缩放，照 TS 版 ui/viewport.ts。
 * 用户 11-23（#1140）：所有设备都用原版底栏（地图和界面一起按 800×600 等比缩放），摇杆、攻击键、技能环叠在底栏上方；
 * 手机上底栏缩得很小，操作件按约 0.95 倍实际大小画，手指才点得到。不做电脑鼠标操作。
 */
final case class Viewport(
    /** 地图：1 逻辑像素 = 多少 CSS 像素 */
    worldZoom: Double,
    /** 界面：1 界面像素 = 多少 CSS 像素 */
    uiScale: Double,
    /** 触屏操作件相对界面的放大倍数 */
    ctlScale: Double
)

object Viewport:
  def choose(cssW: Double, cssH: Double): Viewport =
    val fit800 = math.min(cssW / 800, cssH / 600)
    Viewport(fit800, fit800, (0.95 / fit800).max(1).min(2))
