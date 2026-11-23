package mir.core

import zio.json.*

/** 地图上的一格。 */
final case class Cell(x: Int, y: Int):
  def step(d: Dir, n: Int = 1): Cell = Cell(x + d.dx * n, y + d.dy * n)

  /** 切比雪夫距离：八方向走几步。 */
  def chebyshev(o: Cell): Int = math.max(math.abs(x - o.x), math.abs(y - o.y))

  /** 朝 o 的方向（同一格时为下）。 */
  def dirTo(o: Cell): Dir =
    val dx = Integer.signum(o.x - x)
    val dy = Integer.signum(o.y - y)
    Dir.values.find(d => d.dx == dx && d.dy == dy).getOrElse(Dir.Down)

  /** 16 方向（飞行物、电光的朝向）：按屏幕像素角度（格子 48x32），0 上，顺时针。 */
  def dir16(o: Cell): Int =
    val a = math.atan2((o.x - x) * 48.0, -(o.y - y) * 32.0)
    Math.floorMod(math.round(a / (math.Pi / 8)).toInt, 16)

object Cell:
  given JsonCodec[Cell] = DeriveJsonCodec.gen

/** 八方向，编号同原版：0 上，顺时针到 7 左上。ordinal 就是原版编号。 */
enum Dir(val dx: Int, val dy: Int):
  case Up        extends Dir(0, -1)
  case UpRight   extends Dir(1, -1)
  case Right     extends Dir(1, 0)
  case DownRight extends Dir(1, 1)
  case Down      extends Dir(0, 1)
  case DownLeft  extends Dir(-1, 1)
  case Left      extends Dir(-1, 0)
  case UpLeft    extends Dir(-1, -1)

  def code: Int      = ordinal
  def opposite: Dir  = Dir.fromCode(ordinal + 4)
  def turn(n: Int): Dir = Dir.fromCode(ordinal + n)

object Dir:
  /** 原版编号（取模 8，可以传负数）。 */
  def fromCode(n: Int): Dir = Dir.fromOrdinal(Math.floorMod(n, 8))

  /** 按屏幕向量取方向（px 向右、py 向下为正）。 */
  def fromVector(px: Double, py: Double): Dir =
    val a = math.atan2(px, -py)
    fromCode(math.round(a / (math.Pi / 4)).toInt)

  given JsonCodec[Dir] = JsonCodec.int.transformOrFail(
    n => if n >= 0 && n < 8 then scala.util.Right(Dir.fromOrdinal(n)) else scala.util.Left(s"方向越界：$n"),
    _.code
  )
