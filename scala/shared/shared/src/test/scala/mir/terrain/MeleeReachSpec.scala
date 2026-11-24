package mir.terrain

import mir.core.*
import zio.test.*

/** 近身出刀站位（#1264）：开着刺杀时贴身或隔一格直线都能打，都不是才走到最近能打的格子。 */
object MeleeReachSpec extends ZIOSpecDefault:
  private val open: Cell => Boolean = c => c.x >= 0 && c.y >= 0 && c.x < 60 && c.y < 60
  private def walls(ws: Cell*): Cell => Boolean = c => open(c) && !ws.contains(c)

  /** 沿路走完停在哪 */
  private def end(from: Cell, path: Option[List[Dir]]): Option[Cell] = path.map(_.foldLeft(from)(_.step(_)))

  private val t = Cell(30, 30)

  def spec = suite("近身出刀站位")(
    test("贴身都能打；隔一格直线只在开着刺杀时能打，斜线、马步不行") {
      assertTrue(
        MeleeReach.canStrike(Reach.Adjacent, Cell(31, 31), t, open),
        MeleeReach.canStrike(Reach.Long, Cell(29, 30), t, open),
        !MeleeReach.canStrike(Reach.Adjacent, Cell(32, 30), t, open),
        MeleeReach.canStrike(Reach.Long, Cell(32, 30), t, open),
        MeleeReach.canStrike(Reach.Long, Cell(28, 28), t, open),
        MeleeReach.canStrike(Reach.Long, Cell(30, 32), t, open),
        !MeleeReach.canStrike(Reach.Long, Cell(32, 31), t, open),
        !MeleeReach.canStrike(Reach.Long, Cell(33, 30), t, open)
      )
    },
    test("中间那格是墙就不算能刺（不隔墙捅）") {
      assertTrue(!MeleeReach.canStrike(Reach.Long, Cell(32, 30), t, walls(Cell(31, 30))))
    },
    test("Reach.of：学了且开着才是 Long") {
      assertTrue(
        Reach.of(longHitOn = true, knowsLongHit = true) == Reach.Long,
        Reach.of(longHitOn = false, knowsLongHit = true) == Reach.Adjacent,
        Reach.of(longHitOn = true, knowsLongHit = false) == Reach.Adjacent
      )
    },
    test("开着刺杀从远处直线走来：停在隔一格的地方，比贴身少走一步") {
      val from = Cell(36, 30)
      val long = MeleeReach.path(Reach.Long, open, open, from, t, 3000)
      val near = MeleeReach.path(Reach.Adjacent, open, open, from, t, 3000)
      // 隔一格的直线格不止一个（正左、斜上、斜下离得一样近），只看走几步、停的地方能不能出刀
      assertTrue(
        long.map(_.size).contains(4),
        end(from, long).exists(c => c.chebyshev(t) == 2 && MeleeReach.canStrike(Reach.Long, c, t, open)),
        near.map(_.size).contains(5),
        end(from, near).exists(_.chebyshev(t) == 1)
      )
    },
    test("斜着来也停在隔一格的斜线上") {
      val from = Cell(35, 35)
      assertTrue(end(from, MeleeReach.path(Reach.Long, open, open, from, t, 3000)).contains(Cell(32, 32)))
    },
    test("走到先满足的位置就停：马步位置一步就能贴身，不绕去隔一格") {
      val from = Cell(32, 31)
      val path = MeleeReach.path(Reach.Long, open, open, from, t, 3000)
      assertTrue(path.exists(_.size == 1), end(from, path).exists(MeleeReach.canStrike(Reach.Long, _, t, open)))
    },
    test("中间是墙：不站墙后，换一个能打的格子") {
      val ws   = walls(Cell(31, 30))
      val from = Cell(34, 30)
      val stop = end(from, MeleeReach.path(Reach.Long, ws, ws, from, t, 3000))
      assertTrue(stop.exists(c => c != Cell(32, 30) && MeleeReach.canStrike(Reach.Long, c, t, ws)))
    },
    test("中间那格站着怪（走不进去但地形能走）：照样停在隔一格出刀") {
      val busy = (c: Cell) => open(c) && c != Cell(31, 30)
      val from = Cell(34, 30)
      // 只能停在正前方那条线上的话（上下两条斜线也堵上），就停在怪后面隔一格
      val only = (c: Cell) => busy(c) && !Set(Cell(32, 28), Cell(32, 32), Cell(31, 29), Cell(31, 31), Cell(32, 29), Cell(32, 31)).contains(c)
      assertTrue(end(from, MeleeReach.path(Reach.Long, only, open, from, t, 3000)).contains(Cell(32, 30)))
    },
    test("能出刀的格子全到不了：退回照旧走近") {
      // 目标四周 2 格全是墙，只剩外面
      val ring = for dx <- -2 to 2; dy <- -2 to 2 if (dx, dy) != (0, 0) yield Cell(30 + dx, 30 + dy)
      val ws   = walls(ring*)
      val from = Cell(36, 30)
      assertTrue(MeleeReach.path(Reach.Long, ws, ws, from, t, 3000).exists(_.nonEmpty))
    }
  )
