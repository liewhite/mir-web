package mir.terrain

import mir.core.{Cell, Dir}

import scala.collection.mutable

/** 地形：哪些格能走、哪些格挡魔法和弓箭。不看谁站在上面（占位归世界管）。 */
trait Terrain:
  def width: Int
  def height: Int
  def walkable(c: Cell): Boolean
  def flyable(c: Cell): Boolean

  final def inside(c: Cell): Boolean = c.x >= 0 && c.y >= 0 && c.x < width && c.y < height

object Terrain:
  /** 按格子标记建：1 = 不可走，2 = 不可飞（行优先 i = y*w + x）。 */
  def fromFlags(width: Int, height: Int, flags: Array[Byte]): Terrain =
    require(flags.length == width * height, s"地形标记数 ${flags.length} ≠ $width×$height")
    Grid(width, height, flags.clone())

  /** 全部可走的空地（测试用）。 */
  def open(width: Int, height: Int): Terrain = Grid(width, height, new Array[Byte](width * height))

  /**
   * 解析 assets/map/<asset>.bin（tools/build_assets.py write_map_bin）：
   * "M176"、宽、高（u16 小端），然后 w×h 字节的格子标记，后面是绘制用的数据（这里不用）。
   */
  def parseBin(bytes: Array[Byte]): Either[String, Terrain] =
    def u16(at: Int): Int = (bytes(at) & 0xff) | ((bytes(at + 1) & 0xff) << 8)
    if bytes.length < 8 || new String(bytes.take(4), "US-ASCII") != "M176" then Left("地图文件格式不对（开头不是 M176）")
    else
      val w = u16(4)
      val h = u16(6)
      if bytes.length < 8 + w * h then Left(s"地图文件太短：$w×$h 需要至少 ${8 + w * h} 字节，只有 ${bytes.length}")
      else Right(Grid(w, h, bytes.slice(8, 8 + w * h)))

  private final class Grid(val width: Int, val height: Int, flags: Array[Byte]) extends Terrain:
    private def flag(c: Cell): Int = flags(c.y * width + c.x) & 0xff
    def walkable(c: Cell): Boolean = inside(c) && (flag(c) & 1) == 0
    def flyable(c: Cell): Boolean  = inside(c) && (flag(c) & 2) == 0

/** 八方向 A* 寻路（怪物追人绕障碍、玩家自动走到物品或目标旁）。 */
object Path:
  /**
   * 从 from 到 to 的方向序列；到不了就走到最接近的位置；一步也走不了为 None。
   * adjacent：走到 to 旁边一格就停（to 本身被占也行）；已经站在 to 上（人穿怪站到怪身上，#1240）就挑旁边一格走开。
   * maxNodes 限制搜索量。
   */
  def find(canEnter: Cell => Boolean, from: Cell, to: Cell, maxNodes: Int, adjacent: Boolean): Option[List[Dir]] =
    final case class Node(cell: Cell, g: Int, f: Int, parent: Option[Node])
    def h(c: Cell): Int = c.chebyshev(to)
    if from == to && !adjacent then Some(Nil)
    else
      val open    = mutable.PriorityQueue.empty[Node](using Ordering.by[Node, Int](n => -n.f))
      val best    = mutable.HashMap(from -> 0)
      val start   = Node(from, 0, h(from), None)
      var closest = start
      var visited = 0
      var found   = Option.empty[Node]
      open.enqueue(start)
      while found.isEmpty && open.nonEmpty && visited < maxNodes do
        val cur = open.dequeue()
        visited += 1
        val d = h(cur.cell)
        if d == (if adjacent then 1 else 0) then found = Some(cur)
        else
          if d < h(closest.cell) then closest = cur
          for dir <- Dir.values do
            val n      = cur.cell.step(dir)
            val isGoal = n == to
            val ok     = canEnter(n) && !(adjacent && isGoal)
            val g      = cur.g + 1
            if ok && best.get(n).forall(_ > g) then
              best(n) = g
              open.enqueue(Node(n, g, g + h(n), Some(cur)))
      def unwind(n: Node): List[Dir] =
        @annotation.tailrec
        def go(cur: Node, acc: List[Dir]): List[Dir] = cur.parent match
          case Some(p) => go(p, p.cell.dirTo(cur.cell) :: acc)
          case None    => acc
        go(n, Nil)
      found.orElse(closest.parent.map(_ => closest)).map(unwind)
