package mir.client.assets

import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.concurrent.Future
import scala.scalajs.js

/** 一帧图：在图集页 img 上的位置和大小；绘制时图左上角 = 锚点 + (ox, oy)。 */
final case class Sprite(img: dom.HTMLImageElement, sx: Int, sy: Int, w: Int, h: Int, ox: Int, oy: Int)

/** 取一帧的结果：有图、这帧本来就没图、还在下载。 */
enum Frame:
  case Ready(sprite: Sprite)
  case Empty
  case Loading

  def toOption: Option[Sprite] = this match
    case Ready(s) => Some(s)
    case _        => None

/**
 * 图集按需加载：assets/img/<lib>/<block>.json + <block>_<page>.png（tools/atlas.py 生成），照 TS 版 assets/sprites.ts。
 * json 是 {pages, frames}，frames 每项是 [page, sx, sy, w, h, ox, oy] 或 0（空帧）。
 */
final class SpriteStore(assets: Assets, onFailure: (String, Throwable) => Unit = SpriteStore.warn):
  private val blocks = LoadCache[String, Vector[Option[Sprite]]](SpriteStore.RetryAfterMs, onFailure)

  /** 取一帧；没下载就开始下载。 */
  def get(lib: String, index: Int): Frame =
    if index < 0 then Frame.Empty
    else
      val size = SpriteStore.blockSize(lib)
      val b    = index / size
      blocks.get(s"$lib/$b")(load(lib, b)) match
        case Loaded.Ready(frames) => frames.lift(index - b * size).flatten.fold(Frame.Empty)(Frame.Ready(_))
        case Loaded.Absent        => Frame.Empty
        case Loaded.Pending       => Frame.Loading

  def sprite(lib: String, index: Int): Option[Sprite] = get(lib, index).toOption

  /** 预下载一组帧所在的块。 */
  def preload(lib: String, indices: Iterable[Int]): Future[Unit] =
    val size = SpriteStore.blockSize(lib)
    Future.sequence(indices.map(_ / size).toSet.toList.map(b => blocks.settled(s"$lib/$b")(load(lib, b)))).map(_ => ())

  private def load(lib: String, b: Int): Future[Vector[Option[Sprite]]] =
    assets.text(s"img/$lib/$b.json").flatMap { text =>
      val meta  = js.JSON.parse(text)
      val pages = meta.pages.asInstanceOf[Int]
      val raw   = meta.frames.asInstanceOf[js.Array[js.Any]]
      Future.sequence((0 until pages).map(p => assets.image(s"img/$lib/${b}_$p.png"))).map { imgs =>
        raw.toVector.map { f =>
          if js.typeOf(f) == "number" then None
          else
            val a = f.asInstanceOf[js.Array[Int]]
            imgs.lift(a(0)).map(img => Sprite(img, a(1), a(2), a(3), a(4), a(5), a(6)))
        }
      }
    }

object SpriteStore:
  /** 图集块下载失败后过多久再试（下载本身已带几次重试） */
  val RetryAfterMs = 5000.0

  def warn(key: String, e: Throwable): Unit = dom.console.warn("图集块下载失败", key, e.getMessage)

  /** 每块多少帧：与 tools/build_assets.py block_size 一致。 */
  def blockSize(lib: String): Int = lib.reverse.dropWhile(_.isDigit).reverse match
    case "hum" | "weapon" | "hair"                     => 600
    case "objects" | "stateitem" | "mmap" | "npc"      => 10
    case _                                             => 200
