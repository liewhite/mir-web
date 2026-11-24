package mir.client.render

import mir.client.assets.Sprite
import mir.model.BodyTint
import org.scalajs.dom

import scala.collection.mutable
import scala.util.Try

/**
 * 给一帧图染色（中毒、麻痹，原版 CliUtil.pas BuildColorLevels / DrawEffect）：每个点取 RGB 平均亮度 n，
 * 绿 = (0, n, 0)、红 = (n, 0, 0)、灰 = (n, n, n)，透明度不变。原版再按 256 色调色板找最近的颜色，这里是真彩色，直接用。
 * 染好的图按（帧, 颜色）缓存；只有身上带状态的角色才用到，数量少，超过上限整个清掉重来。
 */
final class Tinter:
  private val cache = mutable.HashMap.empty[(Sprite, BodyTint), Option[dom.HTMLCanvasElement]]

  /** 染好色的这一帧（和原图同大小，从 (0,0) 画起）；浏览器不让读像素时为空，照原样画。 */
  def apply(sp: Sprite, tint: BodyTint): Option[dom.HTMLCanvasElement] =
    if cache.size >= Tinter.MaxCached then cache.clear()
    cache.getOrElseUpdate((sp, tint), render(sp, tint))

  private def render(sp: Sprite, tint: BodyTint): Option[dom.HTMLCanvasElement] =
    val c = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
    c.width = math.max(1, sp.w)
    c.height = math.max(1, sp.h)
    val g = c.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
    g.drawImage(sp.img, sp.sx, sp.sy, sp.w, sp.h, 0, 0, sp.w, sp.h)
    Try(g.getImageData(0, 0, c.width, c.height)).toOption.map { data =>
      val px = data.data
      var i  = 0
      while i < px.length do
        val n = (px(i) + px(i + 1) + px(i + 2)) / 3
        val (r, gr, b) = Tinter.channels(tint, n)
        px(i) = r
        px(i + 1) = gr
        px(i + 2) = b
        i += 4
      g.putImageData(data, 0, 0)
      c
    }

object Tinter:
  private val MaxCached = 512

  /** 亮度 n 染成某色后的 (R, G, B)。 */
  def channels(tint: BodyTint, n: Int): (Int, Int, Int) = tint match
    case BodyTint.Green => (0, n, 0)
    case BodyTint.Red   => (n, 0, 0)
    case BodyTint.Gray  => (n, n, n)
