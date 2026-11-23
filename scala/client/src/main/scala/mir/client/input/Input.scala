package mir.client.input

import mir.client.game.{Controller, GameClient}
import mir.client.render.Renderer
import org.scalajs.dom

/** 界面层（底栏、窗口、操作件）先接触摸：接了返回 true，地图就不管这一下。坐标是界面像素。 */
trait Overlay:
  def pointerDown(p: Pt, pointer: Double): Boolean
  def pointerMove(p: Pt, pointer: Double): Unit
  def pointerUp(p: Pt, pointer: Double): Unit

/**
 * 触屏、鼠标、键盘 → 先给界面，界面不要的再变成 Controller 意图（照 TS 版 game/input.ts 的摇杆布局，用户 #1140 定）：
 * 点中怪锁定、点 NPC 对话、点地上物品去捡，否则在左半屏按下就是摇杆；多指同时操作（一指摇杆、一指按技能）。
 * 键盘：F1–F8 放技能，空格捡脚下的东西。不做电脑鼠标走路（用户 11-23）。
 */
final class Input(
    canvas: dom.HTMLCanvasElement,
    renderer: Renderer,
    client: GameClient,
    ctl: Controller,
    overlay: Overlay,
    val joystick: Joystick,
    /** 1 界面像素 = 多少 CSS 像素 */
    uiScale: () => Double,
    /** 界面宽度（界面像素），摇杆只在左半边 */
    uiWidth: () => Double
):
  /** 正按着界面的手指（松开时只交给界面） */
  private val uiFingers = scala.collection.mutable.Set.empty[Double]

  private def css(e: dom.PointerEvent): (Double, Double) =
    val r = canvas.getBoundingClientRect()
    (e.clientX - r.left, e.clientY - r.top)

  private def uiPos(e: dom.PointerEvent): Pt =
    val (x, y) = css(e)
    val s      = uiScale()
    Pt(x / s, y / s)

  canvas.addEventListener("contextmenu", (e: dom.Event) => e.preventDefault())

  canvas.addEventListener(
    "pointerdown",
    (e: dom.PointerEvent) =>
      e.preventDefault()
      val u = uiPos(e)
      if overlay.pointerDown(u, e.pointerId) then uiFingers += e.pointerId
      else
        // 手指滑出画布也继续收到移动；合成事件抓不了也不影响
        try canvas.setPointerCapture(e.pointerId)
        catch case _: scala.scalajs.js.JavaScriptException => ()
        val (cx, cy) = css(e)
        val p        = renderer.toLogical(cx, cy)
        val me       = client.view.me
        // 手指比鼠标粗：点击框向外放宽约 14 个 CSS 像素
        val hit = me.flatMap(id => renderer.actorAt(p, Input.SlopCss / renderer.zoom, id).filterNot(_ == id))
        val cell = renderer.cellAt(p)
        hit match
          case Some(id) => ctl.tapActor(id)
          case None =>
            client.view.ground.values.find(_.cell == cell) match
              case Some(g) => ctl.tapGround(g)
              case None    => if !joystick.active && u.x < uiWidth() / 2 then joystick.start(e.pointerId, u)
  )

  canvas.addEventListener(
    "pointermove",
    (e: dom.PointerEvent) =>
      if joystick.owns(e.pointerId) then
        joystick.move(uiPos(e)) match
          case Some(s) => ctl.stick(s.dir, s.run)
          case None    => ctl.stickReleased()
      else overlay.pointerMove(uiPos(e), e.pointerId)
  )

  private val release: scala.scalajs.js.Function1[dom.PointerEvent, Unit] = e =>
    if joystick.owns(e.pointerId) then
      joystick.end()
      ctl.stickReleased()
    else if uiFingers.remove(e.pointerId) || e.pointerType == "mouse" then overlay.pointerUp(uiPos(e), e.pointerId)

  canvas.addEventListener("pointerup", release)
  canvas.addEventListener("pointercancel", release)

  dom.window.addEventListener(
    "keydown",
    (e: dom.KeyboardEvent) =>
      Input.fKey(e.key) match
        case Some(n) =>
          e.preventDefault()
          ctl.castKey(n)
        case None =>
          if e.key == " " then
            e.preventDefault()
            ctl.pickUpHere()
  )

object Input:
  val SlopCss = 14.0

  def fKey(key: String): Option[Int] = key match
    case s"F$n" => n.toIntOption.filter(k => k >= 1 && k <= 8)
    case _      => None
