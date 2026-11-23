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
 * 照 TS 版 audio/audio.ts。浏览器要求用户点过一次才能出声，所以第一次点击/按键时 unlock。
 */
final class Audio(assets: Assets, available: Set[Int]):
  private enum Clip:
    case Loading
    case Ready(buf: dom.AudioBuffer)
    case Missing

  private var ctx    = Option.empty[dom.AudioContext]
  private val clips  = mutable.HashMap.empty[Int, Clip]
  private val recent = mutable.HashMap.empty[Int, Double]
  private var bgm    = Option.empty[dom.HTMLAudioElement]
  var soundOn = true
  private var musicOn = false

  def unlock(): Unit =
    if ctx.isEmpty then ctx = Some(new dom.AudioContext())
    ctx.filter(_.state == "suspended").foreach(_.resume(): Unit)
    if musicOn then bgm.foreach(b => b.play(): Unit)

  /** 播一个音效；at 是声源格子（没有就是自己身上的声音），离 listener 超过 12 格听不见。 */
  def play(id: Int, at: Option[Cell], listener: Option[Cell]): Unit =
    val now = dom.window.performance.now()
    val vol = (at, listener) match
      case (Some(a), Some(l)) =>
        val d = a.chebyshev(l)
        Option.when(d <= 12)(0.8 * (1 - d / 14.0))
      case _ => Some(0.8)
    for
      c <- ctx if soundOn && available.contains(id) && now - recent.getOrElse(id, 0.0) >= 60 // 同一声音太密就跳过
      v <- vol
    do
      recent(id) = now
      clips.get(id) match
        case Some(Clip.Ready(buf)) => start(c, buf, v)
        case Some(_)               => ()
        case None =>
          clips(id) = Clip.Loading
          load(c, id).foreach(b => clips(id) = b.fold(Clip.Missing)(Clip.Ready(_)))

  private def start(c: dom.AudioContext, buf: dom.AudioBuffer, vol: Double): Unit =
    val src = c.createBufferSource()
    src.buffer = buf
    val g = c.createGain()
    g.gain.value = vol
    src.connect(g)
    g.connect(c.destination)
    src.start()

  private def load(c: dom.AudioContext, id: Int): Future[Option[dom.AudioBuffer]] =
    assets.bytes(s"sound/$id.m4a").flatMap(data => c.decodeAudioData(data).toFuture).map(Some(_)).recover(_ => None)

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
      b.play().foreach(_.toFuture.recover(_ => ()): Unit)
    else bgm.foreach(_.pause())

object Audio:
  /** 读有哪些音效（assets/sound/index.json）；拿不到就当没有音效，照样能玩。 */
  def load(assets: Assets): Future[Audio] =
    assets.text("sound/index.json")
      .map(t => js.JSON.parse(t).ids.asInstanceOf[js.Array[Int]].toSet)
      .recover(_ => Set.empty[Int])
      .map(Audio(assets, _))
