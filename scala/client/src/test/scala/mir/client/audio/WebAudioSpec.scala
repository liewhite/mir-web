package mir.client.audio

import org.scalajs.dom
import zio.*
import zio.test.*

import scala.scalajs.js
import scala.scalajs.js.typedarray.ArrayBuffer

/** 老 iOS（≤14.4，只有 webkitAudioContext、decodeAudioData 只认回调）和新浏览器的差异兜底（#1366）。 */
object WebAudioSpec extends ZIOSpecDefault:
  private val standard: js.Function0[Unit] = () => ()
  private val prefixed: js.Function0[Unit] = () => ()
  private val buffer = js.Dynamic.literal(duration = 1.0)

  private def ctx(decode: js.Function3[ArrayBuffer, js.Function1[js.Any, Unit], js.Function1[js.Any, Unit], js.Any]): dom.AudioContext =
    js.Dynamic.literal(decodeAudioData = decode).asInstanceOf[dom.AudioContext]

  private def decoded(c: dom.AudioContext) =
    ZIO.fromFuture(_ => WebAudio.decode(c, new ArrayBuffer(4))).either

  def spec = suite("WebAudio")(
    test("先用标准 AudioContext，没有就用 webkitAudioContext，都没有是 None") {
      val both   = js.Dynamic.literal(AudioContext = standard, webkitAudioContext = prefixed)
      val webkit = js.Dynamic.literal(webkitAudioContext = prefixed)
      assertTrue(
        WebAudio.pick(both).exists(_ eq standard.asInstanceOf[js.Dynamic]),
        WebAudio.pick(webkit).exists(_ eq prefixed.asInstanceOf[js.Dynamic]),
        WebAudio.pick(js.Dynamic.literal()).isEmpty,
        WebAudio.pick(js.Dynamic.literal(AudioContext = 1)).isEmpty
      )
    },
    test("老 WebKit 只回调、不返回 Promise：照样解出来") {
      val c = ctx((_, ok, _) => { ok(buffer); js.undefined })
      decoded(c).map(r => assertTrue(r.exists(_ eq buffer.asInstanceOf[dom.AudioBuffer])))
    },
    test("老 WebKit 解码失败走错误回调（参数是 null）：是失败，不挂住") {
      val c = ctx((_, _, bad) => { bad(null); js.undefined })
      decoded(c).map(r => assertTrue(r.isLeft))
    },
    test("新浏览器回调和 Promise 都给：只取一次结果；Promise 失败也不漏成未处理的 rejection") {
      val good = ctx((_, ok, _) => { ok(buffer); js.Promise.resolve[js.Any](buffer) })
      val bad  = ctx((_, _, err) => { err("坏数据"); js.Promise.reject("坏数据") })
      for
        g <- decoded(good)
        b <- decoded(bad)
      yield assertTrue(g.isRight, b.isLeft)
    },
    test("decodeAudioData 同步抛错：是失败的 Future，不往外抛") {
      val c = ctx((_, _, _) => js.special.`throw`(js.Error("不支持")))
      decoded(c).map(r => assertTrue(r.isLeft))
    },
    test("resume/close：不返回 Promise 当作完成，同步抛错是失败") {
      val old    = js.Dynamic.literal(resume = (() => js.undefined): js.Function0[js.Any]).asInstanceOf[dom.AudioContext]
      val broken = js.Dynamic.literal(close = (() => js.special.`throw`(js.Error("坏了"))): js.Function0[js.Any]).asInstanceOf[dom.AudioContext]
      for
        r <- ZIO.fromFuture(_ => WebAudio.resume(old)).either
        c <- ZIO.fromFuture(_ => WebAudio.close(broken)).either
      yield assertTrue(r.isRight, c.isLeft)
    }
  )
