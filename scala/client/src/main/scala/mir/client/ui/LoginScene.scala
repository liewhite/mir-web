package mir.client.ui

import mir.client.assets.{Assets, SpriteStore}
import mir.client.input.Pt
import mir.client.net.AccountApi
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * 原版登录画面（照 ref/mir2delphi LoginScene.pas + FState.pas DLogin，#1197）：
 * 石门背景（ChrSel 22，800×600）正中摆登录框（prguse 60），账号、密码是盖在框上的网页输入框（手机才能弹键盘、浏览器能记密码）。
 * 「提交」登录；「新用户」用框里填的账号密码直接注册（只要账号和密码，注册成功即已登录）；登录成功石门打开（ChrSel 23-32，
 * 每 50ms 一帧，开门声 100），开完淡出，`run` 的 Future 完成。
 * 进游戏前就要画，所以不经 ClassicUi：自己管画布大小、绘制循环和点击，结束时把监听都摘掉。
 * 这里用到的图和声音服务端不登录也给（HttpServer.LoginScreenAssets）。
 */
final class LoginScene(canvas: dom.HTMLCanvasElement, assets: Assets):
  import LoginScene.*

  private val ctx      = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
  private val g        = Gfx(ctx, SpriteStore(assets))
  private val form     = $[dom.HTMLFormElement]("#login")
  private val account  = $[dom.HTMLInputElement]("#account")
  private val password = $[dom.HTMLInputElement]("#password")
  /** 右上角「留言板」链接（#1280，不登录也能看） */
  private val boardLink = $[dom.HTMLElement]("#board-link")
  private val done     = Promise[Unit]()
  private var phase: Phase = Phase.Form(busy = false)
  private var pressed      = Option.empty[Btn]
  /** 框下的提示和它消失的时刻 */
  private var note         = Option.empty[(String, Double)]
  /** 开门声：在点按钮的手势里先建好（iOS 只许手势里起头的声音），登录成功才放 */
  private var doorSound    = Option.empty[dom.HTMLAudioElement]
  private var listeners    = List.empty[() => Unit]

  private def now: Double = dom.window.performance.now()

  def run(): Future[Unit] =
    form.classList.remove("hidden")
    Option(boardLink).foreach(_.classList.remove("hidden"))
    form.onsubmit = e =>
      e.preventDefault()
      act(Btn.Ok)
    listen(account, "keydown", (e: dom.KeyboardEvent) =>
      // 原版：账号框回车跳到密码框
      if e.key == "Enter" then
        e.preventDefault()
        password.focus()
    )
    listen(password, "keydown", (e: dom.KeyboardEvent) =>
      if e.key == "Enter" then
        e.preventDefault()
        act(Btn.Ok)
    )
    listen(canvas, "pointerdown", (e: dom.PointerEvent) => pressed = buttonAt(Pt(e.clientX, e.clientY)))
    listen(canvas, "pointerup", (e: dom.PointerEvent) =>
      val was = pressed
      pressed = None
      was.filter(b => buttonAt(Pt(e.clientX, e.clientY)).contains(b)).foreach(act)
    )
    lazy val frame: js.Function1[Double, Unit] = _ =>
      draw()
      if !done.isCompleted then dom.window.requestAnimationFrame(frame): Unit
    dom.window.requestAnimationFrame(frame): Unit
    if !touch then account.focus()
    done.future

  private def listen[E <: dom.Event](target: dom.EventTarget, ev: String, f: E => Unit): Unit =
    val h: js.Function1[E, Unit] = f
    target.addEventListener(ev, h)
    listeners ::= (() => target.removeEventListener(ev, h))

  // ======================= 操作 =======================

  private def act(b: Btn): Unit = phase match
    case Phase.Form(false) =>
      b match
        case Btn.Ok       => submit(register = false)
        case Btn.New      => submit(register = true)
        case Btn.ChangePw => say("改密码请找管理员")
        case Btn.Close    => ()
    case _ => ()

  private def submit(register: Boolean): Unit =
    val a = account.value.trim
    val p = password.value
    if a.isEmpty || p.isEmpty then
      say(if register then "先在框里填好想要的账号和密码，再点「新用户」" else "请输入账号和密码")
      (if a.isEmpty then account else password).focus()
    else if !register || dom.window.confirm(s"用账号「$a」注册新用户？\n密码请记牢，忘了只能找管理员改。") then
      prepareDoorSound()
      phase = Phase.Form(busy = true)
      say(if register then "注册中…" else "登录中…")
      (if register then AccountApi.register(a, p) else AccountApi.login(a, p)).foreach {
        case Right(()) => open()
        case Left(why) =>
          phase = Phase.Form(busy = false)
          say(why)
      }

  /** 登录成功：收起输入框，开门。 */
  private def open(): Unit =
    note = None
    form.classList.add("hidden")
    Option(boardLink).foreach(_.classList.add("hidden"))
    account.blur()
    password.blur()
    phase = Phase.Opening(now)
    // 放不出来（浏览器不让）就算了
    doorSound.flatMap(_.play().toOption).foreach(_.toFuture.recover(_ => ()): Unit)

  private def prepareDoorSound(): Unit = if doorSound.isEmpty then
    val a = dom.document.createElement("audio").asInstanceOf[dom.HTMLAudioElement]
    a.src = assets.url(s"sound/$DoorSound.m4a")
    a.load()
    doorSound = Some(a)

  private def finish(): Unit =
    listeners.foreach(_())
    listeners = Nil
    form.onsubmit = null
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.fillStyle = "#000"
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    done.trySuccess(()): Unit

  private def say(text: String): Unit = note = Some(text -> (now + NoteMs))

  // ======================= 画 =======================

  private def layout: Layout = Layout.of(dom.window.innerWidth, dom.window.innerHeight)

  private def buttonAt(p: Pt): Option[Btn] =
    val l = layout
    Btn.values.find(b => l.button(b, g.size(Gfx.Main, b.img)).contains(p.x, p.y))

  private def draw(): Unit =
    val l   = layout
    val dpr = math.min(2.0, if dom.window.devicePixelRatio > 0 then dom.window.devicePixelRatio else 1.0)
    val (cw, ch) = (math.round(l.w * dpr).toInt, math.round(l.h * dpr).toInt)
    if canvas.width != cw || canvas.height != ch then
      canvas.width = cw
      canvas.height = ch
      canvas.style.width = s"${l.w}px"
      canvas.style.height = s"${l.h}px"
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.fillStyle = "#000"
    ctx.fillRect(0, 0, cw, ch)
    ctx.imageSmoothingEnabled = true
    val t = now
    // 石门（800×600 按屏幕等比缩放）
    ctx.setTransform(dpr * l.scene, 0, 0, dpr * l.scene, dpr * l.sceneX, dpr * l.sceneY)
    g.img("chrsel", Background, 0, 0)
    phase match
      case Phase.Opening(since) =>
        val k = math.min(((t - since) / FrameMs).toInt, DoorFrames - 1)
        g.img("chrsel", DoorFirst + k, DoorX, DoorY)
        // 开到底后淡出（原版 g_boDoFadeOut），淡完进选人
        val fade = (t - since - FrameMs * (DoorFrames - 1)) / FadeMs
        if fade > 0 then
          ctx.setTransform(1, 0, 0, 1, 0, 0)
          ctx.fillStyle = s"rgba(0,0,0,${math.min(1.0, fade)})"
          ctx.fillRect(0, 0, cw, ch)
        if fade >= 1 then finish()
      case Phase.Form(_) =>
        // 登录框（屏幕正中，手机上不跟着背景缩得太小）
        ctx.setTransform(dpr * l.box, 0, 0, dpr * l.box, dpr * l.boxX, dpr * l.boxY)
        if g.sprite(Gfx.Main, BoxImg).isDefined then g.img(Gfx.Main, BoxImg, 0, 0)
        else g.frame(Rect(0, 0, BoxW, BoxH), "#1b130a", "#8a6a3a")
        pressed.foreach(b => g.img(Gfx.Main, b.img, b.at.x, b.at.y))
        note.filter(_._2 > t).foreach { (text, _) =>
          g.text(text, BoxW / 2 - g.width(text) / 2, BoxH + 6, "#ff8a8a", bold = true)
        }
        placeInput(account, l, AccountAt)
        placeInput(password, l, PasswordAt)

  private def placeInput(el: dom.HTMLInputElement, l: Layout, at: Pt): Unit =
    val s  = el.style
    val fs = math.max(16.0, 12 * l.box)
    s.left = s"${l.boxX + at.x * l.box}px"
    s.top = s"${l.boxY + at.y * l.box + (FieldH * l.box - fs) / 2 - 1}px"
    s.width = s"${FieldW * l.box}px"
    s.height = s"${fs + 2}px"
    s.fontSize = s"${fs}px"

object LoginScene:
  private def $[T <: dom.Element](sel: String): T = dom.document.querySelector(sel).asInstanceOf[T]
  private def touch: Boolean = dom.window.matchMedia("(pointer: coarse)").matches

  private enum Phase:
    /** 填表（busy：登录/注册请求还没回来） */
    case Form(busy: Boolean)
    /** 石门在开，since 是开始的时刻 */
    case Opening(since: Double)

  /** 登录框上的按钮：图号（prguse，按下时画）和在框内的位置（FState.pas 830 行起）。 */
  enum Btn(val img: Int, val at: Pt):
    case New      extends Btn(61, Pt(24, 207))
    case Ok       extends Btn(62, Pt(171, 165))
    case ChangePw extends Btn(53, Pt(111, 207))
    case Close    extends Btn(64, Pt(252, 28))

  /** ChrSel 图库：石门背景、开门第一帧（10 帧，第一帧是空的=门还关着）、开门图在 800×600 里的位置（LoginScene.PlayScene） */
  val Background = 22
  val DoorFirst  = 23
  val DoorFrames = 10
  val DoorX      = 152.0
  val DoorY      = 96.0
  val FrameMs    = 50.0
  val FadeMs     = 500.0
  /** s_rock_door_open */
  val DoorSound  = 100
  val NoteMs     = 5000.0

  /** 登录框（prguse 60）大小、两个输入框在框内的位置和大小（原版 EdId/EdPasswd 在 800×600 的 (350,258)/(350,290)，框在 (252,173)） */
  val BoxImg     = 60
  val BoxW       = 296.0
  val BoxH       = 254.0
  val AccountAt  = Pt(98, 85)
  val PasswordAt = Pt(98, 117)
  val FieldW     = 137.0
  val FieldH     = 16.0

  /**
   * 画面摆放（CSS 像素）：石门 800×600 整个放进屏幕（scene 倍、左上角 sceneX/Y）；
   * 登录框在屏幕正中，倍数跟石门一样，但至少 1 倍（手机上原样大小才点得准、看得清），也不超出屏幕。
   */
  final case class Layout(w: Double, h: Double, scene: Double, sceneX: Double, sceneY: Double, box: Double, boxX: Double, boxY: Double):
    def button(b: Btn, size: Option[(Int, Int)]): Rect =
      val (bw, bh) = size.getOrElse((60, 30))
      Rect(boxX + b.at.x * box, boxY + b.at.y * box, bw * box, bh * box)

  object Layout:
    def of(w: Double, h: Double): Layout =
      val scene = math.min(w / 800, h / 600)
      val box   = math.min(math.max(scene, 1.0), math.min(w * 0.96 / BoxW, h * 0.96 / (BoxH + 20)))
      Layout(w, h, scene, (w - 800 * scene) / 2, (h - 600 * scene) / 2, box, (w - BoxW * box) / 2, (h - BoxH * box) / 2)
