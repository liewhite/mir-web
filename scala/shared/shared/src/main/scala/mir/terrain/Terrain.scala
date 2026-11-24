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
  private final case class Node(cell: Cell, g: Int, f: Int, parent: Option[Node])

  /** 一次搜索的结果：到了的终点（没到为 None），和搜过的离终点最近的一格。 */
  private final case class Search(found: Option[Node], closest: Node)

  /**
   * 从 from 到 to 的方向序列；到不了就走到最接近的位置；一步也走不了为 None。
   * adjacent：走到 to 旁边一格就停（to 本身被占也行）；已经站在 to 上（人穿怪站到怪身上，#1240）就挑旁边一格走开。
   * maxNodes 限制搜索量。
   */
  def find(canEnter: Cell => Boolean, from: Cell, to: Cell, maxNodes: Int, adjacent: Boolean): Option[List[Dir]] =
    if from == to && !adjacent then Some(Nil)
    else
      val goalDist = if adjacent then 1 else 0
      val s = search(c => canEnter(c) && !(adjacent && c == to), from, _.chebyshev(to) == goalDist, _.chebyshev(to), maxNodes)
      s.found.orElse(s.closest.parent.map(_ => s.closest)).map(unwind)

  /**
   * 走到第一个满足 isGoal 的格子：已经满足为 Some(Nil)，搜不到为 None（不像 find 那样退而走近）。
   * h 是到最近终点的步数下界（要不高估，才找得到最短路）。
   */
  def findAny(canEnter: Cell => Boolean, from: Cell, isGoal: Cell => Boolean, h: Cell => Int, maxNodes: Int): Option[List[Dir]] =
    search(canEnter, from, isGoal, h, maxNodes).found.map(unwind)

  private def search(canEnter: Cell => Boolean, from: Cell, isGoal: Cell => Boolean, h: Cell => Int, maxNodes: Int): Search =
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
      if isGoal(cur.cell) then found = Some(cur)
      else
        if h(cur.cell) < h(closest.cell) then closest = cur
        for dir <- Dir.values do
          val n = cur.cell.step(dir)
          val g = cur.g + 1
          if canEnter(n) && best.get(n).forall(_ > g) then
            best(n) = g
            open.enqueue(Node(n, g, g + h(n), Some(cur)))
    Search(found, closest)

  private def unwind(n: Node): List[Dir] =
    @annotation.tailrec
    def go(cur: Node, acc: List[Dir]): List[Dir] = cur.parent match
      case Some(p) => go(p, p.cell.dirTo(cur.cell) :: acc)
      case None    => acc
    go(n, Nil)

/** 近身出刀能打多远：只打贴身的，或开着刺杀剑术时贴身和隔一格直线都行（#1264）。 */
enum Reach:
  case Adjacent
  case Long

object Reach:
  /** 刺杀剑术学了且开着才算 Long（原版客户端 g_boCanLongHit + TargetInSwordLongAttackRange）。 */
  def of(longHitOn: Boolean, knowsLongHit: Boolean): Reach = if longHitOn && knowsLongHit then Long else Adjacent

/**
 * 近身打一个目标时的站位（服务端「按住攻击」和客户端攻击键共用，#1264）：
 * 站的地方能出刀就直接打，不为了刺杀退开或绕位；不能就走到最近的能出刀的格子停下。
 */
object MeleeReach:
  /**
   * 站在 from 能不能出刀打到 target。隔一格要在八方向直线上，且中间那格是能走的地面——
   * 中间站着人或怪也行（这一刀前面那格也挨打），隔着墙不捅（原版刺杀不看墙，这里自动站位不站墙后）。
   */
  def canStrike(reach: Reach, from: Cell, target: Cell, ground: Cell => Boolean): Boolean =
    from.chebyshev(target) match
      case 1 => true
      case 2 => reach == Reach.Long && inLine(from, target) && ground(from.step(from.dirTo(target)))
      case _ => false

  /**
   * 去打 target 的路：Adjacent 同 Path.find(adjacent = true)；Long 走到最近的「贴身或隔一格直线」的格子，
   * 那些格子都到不了时退回 Path.find 走近。canEnter 是能走进的格子，ground 是地形能走。
   */
  def path(reach: Reach, canEnter: Cell => Boolean, ground: Cell => Boolean, from: Cell, target: Cell, maxNodes: Int): Option[List[Dir]] =
    def close = Path.find(canEnter, from, target, maxNodes, adjacent = true)
    reach match
      case Reach.Adjacent => close
      case Reach.Long =>
        // 终点都在目标 2 格以内，离目标的距离减 2 不会高估步数
        Path.findAny(c => canEnter(c) && c != target, from, canStrike(reach, _, target, ground), c => math.max(0, c.chebyshev(target) - 2), maxNodes)
          .filter(_.nonEmpty)
          .orElse(close)

  private def inLine(a: Cell, b: Cell): Boolean =
    val dx = b.x - a.x
    val dy = b.y - a.y
    dx == 0 || dy == 0 || math.abs(dx) == math.abs(dy)
