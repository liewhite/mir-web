package mir.client.assets

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success}

/** 按键取一份下载内容的结果。 */
enum Loaded[+A]:
  case Ready(value: A)
  /** 素材里本来就没有（404），不会再下。 */
  case Absent
  /** 还在下载，或下载失败、等过一会儿再试。 */
  case Pending

/**
 * 按键下载并缓存（图集块、地面块用）：同一个键同时只下一份；下好就一直用；404 记成没有，不再下；
 * 其他失败（断网、请求被取消、图片没解码出来）不记死：过 `retryAfterMs` 后再被要到时重下（#1251：以前失败一次就一直黑到刷新页面）。
 * 每次失败交给 `onFailure`（上报用）。
 */
final class LoadCache[K, A](
    retryAfterMs: Double,
    onFailure: (K, Throwable) => Unit,
    now: () => Double = () => js.Date.now()
):
  private enum Entry:
    case Loading(done: Future[Unit])
    case Ready(value: A)
    case Absent
    case Failed(at: Double)

  private val entries = mutable.HashMap.empty[K, Entry]

  /** 取结果；没下过、或上次失败已过了重试间隔就开始下载。 */
  def get(key: K)(load: => Future[A]): Loaded[A] = entry(key, load) match
    case Entry.Ready(v)                    => Loaded.Ready(v)
    case Entry.Absent                      => Loaded.Absent
    case Entry.Loading(_) | Entry.Failed(_) => Loaded.Pending

  /** 下载结束（不管成败）时完成；用于切图前的预下载。 */
  def settled(key: K)(load: => Future[A]): Future[Unit] = entry(key, load) match
    case Entry.Loading(done) => done
    case _                   => Future.unit

  private def entry(key: K, load: => Future[A]): Entry = entries.get(key) match
    case Some(Entry.Failed(at)) if now() - at >= retryAfterMs => start(key, load)
    case Some(e)                                             => e
    case None                                                => start(key, load)

  private def start(key: K, load: => Future[A]): Entry =
    val done = Future.delegate(load).transform { r =>
      entries(key) = r match
        case Success(v)                 => Entry.Ready(v)
        case Failure(_: Assets.Missing) => Entry.Absent
        case Failure(e) =>
          onFailure(key, e)
          Entry.Failed(now())
      Success(())
    }
    val e = Entry.Loading(done)
    entries(key) = e
    e
