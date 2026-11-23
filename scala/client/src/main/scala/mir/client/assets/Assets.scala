package mir.client.assets

import mir.core.MapId
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.typedarray.ArrayBuffer

/**
 * 素材地址与下载：assets/ 下的文件（服务端登录后才给），URL 带素材版本（assets/version.json）让浏览器在素材更新后重新下载。
 * 网络会间歇掉线，下载都带重试；404 不重试（素材里本来就没有）。
 */
final class Assets(val version: String):
  def url(path: String): String = s"assets/$path?v=$version"

  def text(path: String): Future[String] = Assets.retry(url(path))(_.text())

  def bytes(path: String): Future[ArrayBuffer] = Assets.retry(url(path))(_.arrayBuffer())

  def image(path: String): Future[dom.HTMLImageElement] =
    val p   = Promise[dom.HTMLImageElement]()
    val img = dom.document.createElement("img").asInstanceOf[dom.HTMLImageElement]
    img.asInstanceOf[js.Dynamic].decoding = "async"
    img.addEventListener("load", (_: dom.Event) => p.trySuccess(img): Unit)
    img.addEventListener("error", (_: dom.Event) => p.tryFailure(Assets.Missing(path)): Unit)
    img.src = url(path)
    p.future

  def map(id: MapId, asset: String): Future[MapData] =
    bytes(s"map/$asset.bin").flatMap(buf => MapData.parse(id, asset, buf).fold(e => Future.failed(Exception(e)), Future.successful))

object Assets:
  /** 素材里没有这个文件（404 或图片解码失败）。 */
  final case class Missing(path: String) extends Exception(s"素材里没有 $path")

  /** 读素材版本；拿不到就用 "0"（只影响缓存，不影响游戏）。 */
  def load(): Future[Assets] =
    dom.fetch("assets/version.json", new dom.RequestInit { cache = dom.RequestCache.`no-store` })
      .toFuture
      .flatMap(r => if r.ok then r.json().toFuture.map(j => j.asInstanceOf[js.Dynamic].v.toString) else Future.successful("0"))
      .recover(_ => "0")
      .map(Assets(_))

  def sleep(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    dom.window.setTimeout(() => p.success(()), ms)
    p.future

  private def retry[A](u: String, tries: Int = 4)(read: dom.Response => js.Promise[A]): Future[A] =
    def attempt(i: Int): Future[A] =
      dom.fetch(u).toFuture.flatMap { r =>
        if r.status == 404 then Future.failed(Missing(u))
        else if !r.ok then Future.failed(Exception(s"$u ${r.status}"))
        else read(r).toFuture
      }.recoverWith {
        case m: Missing                => Future.failed(m)
        case e if i + 1 >= tries       => Future.failed(e)
        case _                         => sleep(800 * (i + 1)).flatMap(_ => attempt(i + 1))
      }
    attempt(0)
