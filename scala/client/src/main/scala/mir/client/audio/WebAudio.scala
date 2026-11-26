package mir.client.audio

import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.typedarray.ArrayBuffer
import scala.util.{Failure, Success, Try}

/**
 * 各代浏览器 Web Audio 的差异收在这里（#1366），Audio 只管播什么、多大声。
 *  - iOS 14.4 及以下（iOS 上所有浏览器都是 WebKit）没有 AudioContext，只有带前缀的 webkitAudioContext；
 *    两个都没有、或建的时候抛错（如同时开的 context 太多），就没有音效，游戏照常启动。
 *  - 老 webkitAudioContext 的 decodeAudioData 只认回调、不返回 Promise；新浏览器两种都支持，统一走回调。
 *  - resume/close 在个别老实现上可能直接抛错或不返回 Promise，一律当作失败的 Future，不往外抛。
 */
private[audio] object WebAudio:
  /** 浏览器提供的构造函数：先标准名，再 webkit 前缀名。按 window 的属性取，没有就是 undefined，不会像裸全局名那样抛 ReferenceError。 */
  private lazy val constructor: Option[js.Dynamic] = pick(dom.window.asInstanceOf[js.Dynamic])

  private[audio] def pick(w: js.Dynamic): Option[js.Dynamic] =
    List("AudioContext", "webkitAudioContext").map(w.selectDynamic).find(c => js.typeOf(c) == "function")

  /** 浏览器有没有 Web Audio；没有就不用再试着建 context。 */
  def supported: Boolean = constructor.isDefined

  /** 新建一个 context；不支持或建的时候抛错就是 None。 */
  def create(): Option[dom.AudioContext] =
    constructor.flatMap(c => Try(js.Dynamic.newInstance(c)().asInstanceOf[dom.AudioContext]).toOption)

  /** 解码一段音频：回调式（老 WebKit 只认这种），同时接住新浏览器返回的 Promise，免得解码失败时报「未处理的 rejection」。 */
  def decode(c: dom.AudioContext, data: ArrayBuffer): Future[dom.AudioBuffer] =
    val p = Promise[dom.AudioBuffer]()
    val ok: js.Function1[dom.AudioBuffer, Unit] = b => p.trySuccess(b): Unit
    val bad: js.Function1[js.Any, Unit]         = e => p.tryFailure(js.JavaScriptException(e)): Unit
    Try(c.asInstanceOf[js.Dynamic].decodeAudioData(data, ok, bad)) match
      case Failure(e) => p.tryFailure(e)
      case Success(r) =>
        if thenable(r) then r.asInstanceOf[js.Promise[dom.AudioBuffer]].toFuture.onComplete(p.tryComplete(_))
    p.future

  def resume(c: dom.AudioContext): Future[Unit] = call(c.asInstanceOf[js.Dynamic].resume())

  def close(c: dom.AudioContext): Future[Unit] = call(c.asInstanceOf[js.Dynamic].close())

  /** 调一个本该返回 Promise 的方法：同步抛错是失败；不返回 Promise 的老实现当作已完成。 */
  private def call(f: => js.Dynamic): Future[Unit] =
    Try(f) match
      case Failure(e)                => Future.failed(e)
      case Success(r) if thenable(r) => r.asInstanceOf[js.Promise[Any]].toFuture.map(_ => ())
      case Success(_)                => Future.unit

  private def thenable(r: js.Dynamic): Boolean =
    !js.isUndefined(r) && r != null && js.typeOf(r.`then`) == "function"

