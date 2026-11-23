package mir.client.audio

import mir.client.assets.Assets
import mir.core.Cell
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js

/**
 * 音效与背景音乐：Web Audio 播放 assets/sound/<编号>.m4a（tools/build_sounds.py 生成），按离自己的距离降低音量。
 * 照 TS 版 audio/audio.ts。
 *
 * 浏览器（尤其 iOS Safari）的限制和对策（#1180）：
 *  - 要在用户手势里 resume 才出声：AudioContext 一开始就建好（挂起状态也能解码、排队播放），
 *    每次手势（触摸松开、点击、按键，捕获阶段先于游戏处理）都把不在 running 的 context 恢复，
 *    这样第一下点攻击键发出的声音在同一个手势里就放出来了。
 *  - 切 App 回来 context 变成 interrupted/suspended：回到前台时试着恢复，不成就等下一次手势；
 *    恢复失败或恢复后仍不在 running，下一次手势换一个新 context（已解码的音效可以继续用）。
 *  - 音效第一次用要下载解码：常用的（人物动作、受击、魔法）进游戏就预加载；
 *    没加载好的那次请求记下来，加载完 ≤ ReplayWindowMs 内补播。
 */
final class Audio(assets: Assets, available: Set[Int]):
  import Audio.*

  private enum Clip:
    /** 下载解码中；pending 是期间最后一次请求播放（请求时刻、音量）。 */
    case Loading(pending: Option[(Double, Double)])
    case Ready(buf: dom.AudioBuffer)
    case Missing

  private var ctx          = new dom.AudioContext()
  private var needsRebuild = false
  private val clips        = mutable.HashMap.empty[Int, Clip]
  private val recent       = mutable.HashMap.empty[Int, Double]
  private var bgm          = Option.empty[dom.HTMLAudioElement]
  var soundOn = true
  private var musicOn = false

  private def now(): Double = dom.window.performance.now()

  /** 用户手势里调：恢复（必要时重建）音频，iOS 上顺便放一段静音把输出打开。 */
  def unlock(): Unit =
    if needsRebuild || ctx.state == "closed" then
      val old = ctx
      if old.state != "closed" then old.close().toFuture.recover(_ => ()): Unit
      ctx = new dom.AudioContext()
      needsRebuild = false
    resume()
    if ctx.state != "running" then primeSilence(ctx)
    if musicOn then bgm.filter(_.paused).foreach(playBgm)

  /** 页面回到前台（visibilitychange/pageshow/focus）时调：不在手势里，能恢复就恢复，不成等下一次手势。 */
  def wake(): Unit =
    if !needsRebuild && ctx.state != "closed" then resume()
    if musicOn then bgm.filter(_.paused).foreach(playBgm)

  private def resume(): Unit =
    val c = ctx
    if c.state != "running" && c.state != "closed" then
      c.resume().toFuture
        .map(_ => if (c eq ctx) && c.state != "running" then needsRebuild = true)
        .recover(_ => if c eq ctx then needsRebuild = true): Unit

  private def primeSilence(c: dom.AudioContext): Unit =
    val src = c.createBufferSource()
    src.buffer = c.createBuffer(1, 1, 22050)
    src.connect(c.destination)
    src.start()

  /** 播一个音效；at 是声源格子（没有就是自己身上的声音），离 listener 超过 12 格听不见。 */
  def play(id: Int, at: Option[Cell], listener: Option[Cell]): Unit =
    val t = now()
    val vol = (at, listener) match
      case (Some(a), Some(l)) =>
        val d = a.chebyshev(l)
        Option.when(d <= 12)(0.8 * (1 - d / 14.0))
      case _ => Some(0.8)
    for
      v <- vol if soundOn && available.contains(id) && t - recent.getOrElse(id, 0.0) >= 60 // 同一声音太密就跳过
    do
      recent(id) = t
      clips.get(id) match
        case Some(Clip.Ready(buf))  => start(buf, v)
        case Some(Clip.Loading(_))  => clips(id) = Clip.Loading(Some((t, v)))
        case Some(Clip.Missing)     => ()
        case None                   => fetch(id, Some((t, v)))

  /** 预加载一批音效（并发 PreloadParallel 个），不影响已在加载的。 */
  def preload(ids: Iterable[Int]): Future[Unit] =
    val queue = mutable.Queue.from(ids.filter(id => available.contains(id) && !clips.contains(id)))
    def worker(): Future[Unit] =
      if queue.isEmpty then Future.unit
      else
        val id = queue.dequeue()
        (if clips.contains(id) then Future.unit else fetch(id, None)).flatMap(_ => worker())
    Future.sequence(List.fill(PreloadParallel)(worker())).map(_ => ())

  private def fetch(id: Int, pending: Option[(Double, Double)]): Future[Unit] =
    clips(id) = Clip.Loading(pending)
    decode(id).map { result =>
      val waiting = clips.get(id).collect { case Clip.Loading(p) => p }.flatten
      clips(id) = result.fold(Clip.Missing)(Clip.Ready(_))
      for
        buf    <- result
        (t, v) <- waiting if soundOn && now() - t <= ReplayWindowMs
      do start(buf, v)
    }

  private def start(buf: dom.AudioBuffer, vol: Double): Unit =
    val c   = ctx
    val src = c.createBufferSource()
    src.buffer = buf
    val g = c.createGain()
    g.gain.value = vol
    src.connect(g)
    g.connect(c.destination)
    src.start()

  private def decode(id: Int): Future[Option[dom.AudioBuffer]] =
    assets.bytes(s"sound/$id.m4a").flatMap(data => ctx.decodeAudioData(data).toFuture).map(Some(_)).recover(_ => None)

  /** 背景音乐自动播放被拦时会拒绝，忽略，下一次手势再试。 */
  private def playBgm(b: dom.HTMLAudioElement): Unit =
    b.play().foreach(_.toFuture.recover(_ => ()): Unit)

  /** 背景音乐开关：打开时从头放野外音乐，关掉就停。 */
  def music(on: Boolean): Unit =
    musicOn = on
    if on then
      val b = bgm.getOrElse {
        val a = dom.document.createElement("audio").asInstanceOf[dom.HTMLAudioElement]
        a.src = assets.url("sound/bgm_field.m4a")
        a.loop = true
        a.volume = 0.35
        bgm = Some(a)
        a
      }
      playBgm(b)
    else bgm.foreach(_.pause())

object Audio:
  /** 没加载好的音效，加载完多久以内还补播（再晚就和动作对不上了）。 */
  private val ReplayWindowMs  = 400.0
  private val PreloadParallel = 4

  /** 进游戏就预加载的：人物的脚步、挥武器、受击、死亡、喝药捡钱等（编号 < 200）和全部魔法声（≥ 10000）。约 2MB。 */
  def common(id: Int): Boolean = id < 200 || id >= 10000

  /** 读有哪些音效（assets/sound/index.json）；拿不到就当没有音效，照样能玩。 */
  def load(assets: Assets): Future[Audio] =
    assets.text("sound/index.json")
      .map(t => js.JSON.parse(t).ids.asInstanceOf[js.Array[Int]].toSet)
      .recover(_ => Set.empty[Int])
      .map { ids =>
        val a = Audio(assets, ids)
        a.preload(ids.filter(common).toList.sorted)
        a
      }
