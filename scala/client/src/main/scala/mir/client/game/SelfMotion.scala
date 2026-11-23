package mir.client.game

import mir.core.*
import mir.model.ActKind
import mir.protocol.{ActionView, ActorView, GameCmd}
import mir.rules.Pace

/**
 * 自己走路「本地先走」（原版做法）：发 Walk/Run 的同时在本地就开始走，服务端回 MoveOk 才能发下一步，
 * 回 MoveDenied 就回到服务端的位置（视野副本里的自己就是服务端的真位置，丢掉本地的就行）。
 * 不可变：每个操作返回新状态。
 */
final case class SelfMotion(
    /** 发出去还没回音的一步 */
    pending: Option[GameCmd] = None,
    /** 本地先画的自己（走完、服务端也回了就不用了） */
    local: Option[ActorView] = None,
    /** 被拉回后这之前不再走 */
    blockedUntil: Millis = Millis.zero
):
  import SelfMotion.*

  /** 现在能不能发下一步：没有在等回音、本地这一步走完了、没在被拉回的冷却里。 */
  def ready(now: Millis): Boolean =
    pending.isEmpty && now >= blockedUntil && local.forall(l => now - l.action.startedAt >= l.action.duration)

  /** 走（run 跑两格）一步：返回新状态和要发的指令。调用方先确认 ready、目标格能走。 */
  def step(self: ActorView, dir: Dir, run: Boolean, now: Millis): (SelfMotion, GameCmd) =
    val to     = self.cell.step(dir, if run then 2 else 1)
    val cmd    = if run then GameCmd.Run(self.cell, dir) else GameCmd.Walk(self.cell, dir)
    val dur    = if run then Pace.RunMs else Pace.WalkMs
    val action = ActionView(if run then ActKind.Run else ActKind.Walk, now, dur, from = Some(self.cell))
    (copy(pending = Some(cmd), local = Some(self.copy(cell = to, dir = dir, action = action))), cmd)

  def ok: SelfMotion = copy(pending = None)

  /** 被拒：丢掉本地先走的，短暂不再走（免得对着墙一直被拉回）。 */
  def denied(now: Millis): SelfMotion = SelfMotion(blockedUntil = now + DeniedPauseMs)

  /**
   * 画出来的自己：本地这一步还在走或还没回音时用本地的（血量、外观跟服务端）；
   * 走完以后用服务端的，但服务端那份若是同一步的移动（它开始得晚一点，动画还没完），就按本地的时刻算，免得原地多走一截。
   */
  def shown(server: ActorView, now: Millis): ActorView = local match
    case Some(l) if pending.nonEmpty || now - l.action.startedAt < l.action.duration =>
      l.copy(hp = server.hp, maxHp = server.maxHp, look = server.look, dead = server.dead)
    case Some(l) if server.cell == l.cell && sameMove(server.action, l.action) => server.copy(action = l.action)
    case _                                                                       => server

object SelfMotion:
  /** 被拉回后多久才能再走（原版 1 秒，摇杆贴墙时太卡，取短一点；服务端不限） */
  val DeniedPauseMs = 300L

  private def sameMove(a: ActionView, b: ActionView): Boolean =
    (a.kind == ActKind.Walk || a.kind == ActKind.Run) && a.kind == b.kind && a.from == b.from
