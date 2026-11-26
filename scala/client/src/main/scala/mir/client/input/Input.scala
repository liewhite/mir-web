package mir.client.input

import mir.client.game.{Aim, Controller, GameClient, LeftClick, Pointing}
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
  /** 这一点盖着界面（窗口、底栏、按钮）：鼠标在上面时不算指着地图上的东西 */
  def covers(p: Pt): Boolean
  /** 现在地图上怎么操作（设置里切换，#1353） */
  def controls: ControlMode

/**
 * 触屏、鼠标、键盘 → 先给界面，界面不要的再变成 Controller 意图（照 TS 版 game/input.ts 的摇杆布局，用户 #1140 定）：
 * 点中怪锁定、点 NPC 对话、点地上物品去捡，否则在左半屏按下就是摇杆；底栏左下角血/魔球按下直接是摇杆
 * （满地物品和怪时左半屏一按就命中它们，#1278）；多指同时操作（一指摇杆、一指按技能）。
 * 键盘：先给界面（窗口快捷键、聊天），剩下 F1–F8 放技能、空格捡脚下的东西；WASD 是键盘摇杆（默认跑，按住 Shift 走，#1189），
 * 和屏幕摇杆并存。
 * 原版鼠标操作（ControlMode.Classic，电脑默认，#1353，照原版 ClMain.pas 的鼠标处理）：地图上左键交给 Controller.leftClick
 * （点在空地上就按着朝鼠标走，Ctrl 跑），右键按着朝鼠标跑（Ctrl+右键点玩家看装备），松开就停；F1–F8 技能放向鼠标（Aim.Mouse）；
 * 每帧（frame）算鼠标指着的对象，显示它的名字。
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

  /** 原版操作：鼠标最后在画布上的位置（CSS 像素），移出画布为 None */
  private var mouseCss = Option.empty[(Double, Double)]
  /** 原版操作：按着鼠标朝它走（哪个指针、跑不跑）；没按着为 None */
  private var steering = Option.empty[(Double, Boolean)]

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
        case Press.Pass =>
          overlay.controls match
            case ControlMode.Touch   => mapDown(e, u)
            case ControlMode.Classic => classicDown(e)
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

  /** 鼠标指着的地方：对象按图像点中（不放宽），没点中图像就取站在那一格上的活物（鼠标指着怪脚下也算指着它） */
  private def pointing(cx: Double, cy: Double): Pointing =
    val p    = renderer.toLogical(cx, cy)
    val cell = renderer.cellAt(p)
    val me   = client.view.me
    val onCell = client.actors.find(a => !a.dead && a.cell == cell && !me.contains(a.id)).map(_.id)
    Pointing(cell, me.flatMap(id => renderer.actorAt(p, 0, id)).orElse(onCell))

  /** 原版操作在地图上按下：左键交给 Controller，右键跑（Ctrl+右键点玩家看装备）。 */
  private def classicDown(e: dom.PointerEvent): Unit =
    capture(e)
    val (cx, cy) = css(e)
    mouseCss = Some((cx, cy))
    val at = pointing(cx, cy)
    e.button match
      case 2 => if !(e.ctrlKey && ctl.inspect(at)) then steer(e.pointerId, run = true)
      case 0 =>
        ctl.leftClick(at, force = e.shiftKey) match
          case LeftClick.Walk => steer(e.pointerId, run = e.ctrlKey)
          case LeftClick.Done => ()
      case _ => ()

  /** 开始（或接着）朝鼠标走 */
  private def steer(pointer: Double, run: Boolean): Unit =
    steering = Some((pointer, run))
    steerNow()

  private def steerNow(): Unit = for
    (_, run) <- steering
    (cx, cy) <- mouseCss
  do
    val (dx, dy) = renderer.cellsFromSelf(renderer.toLogical(cx, cy))
    ctl.steer(dx, dy, run)

  /** 每帧：原版操作下算鼠标指着的对象（怪走动、自己走动时鼠标不动也会变），盖着界面时不算。 */
  def frame(): Unit =
    client.hovered = overlay.controls match
      case ControlMode.Touch => None
      case ControlMode.Classic =>
        mouseCss.filterNot((x, y) => overlay.covers(Pt(x / uiScale(), y / uiScale()))).flatMap((x, y) => pointing(x, y).actor)

  /** 现在放技能往哪放：原版操作指向鼠标（鼠标还没进过画布就照触屏的办法） */
  private def aim: Aim = (overlay.controls, mouseCss) match
    case (ControlMode.Classic, Some((x, y))) => Aim.Mouse(pointing(x, y))
    case _                                   => Aim.Auto

  canvas.addEventListener("pointerleave", (e: dom.PointerEvent) => if e.pointerType == "mouse" && steering.isEmpty then mouseCss = None)

  canvas.addEventListener(
    "pointermove",
    (e: dom.PointerEvent) =>
      val steers = steering.exists(_._1 == e.pointerId)
      if e.pointerType == "mouse" || steers then mouseCss = Some(css(e))
      if steers then steerNow()
      else if joystick.owns(e.pointerId) then
        joystick.move(uiPos(e)) match
          case Some(s) => ctl.stick(s.dir, s.run)
          case None    => ctl.stickReleased()
      else overlay.pointerMove(uiPos(e), e.pointerId)
  )

  private val release: scala.scalajs.js.Function1[dom.PointerEvent, Unit] = e =>
    if steering.exists(_._1 == e.pointerId) then
      steering = None
      ctl.stickReleased()
    else if joystick.owns(e.pointerId) then
      joystick.end()
      ctl.stickReleased()
    else if uiFingers.remove(e.pointerId) || e.pointerType == "mouse" then overlay.pointerUp(uiPos(e), e.pointerId)

  canvas.addEventListener("pointerup", release)
  canvas.addEventListener("pointercancel", release)

  private val keys = KeyStick()

  private def follow(m: KeyMove): Unit = m match
    case KeyMove.Go(s)  => ctl.stick(s.dir, s.run)
    case KeyMove.Stop   => if !joystick.active && steering.isEmpty then ctl.stickReleased()
    case KeyMove.Settle => dom.window.setTimeout(() => settle(), KeyStick.SettleMs): Unit
    case KeyMove.Same   => ()

  private def settle(): Unit = keys.current match
    case Some(s) => ctl.stick(s.dir, s.run)
    case None    => if !joystick.active && steering.isEmpty then ctl.stickReleased()

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
            ctl.castKey(n, aim)
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
