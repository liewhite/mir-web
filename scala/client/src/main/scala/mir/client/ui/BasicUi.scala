package mir.client.ui

import mir.client.game.{Controller, GameClient, UiEvents}
import mir.client.input.{Joystick, Overlay, Pt}
import mir.core.*
import mir.data.Catalog
import mir.protocol.*
import org.scalajs.dom

/**
 * 简单的游戏界面：系统消息、连接状态、锁定怪物信息、摇杆、攻击键、死亡复活。
 * 只为在原版界面（#1156 ui/classic）移植好之前能玩、能验证；原版界面实现同样的 UiEvents + Overlay 后替换它。
 */
final class BasicUi(catalog: Catalog, client: GameClient, ctl: Controller, joystick: Joystick, lobby: Lobby) extends UiEvents, Overlay:
  private final case class Line(text: String, tone: Tone, at: Double)
  private var lines    = Vector.empty[Line]
  private var problem  = Option.empty[String]
  private var dead     = false
  private var attackAt = Option.empty[(Double, Double, Double)] // 攻击键圆心和半径（界面像素）
  private var reviveAt = Option.empty[(Double, Double, Double, Double)]
  private var attackFinger = Option.empty[Double]

  private def log(text: String, tone: Tone): Unit =
    lines = (lines :+ Line(text, tone, dom.window.performance.now())).takeRight(BasicUi.MaxLines)

  // ---------------- UiEvents ----------------

  def link(p: Option[String]): Unit           = problem = p
  def loggedIn(account: String): Unit         = lobby.loggedIn(account)
  def charList(chars: List[CharSummary]): Unit = lobby.charList(chars)
  def entered(map: MapId): Unit =
    dead = client.view.self.exists(_.dead)
    lobby.hide()
  def notice(text: String, tone: Tone): Unit = log(text, tone)
  def chat(from: String, text: String, kind: ChatKind): Unit =
    log(if kind == ChatKind.Shout then s"(!)$from：$text" else s"$from：$text", Tone.System)
  def npcOpened(npc: ActorId, merchant: MerchantRef): Unit =
    log("商店、仓库界面还在移植（#1156）", Tone.System)
  def levelUp(level: Int): Unit = log(s"升到 $level 级！", Tone.Good)
  def died(): Unit =
    dead = true
    log("你死了", Tone.Bad)
  def kicked(reason: String): Unit = lobby.fail(s"已下线：$reason")

  // ---------------- Overlay ----------------

  private def inCircle(p: Pt, c: Option[(Double, Double, Double)]): Boolean =
    c.exists((x, y, r) => math.hypot(p.x - x, p.y - y) <= r)

  def pointerDown(p: Pt, pointer: Double): Boolean =
    if dead && reviveAt.exists((x, y, w, h) => p.x >= x && p.x <= x + w && p.y >= y && p.y <= y + h) then
      client.send(GameCmd.Revive)
      dead = false
      true
    else if inCircle(p, attackAt) then
      attackFinger = Some(pointer)
      ctl.attack()
      true
    else false

  def pointerMove(p: Pt, pointer: Double): Unit = ()
  def pointerUp(p: Pt, pointer: Double): Unit   = if attackFinger.contains(pointer) then attackFinger = None

  // ---------------- 画 ----------------

  /** 在界面坐标系里画（w、h 是界面像素大小，ctl 是操作件放大倍数）。 */
  def draw(g: dom.CanvasRenderingContext2D, w: Double, h: Double, ctlScale: Double): Unit =
    val now = dom.window.performance.now()
    g.font = "13px \"PingFang SC\",\"Microsoft YaHei\",sans-serif"
    g.textBaseline = "alphabetic"
    // 系统消息：左下，10 秒后淡出
    g.textAlign = "left"
    lines = lines.filter(l => now - l.at < BasicUi.LineMs)
    lines.reverse.zipWithIndex.foreach { (l, i) =>
      shadowText(g, l.text, 10, h - 150 - i * 17, BasicUi.color(l.tone))
    }
    // 锁定的怪：上方中间显示名字和血
    client.locked.flatMap(client.actor).foreach { a =>
      val name = a.look match
        case Look.Monster(kind, _, _) => catalog.displayName(kind).value
        case _                        => ""
      g.textAlign = "center"
      shadowText(g, s"$name  ${a.hp.value}/${a.maxHp.value}", w / 2, 24, "#ffd166")
    }
    // 摇杆
    if joystick.active then
      g.strokeStyle = "rgba(255,255,255,.5)"
      g.lineWidth = 2
      g.beginPath()
      g.arc(joystick.base.x, joystick.base.y, joystick.radius, 0, math.Pi * 2)
      g.stroke()
      g.fillStyle = "rgba(255,255,255,.35)"
      g.beginPath()
      g.arc(joystick.knob.x, joystick.knob.y, joystick.radius * 0.45, 0, math.Pi * 2)
      g.fill()
    joystick.radius = Joystick.Radius * ctlScale
    // 攻击键：右下
    val r  = 34 * ctlScale
    val ax = w - r - 24 * ctlScale
    val ay = h - r - 150 * ctlScale
    attackAt = Some((ax, ay, r))
    g.fillStyle = if attackFinger.isDefined then "rgba(200,60,40,.75)" else "rgba(120,30,20,.6)"
    g.beginPath()
    g.arc(ax, ay, r, 0, math.Pi * 2)
    g.fill()
    g.textAlign = "center"
    g.textBaseline = "middle"
    shadowText(g, "攻击", ax, ay, "#ffe8c0")
    g.textBaseline = "alphabetic"
    // 连接状态
    problem.foreach { p =>
      g.textAlign = "center"
      g.fillStyle = "rgba(0,0,0,.7)"
      g.fillRect(w / 2 - 150, h / 2 - 60, 300, 32)
      shadowText(g, p, w / 2, h / 2 - 38, "#ff8a8a")
    }
    // 死亡：点「复活」回城
    if dead then
      val (bx, by, bw, bh) = (w / 2 - 70, h / 2 - 20, 140.0, 40.0)
      reviveAt = Some((bx, by, bw, bh))
      g.fillStyle = "rgba(60,10,10,.85)"
      g.fillRect(bx, by, bw, bh)
      g.textAlign = "center"
      shadowText(g, "你死了，点这里复活", w / 2, by + 26, "#ffb0b0")
    else reviveAt = None

  private def shadowText(g: dom.CanvasRenderingContext2D, s: String, x: Double, y: Double, color: String): Unit =
    g.fillStyle = "#000"
    g.fillText(s, x + 1, y + 1)
    g.fillStyle = color
    g.fillText(s, x, y)

object BasicUi:
  val MaxLines = 6
  val LineMs   = 10000.0

  def color(t: Tone): String = t match
    case Tone.System => "#e8dcc0"
    case Tone.Good   => "#9cff8a"
    case Tone.Bad    => "#ff8a8a"
    case Tone.Item   => "#ffe08a"
