package mir.client.game

import mir.core.*
import mir.terrain.Path

/**
 * 远距离自动走（全景地图上点一个地方）：先只看地形一次算好整条路，再沿路一段一段走，
 * 每段朝路上前面几格就近寻路（绕开人和怪）。只靠每步就近寻路会在长墙前卡住（搜索量有限，只能走到离目标最近的死角）。
 */
object Travel:
  /** 算整条路的搜索量上限（格数）：700×700 的大图跨图走一趟够用；搜完要一两百毫秒（到不了的孤岛才会搜完） */
  val PlanNodes = 120_000
  /** 每段朝路上前面第几格走 */
  val Lookahead = 8

  /** 一条走得通的路：起点之后的每一格，至少一格；最后一格就是终点。 */
  final case class Route private (cells: Vector[Cell]):
    def dest: Cell = cells.last

  object Route:
    def of(cells: Vector[Cell]): Option[Route] = Option.when(cells.nonEmpty)(Route(cells))

  /** 沿路走这一帧的结果。 */
  enum Step:
    /** 到了 */
    case Arrived
    /** 剩下的路，这一段朝哪格走 */
    case Next(rest: Route, toward: Cell)
    /** 被挤离了路（拉回、绕人绕远了）：要重算 */
    case OffRoute

  /** 点在墙里时往外找能走的格，最多找这么远（格） */
  val SnapRadius = 12

  /**
   * 从 from 到 to 的整条路；to 在墙里先换成离它最近的能走的格（不然寻路要把整张图搜完才知道到不了，卡顿），
   * 还到不了（不连通）就到离它最近的地方；一步也走不了为 None。
   */
  def plan(walkable: Cell => Boolean, from: Cell, to: Cell): Option[Route] =
    Path.find(walkable, from, snap(walkable, to, from), PlanNodes, adjacent = false)
      .flatMap(dirs => Route.of(dirs.scanLeft(from)(_.step(_)).tail.toVector))

  /** to 能走就是它，否则一圈圈往外找最近的能走的格（一样近的取靠 from 这边的）；都没有就还是 to。 */
  def snap(walkable: Cell => Boolean, to: Cell, from: Cell): Cell =
    (0 to SnapRadius).iterator
      .flatMap(r => ring(to, r).filter(walkable).minByOption(c => ((c.x - to.x).abs + (c.y - to.y).abs, c.chebyshev(from))))
      .nextOption()
      .getOrElse(to)

  private def ring(c: Cell, r: Int): Seq[Cell] =
    if r == 0 then Seq(c)
    else
      for
        dx <- -r to r
        dy <- -r to r
        if dx.abs == r || dy.abs == r
      yield Cell(c.x + dx, c.y + dy)

  /** 站在 at，沿 route 走：去掉已经走过的部分，朝前面第 Lookahead 格（不足就终点）走。 */
  def follow(route: Route, at: Cell): Step =
    if at == route.dest then Step.Arrived
    else
      val cells = route.cells
      cells.lastIndexWhere(_.chebyshev(at) <= 1) match
        case -1 => Step.OffRoute
        case i =>
          val rest = if cells(i) == at then cells.drop(i + 1) else cells.drop(i)
          Route.of(rest).fold(Step.Arrived)(r => Step.Next(r, r.cells(math.min(Lookahead, r.cells.size) - 1)))
