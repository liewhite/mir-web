package mir.client

import mir.client.assets.{Assets, SpriteStore}
import mir.client.audio.Audio
import mir.client.game.{ClientEnv, Controller, GameClient}
import mir.client.input.{Input, Joystick}
import mir.client.net.{LocalServer, WsTransport}
import mir.client.render.{Renderer, ViewOptions}
import mir.client.ui.{ClassicUi, Gfx, Insets, Lobby, UiEnv, Viewport}
import mir.core.*
import mir.data.Catalog
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*
import zio.json.*

import scala.concurrent.Future
import scala.scalajs.js

/**
 * 入口：读游戏数据 → 登录（地址带 ?local 时跳过，在浏览器里跑本地服务端）→ 连服务端 → 选角色 → 主循环。
 * 模块：net（连接、对时、本地服务端）、assets（素材）、render（画面）、audio、input（触屏键盘）、game（客户端状态、意图）、ui。
 */
object Main:
  /** 进图前最多等多久预下载（网络慢也别卡住） */
  val PreloadCapMs = 30000
  val PingEveryMs  = 10000
  val OptionsKey   = "mir176.options"

  def main(args: Array[String]): Unit =
    val local = new dom.URLSearchParams(dom.window.location.search).has("local")
    Lobby.status("读取游戏数据…")
    // 素材要登录后才给，先登录再读数据（旧版留下的 cookie 新服务端不认，也会走到登录表单）
    val boot =
      for
        _       <- if local then Future.unit else Lobby.ensureLogin()
        _        = Lobby.status("读取游戏数据…")
        assets  <- Assets.load()
        text    <- assets.text("data/game.json")
        catalog <- Catalog.decode(text).fold(e => Future.failed(Exception(s"游戏数据有误：$e")), Future.successful)
        audio   <- Audio.load(assets)
      yield start(catalog, assets, audio, local)
    boot.failed.foreach { e =>
      dom.console.error(e.toString)
      Lobby.status(s"出错了：${e.getMessage}")
    }

  private def start(catalog: Catalog, assets: Assets, audio: Audio, local: Boolean): Unit =
    val canvas   = dom.document.querySelector("#game").asInstanceOf[dom.HTMLCanvasElement]
    val sprites  = SpriteStore(assets)
    val renderer = Renderer(canvas, sprites, assets, catalog)
    val joystick = Joystick()
    var view     = Viewport.choose(dom.window.innerWidth, dom.window.innerHeight)

    // 地图下载要知道自己在哪（预下载周围一屏），client 建好后才有
    var clientRef = Option.empty[GameClient]
    val env = ClientEnv(
      localNow = () => dom.window.performance.now(),
      loadMap = id =>
        catalog.map(id) match
          case None => Future.failed(Exception(s"数据表里没有地图 ${id.value}"))
          case Some(d) =>
            Lobby.status(s"加载地图 ${d.title}…")
            assets.map(id, d.asset).flatMap { md =>
              val center = clientRef.flatMap(_.view.self).map(_.cell).getOrElse(Cell(0, 0))
              val preload = renderer.preloadAround(md, center, (n, total) => Lobby.status(s"加载画面… $n/$total"))
              Future.firstCompletedOf(List(preload, Assets.sleep(PreloadCapMs))).map(_ => md)
            }
      ,
      sound = (id, at, me) => audio.play(id, at, me)
    )
    val client = GameClient(catalog, env)
    clientRef = Some(client)
    val lobby = Lobby(client.send)
    val ctl   = Controller(client)
    def applyOptions(o: ViewOptions): Unit =
      client.options = o
      audio.soundOn = o.soundOn
      audio.music(o.musicOn)
    val uiEnv = UiEnv(
      sound = id => audio.play(id, None, None),
      saveOptions = o =>
        dom.window.localStorage.setItem(OptionsKey, o.toJson)
        applyOptions(o),
      // 退出：重新打开页面回到选人（服务端在断线时存档）
      logout = () => dom.window.location.reload(),
      uiScale = () => view.uiScale
    )
    val touch = dom.window.matchMedia("(pointer: coarse)").matches
    val ui    = ClassicUi(Gfx(renderer.ctx, sprites), catalog, client, ctl, joystick, lobby, uiEnv, touch)
    client.ui = ui

    applyOptions(loadOptions())
    Input(canvas, renderer, client, ctl, ui, joystick, () => view.uiScale, () => dom.window.innerWidth / view.uiScale)
    val unlock: js.Function1[dom.Event, Unit] = _ => audio.unlock()
    dom.window.addEventListener("pointerdown", unlock)
    dom.window.addEventListener("keydown", unlock)

    def fit(): Unit =
      view = Viewport.choose(dom.window.innerWidth, dom.window.innerHeight)
      renderer.resize(dom.window.innerWidth, dom.window.innerHeight, view.worldZoom)
      val css = dom.window.getComputedStyle(dom.document.documentElement)
      def inset(name: String): Double = css.getPropertyValue(name).trim.stripSuffix("px").toDoubleOption.getOrElse(0.0) / view.uiScale
      ui.safe = Insets(inset("--safe-l"), inset("--safe-r"), inset("--safe-t"), inset("--safe-b"))
    fit()
    dom.window.addEventListener("resize", (_: dom.Event) => fit())
    // iOS 转屏后 innerWidth 有时晚一帧才更新
    dom.window.addEventListener("orientationchange", (_: dom.Event) => dom.window.setTimeout(() => fit(), 300): Unit)

    lobby.status("连接服务器…")
    client.connect(if local then LocalServer(catalog, assets, client.receive) else WsTransport(client.receive))
    dom.window.setInterval(() => client.ping(), PingEveryMs)

    lazy val frame: js.Function1[Double, Unit] = t =>
      ctl.update()
      client.scene(t).foreach(renderer.draw)
      if client.map.isEmpty then
        renderer.ctx.fillStyle = "#000"
        renderer.ctx.fillRect(0, 0, renderer.width, renderer.height)
      renderer.scaleTo(view.uiScale)
      ui.draw(dom.window.innerWidth / view.uiScale, dom.window.innerHeight / view.uiScale, view.ctlScale)
      dom.window.requestAnimationFrame(frame): Unit
    dom.window.requestAnimationFrame(frame): Unit

    DebugHandle.install(client, ctl, renderer, ui)

  private def loadOptions(): ViewOptions =
    Option(dom.window.localStorage.getItem(OptionsKey)).flatMap(_.fromJson[ViewOptions].toOption).getOrElse(ViewOptions())

/**
 * 给自动化测试（browser-cli）看的把手：window.__mir 上读状态摘要、触发操作。只读视野副本、只调公开的意图入口，不绕过协议。
 */
object DebugHandle:
  def install(client: GameClient, ctl: Controller, renderer: Renderer, ui: ClassicUi): Unit =
    val handle = js.Dynamic.literal(
      state = () =>
        js.JSON.parse(
          zio.json.ast.Json.Obj(
            "me"     -> client.self.fold(zio.json.ast.Json.Null)(s => zio.json.ast.Json.Str(s"${s.cell.x},${s.cell.y} hp=${s.hp.value} dead=${s.dead}")),
            "map"    -> client.view.map.fold(zio.json.ast.Json.Null)(m => zio.json.ast.Json.Str(m.value)),
            "loaded" -> zio.json.ast.Json.Bool(client.map.isDefined),
            "locked" -> client.locked.fold(zio.json.ast.Json.Null)(id => zio.json.ast.Json.Num(id.value)),
            "exp"    -> client.view.stats.fold(zio.json.ast.Json.Null)(s => zio.json.ast.Json.Num(s.exp.value)),
            "actors" -> zio.json.ast.Json.Arr(client.actors.toSeq.sortBy(_.id.value).map { a =>
              zio.json.ast.Json.Str(s"${a.id.value} ${a.look.getClass.getSimpleName} ${lookName(a.look)} ${a.cell.x},${a.cell.y} ${a.action.kind} hp=${a.hp.value}/${a.maxHp.value}${if a.dead then " dead" else ""}")
            }*)
          ).toJson
        ),
      stick = (dir: Int, run: Boolean) => ctl.stick(Dir.fromCode(dir), run),
      release = () => ctl.stickReleased(),
      tap = (id: Int) => ctl.tapActor(ActorId(id)),
      attack = () => ctl.attack(),
      goTo = (x: Int, y: Int) => ctl.goTo(Cell(x, y)),
      teleport = (i: Int) => client.send(mir.protocol.GameCmd.Teleport(TeleportRef(i))),
      cast = (skill: Int) => ctl.castSkill(SkillId(skill)),
      newChar = (name: String, job: Int) =>
        client.send(mir.protocol.SessionCmd.CreateChar(name, mir.model.Job.fromOrdinal(job), mir.model.Sex.Male)),
      enter = (id: Int) => client.enterGame(CharId(id.toLong)),
      cam = () => js.Array(renderer.camX, renderer.camY, renderer.zoom),
      screen = () => ui.screen.toString.takeWhile(_ != '('),
      chat = () => js.Array(ui.chat.map(_.text)*),
      bag = () => js.Array(client.view.bag.flatten.map(_.name.value)*),
      worn = () => js.Array(client.view.worn.toSeq.map((s, i) => s"${s.key}=${i.name.value}")*),
      gold = () => client.view.stats.fold(0L)(_.gold.value),
      sent = () => js.Array(client.recent.filter(_.isInstanceOf[mir.protocol.GameCmd]).map(_.toString)*)
    )
    dom.window.asInstanceOf[js.Dynamic].__mir = handle

  private def lookName(l: mir.protocol.Look): String = l match
    case h: mir.protocol.Look.Human   => h.name
    case m: mir.protocol.Look.Monster => m.kind.value
    case n: mir.protocol.Look.Npc     => s"npc${n.merchant.index}"
