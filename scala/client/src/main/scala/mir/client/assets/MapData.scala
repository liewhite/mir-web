package mir.client.assets

import mir.core.MapId
import mir.terrain.Terrain

import scala.scalajs.js.typedarray.{ArrayBuffer, DataView, Int8Array, Uint16Array, Uint8Array}

/**
 * 一张地图的格子数据：assets/map/<asset>.bin（tools/build_assets.py write_map_bin），行优先 i = y*w + x。
 * 地形（能不能走、挡不挡魔法）交给共用的 Terrain；画图用的前景层、动画、门在这里。照 TS 版 assets/mapdata.ts。
 */
final class MapData private (
    val id: MapId,
    /** 素材名（地面图目录 assets/ground/<asset>/），内容相同的地图共用 */
    val asset: String,
    val width: Int,
    val height: Int,
    val terrain: Terrain,
    /** 前景图号（从 1 起，0 无） */
    front: Uint16Array,
    /** Objects 图库编号：0 → objects，n → objects{n+1} */
    area: Uint8Array,
    ani: Uint8Array,
    aniTick: Uint8Array
):
  def inside(x: Int, y: Int): Boolean = x >= 0 && y >= 0 && x < width && y < height

  /** 某格的前景物件；没有为 None。 */
  def objectAt(x: Int, y: Int): Option[MapObject] =
    if !inside(x, y) then None
    else
      val i = y * width + x
      val f = front(i)
      if f == 0 then None
      else
        val a = ani(i)
        Some(MapObject(MapData.objectLib(area(i)), f - 1, a & 0x7f, aniTick(i), (a & 0x80) != 0))

object MapData:
  /** 格子像素大小（原版 UNITX、UNITY） */
  val UnitX = 48
  val UnitY = 32
  /** 地面预渲染大块：16×16 格一张图 */
  val GroundBlock = 16

  def objectLib(area: Int): String = if area == 0 then "objects" else s"objects${area + 1}"

  def parse(id: MapId, asset: String, buf: ArrayBuffer): Either[String, MapData] =
    val dv = new DataView(buf)
    if buf.byteLength < 8 then Left(s"地图 ${id.value} 文件太短")
    else
      val magic = (0 until 4).map(i => dv.getUint8(i).toChar).mkString
      val w     = dv.getUint16(4, true)
      val h     = dv.getUint16(6, true)
      val n     = w * h
      if magic != "M176" then Left(s"地图 ${id.value} 格式不对（开头不是 M176）")
      else if buf.byteLength < 8 + n * 9 then Left(s"地图 ${id.value} 文件太短：$w×$h 要 ${8 + n * 9} 字节，只有 ${buf.byteLength}")
      else
        val flags = new Int8Array(buf, 8, n).toArray
        // 前景图号是 u16 小端，起点不一定对齐 2 字节，逐个读
        val front = new Uint16Array(n)
        val fOff  = 8 + n
        var i     = 0
        while i < n do
          front(i) = dv.getUint16(fOff + i * 2, true)
          i += 1
        def u8(k: Int): Uint8Array = new Uint8Array(buf, 8 + n * 3 + n * k, n)
        Right(MapData(id, asset, w, h, Terrain.fromFlags(w, h, flags), front, u8(0), u8(1), u8(2)))

/** 一格前景物件：图库、首帧号（从 0 起）、动画帧数（0 不动）、动画每帧多拖几拍、是否叠加混合（灯火等）。 */
final case class MapObject(lib: String, index: Int, aniFrames: Int, aniTick: Int, blend: Boolean):
  /** 动画到第几帧：aniCount 是 100 毫秒一拍的计数（原版做法）。 */
  def frameAt(aniCount: Long): Int =
    if aniFrames <= 0 then index
    else index + ((aniCount % (aniFrames + aniFrames * aniTick)) / (1 + aniTick)).toInt
