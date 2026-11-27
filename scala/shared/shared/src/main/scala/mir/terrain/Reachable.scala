package mir.terrain

import mir.core.{Cell, Dir}

import scala.collection.mutable

/**
 * 一张图上从主区域走得到的格子（#1369）。原版地图文件在可见区域外还有零星「可走」的小块，跟主区域不连通
 * （如圣域 x=0~1、y=169~199）；刷怪、随机传送落进去的怪出不来、玩家打不到，会一直占着名额。
 *
 * 按八方向连通给可走格分块，算得到的 = 最大的一块 + 门（传送点）所在或紧挨着的块 + 别的图传进来的落点所在或紧挨着的块，
 * 靠门连起来的分区（如迷宫、两片只靠传送点相通的区域）不会被误伤。
 */
final class Reachable private (width: Int, height: Int, cells: mutable.BitSet):
  def apply(c: Cell): Boolean =
    c.x >= 0 && c.y >= 0 && c.x < width && c.y < height && cells.contains(c.y * width + c.x)

  /** 走得到的格数（测试、日志用） */
  def size: Int = cells.size

object Reachable:
  /** 全部可走格都算走得到（测试用的空地、没有地形的场合）。 */
  def all(t: Terrain): Reachable = of(t, Nil, keepAll = true)

  /** anchors：门和落点（门那格本身可能不可走，就看它周围一圈）。 */
  def of(t: Terrain, anchors: Iterable[Cell]): Reachable = of(t, anchors, keepAll = false)

  private def of(t: Terrain, anchors: Iterable[Cell], keepAll: Boolean): Reachable =
    val w      = t.width
    val label  = new Array[Int](w * t.height) // 0 = 不可走或还没分块，k ≥ 1 = 第 k 块
    val sizes  = mutable.ArrayBuffer(0)
    val stack  = mutable.Stack.empty[Int]
    def cellOf(i: Int): Cell = Cell(i % w, i / w)
    for i <- label.indices if label(i) == 0 && t.walkable(cellOf(i)) do
      val k = sizes.size
      var n = 0
      label(i) = k
      stack.push(i)
      while stack.nonEmpty do
        val cur = cellOf(stack.pop())
        n += 1
        Dir.values.foreach { d =>
          val nb = cur.step(d)
          if t.walkable(nb) && label(nb.y * w + nb.x) == 0 then
            label(nb.y * w + nb.x) = k
            stack.push(nb.y * w + nb.x)
        }
      sizes += n
    val biggest = if sizes.size > 1 then Set(sizes.indices.drop(1).maxBy(sizes)) else Set.empty[Int]
    val anchored = anchors.flatMap(a => a +: Dir.values.toSeq.map(d => a.step(d))).collect {
      case c if t.walkable(c) => label(c.y * w + c.x)
    }.toSet
    val keep  = biggest ++ anchored
    val cells = mutable.BitSet.empty
    for i <- label.indices if label(i) != 0 && (keepAll || keep(label(i))) do cells += i
    Reachable(w, t.height, cells)
