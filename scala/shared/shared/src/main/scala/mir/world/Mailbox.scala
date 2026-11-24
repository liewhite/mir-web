package mir.world

import mir.core.*
import mir.protocol.*

import scala.collection.mutable

/** 到下次 flush 为止攒下的要发的东西：给某个玩家的、附近的人都能看到的、离开本图的人。 */
final case class Pending(
    personal: Vector[(ActorId, Vector[ServerMsg])],
    /** 发生在哪一格、是什么 */
    local: Vector[(Cell, ServerMsg)],
    departed: Vector[Departure],
    /** 要立刻存档的玩家（升级、交易成交） */
    checkpoints: Set[ActorId] = Set.empty
)

/** 世界要发出去的消息：先攒着，flush 时由 Visibility 按视野分给每个玩家。 */
final class Mailbox(w: World):
  private val personal = mutable.LinkedHashMap.empty[ActorId, mutable.ArrayBuffer[ServerMsg]]
  private val local    = mutable.ArrayBuffer.empty[(Cell, ServerMsg)]
  private val departed = mutable.ArrayBuffer.empty[Departure]
  private val saves    = mutable.LinkedHashSet.empty[ActorId]

  private[world] def tell(p: Player, msg: ServerMsg): Unit =
    personal.getOrElseUpdate(p.id, mutable.ArrayBuffer.empty) += msg

  private[world] def notice(p: Player, text: String, tone: Tone): Unit = tell(p, ServerMsg.Notice(text, tone))
  private[world] def refuse(p: Player, why: String): Unit            = tell(p, ServerMsg.Refused(why))

  /** 附近的人都看得到/听得到的事。 */
  private[world] def nearby(at: Cell, msg: ServerMsg): Unit = local += at -> msg

  private[world] def sound(at: Cell, id: Int): Unit = nearby(at, ServerMsg.Sound(id, Some(at)))
  /** 只有自己听得到的声音（脚步、喝药） */
  private[world] def selfSound(p: Player, id: Int): Unit = tell(p, ServerMsg.Sound(id, None))

  private[world] def effect(
      lib: String, start: Int, frames: Int, frameTime: Int, at: Anchor,
      from: Option[Cell] = None, dir: Option[Dir] = None, dirStride: Option[Int] = None,
      blend: Boolean = true, duration: Option[Int] = None
  ): Unit =
    val cell = at match
      case Anchor.OnCell(c)   => c
      case Anchor.OnActor(id) => w.actor(id).fold(from.getOrElse(Cell(0, 0)))(_.cell)
    nearby(cell, ServerMsg.Effect(EffectView(lib, start, frames, frameTime, at, from, dir, dirStride, blend, w.now, duration.getOrElse(frames * frameTime))))

  /** 玩家离开本图去别处（服务端交给目的地图）。 */
  private[world] def depart(d: Departure): Unit = departed += d

  /**
   * 这个玩家要立刻存档（升级、交易成交）：交易双方在同一步里存，免得服务端中途崩溃时一方的存档是交易前、
   * 另一方是交易后（东西变成两份或没了）。
   */
  private[world] def checkpoint(p: Player): Unit = saves += p.id

  /** 取走攒下的全部。 */
  private[world] def drain(): Pending =
    val out = Pending(personal.toVector.map((id, m) => id -> m.toVector), local.toVector, departed.toVector, saves.toSet)
    saves.clear()
    personal.clear()
    local.clear()
    departed.clear()
    out
