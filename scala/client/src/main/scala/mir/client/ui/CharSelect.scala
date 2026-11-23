package mir.client.ui

import mir.client.input.Pt
import mir.model.{Job, Sex}
import mir.protocol.{CharSummary, SessionCmd}
import org.scalajs.dom

/**
 * 原版选人画面（照 ref/mir2delphi SelectChrScene.pas + FState.pas DSelectChr / DCreateChr）：
 * 800×600 背景（prguse 65）上左右两个位，选中的人从石像里活过来（ChrSel 图库），没选中的变回石像；
 * 「新人物」在空位弹出建角色窗（职业、性别、名字）；「删除人物」确认后删；「开始」进游戏。
 */
final class CharSelect(ui: ClassicUi, initial: List[CharSummary]):
  import CharSelect.*
  import ui.g

  /** 一个位的动画：石像、正在活过来、活着（选中时动）、正在变回石像。since 是这个状态开始的时刻。 */
  private enum Pose:
    case Stone
    case Waking(since: Double)
    case Alive(since: Double)
    case Freezing(since: Double)

  /** 一个位：有人（或正在建的人），选中没有、动画。 */
  private final case class Slot(job: Job, sex: Sex, name: String, level: Int, pose: Pose)

  /** 正在建角色：在第几个位，选的职业性别 */
  private final case class Creating(index: Int, job: Job, sex: Sex)

  private var chars    = initial.take(2).toVector
  private var selected = 0
  private var poses    = Vector[Pose](Pose.Alive(now), Pose.Stone)
  private var creating = Option.empty[Creating]
  private var entering = false
  private var pressed  = Option.empty[Rect]
  /** 画面上的提示和它消失的时刻 */
  private var note     = Option.empty[(String, Double)]
  private val nameBox  = NameInput(() => confirmCreate())

  private def now: Double = dom.window.performance.now()

  /** 服务端回了新的角色列表（建了、删了）。 */
  def update(list: List[CharSummary]): Unit =
    chars = list.take(2).toVector
    creating = None
    nameBox.close()
    selected = math.min(selected, math.max(0, chars.size - 1))
    poses = Vector.tabulate(2)(i => if i == selected then Pose.Alive(now) else Pose.Stone)

  private def ox: Double = math.round((ui.W - 800) / 2).toDouble
  private def oy: Double = math.round((ui.H - 600) / 2).toDouble

  /** 第 i 个位上现在显示谁（建角色时显示正在建的样子）。 */
  private def slotOf(i: Int): Option[Slot] =
    creating.filter(_.index == i).map(c => Slot(c.job, c.sex, "", 0, poses(i)))
      .orElse(chars.lift(i).map(c => Slot(c.job, c.sex, c.name, c.level, poses(i))))

  // ======================= 画 =======================

  def draw(): Unit =
    val t = now
    g.rect(0, 0, ui.W, ui.H, "#000")
    g.img(Gfx.Main, 65, ox, oy)
    advance(t)
    (0 to 1).foreach(i => slotOf(i).foreach(drawSlot(i, _, t)))
    buttons.foreach { (id, r) => if pressed.contains(r) then g.img(Gfx.Main, ButtonImg(id), r.x, r.y) }
    creating.foreach(drawCreate)
    val title = if entering then "进入游戏…" else "热血传奇 1.76"
    g.text(title, ui.W / 2 - g.width(title) / 2, oy + 8, "#fff", bold = true)
    note.filter(_._2 > t).foreach { (text, _) =>
      g.text(text, ui.W / 2 - g.width(text) / 2, oy + 430, "#ff8a8a", bold = true)
    }

  /** 服务端拒绝了（名字重复、进不去…）：在画面上显示几秒。 */
  def notice(text: String): Unit =
    if entering then ui.lobby.hide()
    entering = false
    note = Some(text -> (now + NoteMs))

  /** 推进动画：活过来、变回石像播完就换状态；没选中的活人开始变回石像。 */
  private def advance(t: Double): Unit =
    poses = poses.zipWithIndex.map { (p, i) =>
      val sel = i == selected
      p match
        case Pose.Waking(s) if t - s > FrameMs * FreezeFrames   => Pose.Alive(t)
        case Pose.Freezing(s) if t - s > FrameMs * FreezeFrames => Pose.Stone
        case Pose.Alive(_) if !sel                               => Pose.Freezing(t)
        case Pose.Stone if sel && slotOf(i).isDefined            => Pose.Waking(t)
        case other                                               => other
    }

  private def drawSlot(i: Int, s: Slot, t: Double): Unit =
    val (bx0, by0, fdx, fdy) = Offsets(s.job -> s.sex)
    val (bx, by)  = (ox + bx0 + (if i == 1 then 340 else 0), oy + by0 + (if i == 1 then 2 else 0))
    val (fx, fy)  = (bx + fdx, by + fdy)
    val stone     = 60 + s.job.code * 40 + s.sex.code * 120
    val alive     = 40 + s.job.code * 40 + s.sex.code * 120
    s.pose match
      case Pose.Stone => g.img("chrsel", stone, bx, by)
      case Pose.Waking(since) =>
        val k = ((t - since) / FrameMs).toInt
        g.img("chrsel", stone + math.min(k, FreezeFrames - 1), bx, by)
        val (ex, ey) = if i == 1 then (ox + 430, oy + 60) else (ox + 90, oy + 58)
        g.ctx.globalCompositeOperation = "lighter"
        g.img("chrsel", 4 + math.min(k, EffectFrames - 1), ex, ey)
        g.ctx.globalCompositeOperation = "source-over"
      case Pose.Freezing(since) =>
        val k = ((t - since) / FrameMs).toInt
        g.img("chrsel", stone + math.max(0, FreezeFrames - 1 - k), bx, by)
      case Pose.Alive(since) =>
        val k = ((t - since) / SelectedMs).toInt % SelectedFrames
        g.img("chrsel", alive + k, fx, fy)
    if s.name.nonEmpty then
      val (tx, ys) = if i == 0 then (117.0, List(494.0, 523.0, 553.0)) else (671.0, List(494.0, 527.0, 557.0))
      List(s.name, s.level.toString, s.job.label).zip(ys).foreach((v, y) => g.text(v, ox + tx, oy + y, "#fff", bold = true))

  // ---------------- 建角色窗（DCreateChr） ----------------

  private def createOrigin(c: Creating): Pt = Pt(ox + (if c.index == 0 then 415 else 75), oy + 15)

  private def createButtons(c: Creating): List[(Rect, Int, () => Unit)] =
    val o = createOrigin(c)
    def r(img: Int, x: Double, y: Double) =
      val (w, h) = g.size(Gfx.Main, img).getOrElse((40, 30))
      Rect(o.x + x, o.y + y, w, h)
    List(
      (r(74, 48, 157), 74, () => pick(c.copy(job = Job.Warrior))),
      (r(75, 93, 157), 75, () => pick(c.copy(job = Job.Wizard))),
      (r(76, 138, 157), 76, () => pick(c.copy(job = Job.Taoist))),
      (r(77, 93, 231), 77, () => pick(c.copy(sex = Sex.Male))),
      (r(78, 138, 231), 78, () => pick(c.copy(sex = Sex.Female))),
      (r(62, 104, 361), 62, () => confirmCreate()),
      (r(64, 248, 31), 64, () => cancelCreate())
    )

  private def drawCreate(c: Creating): Unit =
    val o = createOrigin(c)
    g.img(Gfx.Main, 73, o.x, o.y)
    createButtons(c).foreach { (r, img, _) =>
      val chosen = img match
        case 74 => Option.when(c.job == Job.Warrior)(55)
        case 75 => Option.when(c.job == Job.Wizard)(56)
        case 76 => Option.when(c.job == Job.Taoist)(57)
        case 77 => Option.when(c.sex == Sex.Male)(58)
        case 78 => Option.when(c.sex == Sex.Female)(59)
        case _  => None
      if pressed.contains(r) then g.img(Gfx.Main, img, r.x, r.y) else chosen.foreach(g.img(Gfx.Main, _, r.x, r.y))
    }
    // 名字框跟着窗口（界面像素 → CSS 像素）
    val s = ui.env.uiScale()
    nameBox.place((o.x + 71) * s, (o.y + 107) * s, 120 * s, 16 * s)

  private def pick(c: Creating): Unit =
    creating = Some(c)
    poses = poses.updated(c.index, Pose.Alive(now))

  private def startCreate(): Unit =
    if chars.size >= 2 then notice("每个账号最多两个角色")
    else
      val i = chars.size
      creating = Some(Creating(i, Job.Warrior, Sex.Male))
      selected = i
      poses = poses.updated(i, Pose.Alive(now))
      nameBox.open()

  private def confirmCreate(): Unit = creating.foreach { c =>
    val name = nameBox.value.trim
    // 名字框留着：服务端回了新的角色列表才收（名字重复被拒时还能改）
    if name.nonEmpty then ui.client.send(SessionCmd.CreateChar(name, c.job, c.sex))
  }

  private def cancelCreate(): Unit =
    creating = None
    nameBox.close()
    selected = 0
    poses = Vector.tabulate(2)(i => if i == 0 then Pose.Alive(now) else Pose.Stone)

  // ======================= 选人画面的按钮（DSelectChr） =======================

  private def buttons: List[(Btn, Rect)] = Btn.values.toList.map { b =>
    val (x, y) = ButtonAt(b)
    val (w, h) = g.size(Gfx.Main, ButtonImg(b)).getOrElse((60, 20))
    b -> Rect(ox + x, oy + y, w, h)
  }

  private def run(b: Btn): Unit = if creating.isEmpty && !entering then
    b match
      case Btn.Select1 => choose(0)
      case Btn.Select2 => choose(1)
      case Btn.Start   => start()
      case Btn.NewChr  => startCreate()
      case Btn.Erase   => erase()
      case Btn.Credits => ()
      case Btn.Exit    => ui.logout()

  private def choose(i: Int): Unit = if i != selected && chars.isDefinedAt(i) then
    selected = i
    ui.env.sound(MeltStoneSound)

  private def start(): Unit = chars.lift(selected) match
    case Some(c) =>
      entering = true
      // 加载地图的进度写在加载页上；进了图（entered）或被拒（notice）时收起
      ui.lobby.show()
      ui.lobby.status(s"${c.name} 进入游戏…")
      ui.client.enterGame(c.id)
    case None => startCreate()

  private def erase(): Unit = chars.lift(selected).foreach { c =>
    if dom.window.confirm(s"删除「${c.name}」？删除后不能恢复。") then ui.client.send(SessionCmd.DeleteChar(c.id))
  }

  // ======================= 输入 =======================

  private def allTargets: List[(Rect, () => Unit)] =
    creating.fold(buttons.map((b, r) => r -> (() => run(b))))(c => createButtons(c).map((r, _, f) => r -> f)) ++
      // 点人也能选（原版要点名字下的按钮，触屏上人更好点）
      Option.when(creating.isEmpty)((0 to 1).map(i => Rect(ox + 60 + i * 340, oy + 60, 300, 380) -> (() => choose(i)))).toList.flatten

  def pointerDown(p: Pt): Unit = pressed = allTargets.find(_._1.contains(p.x, p.y)).map(_._1)

  def pointerMove(p: Pt): Unit = ()

  def pointerUp(p: Pt): Unit =
    val was = pressed
    pressed = None
    was.filter(_.contains(p.x, p.y)).flatMap(r => allTargets.find(_._1 == r)).foreach { (_, f) =>
      ui.env.sound(ClassicUi.ButtonSound)
      f()
    }

  def key(k: String): Boolean = k match
    case "Enter" if creating.isEmpty => start(); true
    case "ArrowLeft"                 => choose(0); true
    case "ArrowRight"                => choose(1); true
    case _                           => false

object CharSelect:
  /** 选人画面的按钮 */
  enum Btn:
    case Select1, Select2, Start, NewChr, Erase, Credits, Exit

  /** 按钮图号（prguse，按下时画）、位置（800×600 内，FState.pas 968 行起） */
  val ButtonImg: Map[Btn, Int] = Map(
    Btn.Select1 -> 66, Btn.Select2 -> 67, Btn.Start -> 68, Btn.NewChr -> 69, Btn.Erase -> 70, Btn.Credits -> 71, Btn.Exit -> 72
  )
  val ButtonAt: Map[Btn, (Double, Double)] = Map(
    Btn.Select1 -> (134, 454), Btn.Select2 -> (685, 454), Btn.Start -> (367, 457), Btn.NewChr -> (321, 488),
    Btn.Erase -> (311, 509), Btn.Credits -> (362, 529), Btn.Exit -> (379, 559)
  )

  /** 每个职业性别的石像位置 (bx, by) 和活人相对石像的偏移 (fx-bx, fy-by)（SelectChrScene.PlayScene） */
  val Offsets: Map[(Job, Sex), (Double, Double, Double, Double)] = Map(
    (Job.Warrior, Sex.Male)   -> (71, 52, 0, 0),
    (Job.Warrior, Sex.Female) -> (65, 55, 0, 0),
    (Job.Wizard, Sex.Male)    -> (77, 46, 0, 0),
    (Job.Wizard, Sex.Female)  -> (171, 97, -30, -14),
    (Job.Taoist, Sex.Male)    -> (85, 63, 0, 0),
    (Job.Taoist, Sex.Female)  -> (164, 103, -23, -20)
  )

  /** 原版 IntroScn.pas：石像化 13 帧、选中动画 16 帧、特效 14 帧 */
  val FreezeFrames   = 13
  val SelectedFrames = 16
  val EffectFrames   = 14
  val FrameMs        = 50.0
  val SelectedMs     = 300.0
  /** 选人时石头裂开的声音（s_meltstone） */
  val MeltStoneSound = 101
  val NoteMs         = 5000.0

/** 建角色窗的名字框（网页输入框盖在画布上，手机才能弹键盘）。 */
final class NameInput(onEnter: () => Unit):
  private val input: dom.HTMLInputElement =
    val el = dom.document.createElement("input").asInstanceOf[dom.HTMLInputElement]
    el.id = "chrname"
    el.maxLength = 14
    el.placeholder = "角色名"
    el.setAttribute("autocomplete", "off")
    el.classList.add("hidden")
    dom.document.body.appendChild(el)
    el
  input.addEventListener(
    "keydown",
    (e: dom.KeyboardEvent) =>
      e.stopPropagation()
      if e.key == "Enter" then
        e.preventDefault()
        onEnter()
  )

  def value: String = input.value

  def open(): Unit =
    input.value = ""
    input.classList.remove("hidden")
    input.focus()

  def place(x: Double, y: Double, w: Double, h: Double): Unit =
    val st = input.style
    st.left = s"${x}px"
    st.top = s"${y}px"
    st.width = s"${w}px"
    st.height = s"${math.max(h, 24)}px"

  def close(): Unit =
    input.classList.add("hidden")
    input.blur()
