package mir.client.render

import mir.client.assets.Sprite
import org.scalajs.dom

import scala.collection.mutable
import scala.scalajs.js.typedarray.Uint8ClampedArray
import scala.util.Try

/**
 * 身体图哪些点不透明（点选角色按像素判断用，#1284）。只在点击时对鼠标下的那几张图取一次像素，按帧缓存；
 * 超过上限整个清掉重来。浏览器不让读像素时当整张透明，点选退回到正中 40×70 的宽松框。
 */
final class Silhouettes:
  private val cache = mutable.HashMap.empty[Sprite, Option[Uint8ClampedArray]]
  private lazy val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]

  def solid(sp: Sprite, x: Int, y: Int): Boolean =
    x >= 0 && y >= 0 && x < sp.w && y < sp.h && rgba(sp).exists(px => px((y * sp.w + x) * 4 + 3) > 0)

  private def rgba(sp: Sprite): Option[Uint8ClampedArray] =
    if cache.size >= Silhouettes.MaxCached then cache.clear()
    cache.getOrElseUpdate(sp, read(sp))

  private def read(sp: Sprite): Option[Uint8ClampedArray] =
    canvas.width = math.max(1, sp.w)
    canvas.height = math.max(1, sp.h)
    val g = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
    g.clearRect(0, 0, canvas.width, canvas.height)
    g.drawImage(sp.img, sp.sx, sp.sy, sp.w, sp.h, 0, 0, sp.w, sp.h)
    Try(g.getImageData(0, 0, canvas.width, canvas.height).data).toOption

object Silhouettes:
  private val MaxCached = 256
