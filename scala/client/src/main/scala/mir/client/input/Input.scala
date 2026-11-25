package mir.client.input

import mir.client.game.{Controller, GameClient}
import mir.client.render.Renderer
import org.scalajs.dom

/** 界面怎么处理一次按下。 */
enum Press:
  /** 界面接了（按钮、窗口、底栏），地图不管 */
  case Ui
  /** 按在界面上的摇杆区（底栏左下角血/魔球，#1278）：就地起摇杆，不看地上的怪和物品 */
  case Stick
  /** 不在界面上：交给地图（点怪、点物品、左半屏摇杆） */
  case Pass

/**
 * 界面层（底栏、窗口、操作件）先接触摸和按键，按下的结果见 Press；键盘接了返回 true。坐标是界面像素。
 * button：鼠标键（0 左、2 右；手指算 0）。
 */
trait Overlay:
  def pointerDown(p: Pt, pointer: Double, button: Int): Press
  def pointerMove(p: Pt, pointer: Double): Unit
  def pointerUp(p: Pt, pointer: Double): Unit
  def key(k: String): Boolean

/**
 * 触屏、鼠标、键盘 → 先给界面，界面不要的再变成 Controller 意图（照 TS 版 game/input.ts 的摇杆布局，用户 #1140 定）：
 * 点中怪锁定、点 NPC 对话、点地上物品去捡，否则在左半屏按下就是摇杆；底栏左下角血/魔球按下直接是摇杆
 * （满地物品和怪时左半屏一按就命中它们，#1278）；多指同时操作（一指摇杆、一指按技能）。
 * 键盘：先给界面（窗口快捷键、聊天），剩下 F1–F8 放技能、空格捡脚下的东西；WASD 是键盘摇杆（默认跑，按住 Shift 走，#1189），
 * 和屏幕摇杆并存。不做电脑鼠标走路（用户 11-23）。
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
      overlay.pointerDown(u, e.pointerId, e.button) match
        case Press.Ui => uiFingers += e.pointerId
        // 已有一指在推摇杆：这一指不做事（松开交给界面，界面上没按下的按钮，无副作用）
        case Press.Stick if joystick.active => uiFingers += e.pointerId
        case Press.Stick =>
          capture(e)
          joystick.start(e.pointerId, u)
        case Press.Pass => mapDown(e, u)
  )

  /** 手指滑出画布也继续收到移动；合成事件抓不了也不影响 */
  private def capture(e: dom.PointerEvent): Unit =
    try canvas.setPointerCapture(e.pointerId)
    catch case _: scala.scalajs.js.JavaScriptException => ()

  private def mapDown(e: dom.PointerEvent, u: Pt): Unit =
    capture(e)
    val (cx, cy) = css(e)
    val p        = renderer.toLogical(cx, cy)
    val me       = client.view.me
    // 手指比鼠标粗：点击框向外放宽约 14 个 CSS 像素
    val hit = me.flatMap(id => renderer.actorAt(p, Input.SlopCss / renderer.zoom, id))
    val cell = renderer.cellAt(p)
    hit match
      case Some(id) => ctl.tapActor(id)
      case None =>
        client.view.ground.values.find(_.cell == cell) match
          case Some(g) => ctl.tapGround(g)
          case None    => if !joystick.active && u.x < uiWidth() / 2 then joystick.start(e.pointerId, u)

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

  private val keys = KeyStick()

  private def follow(m: KeyMove): Unit = m match
    case KeyMove.Go(s)  => ctl.stick(s.dir, s.run)
    case KeyMove.Stop   => if !joystick.active then ctl.stickReleased()
    case KeyMove.Settle => dom.window.setTimeout(() => settle(), KeyStick.SettleMs): Unit
    case KeyMove.Same   => ()

  private def settle(): Unit = keys.current match
    case Some(s) => ctl.stick(s.dir, s.run)
    case None    => if !joystick.active then ctl.stickReleased()

  /** 带 Ctrl/Cmd/Alt 的是浏览器快捷键（Cmd+W 关页面等），不当走路 */
  private def plain(e: dom.KeyboardEvent): Boolean = !(e.ctrlKey || e.metaKey || e.altKey)

  dom.window.addEventListener(
    "keydown",
    (e: dom.KeyboardEvent) =>
      if !Input.realKey(e) then ()
      else if e.key == "Shift" then follow(keys.shift(true))
      else if overlay.key(e.key) then e.preventDefault()
      else if client.map.isDefined then
        (Input.fKey(e.key), MoveKey.of(e.code).filter(_ => plain(e))) match
          case (Some(n), _) =>
            e.preventDefault()
            ctl.castKey(n)
          case (None, Some(k)) =>
            e.preventDefault()
            follow(keys.shift(e.shiftKey))
            follow(keys.press(k))
          case (None, None) =>
            if e.key == " " then
              e.preventDefault()
              ctl.pickUpHere()
  )

  // 松键不看界面和地图：任何时候松开都要算数，免得卡住一直走
  dom.window.addEventListener(
    "keyup",
    (e: dom.KeyboardEvent) =>
      if !Input.realKey(e) then ()
      else if e.key == "Shift" then follow(keys.shift(false))
      else MoveKey.of(e.code).foreach(k => follow(keys.release(k)))
  )

  // 切走窗口收不到松键：当全部松开
  dom.window.addEventListener("blur", (_: dom.Event) => follow(keys.clear()))
  dom.document.addEventListener(
    "visibilitychange",
    (_: dom.Event) => if dom.document.visibilityState != "visible" then follow(keys.clear())
  )

object Input:
  val SlopCss = 14.0

  /**
   * 真的按键事件：浏览器自动填充（如 Chrome 选了保存的账号）会发不带 key、code 的 keydown/keyup，
   * 照常处理会在字符串匹配里读到 undefined 报错（11-25 线上客户端报错），这种直接忽略。
   */
  def realKey(e: dom.KeyboardEvent): Boolean =
    val d = e.asInstanceOf[scalajs.js.Dynamic]
    !scalajs.js.isUndefined(d.key) && d.key != null && !scalajs.js.isUndefined(d.code) && d.code != null

  def fKey(key: String): Option[Int] = key match
    case s"F$n" => n.toIntOption.filter(k => k >= 1 && k <= 8)
    case _      => None
