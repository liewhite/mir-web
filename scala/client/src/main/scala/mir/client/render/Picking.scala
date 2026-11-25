package mir.client.render

import mir.core.ActorId

/**
 * 这一帧画出来的一个可点中的角色：身体图画在 (x, y)，大小 w×h，站在第 row 行；body 是取像素用的身体图。
 * 列表按画的先后排（后画的盖在上面）。
 */
final case class Hit[+B](id: ActorId, x: Double, y: Double, w: Int, h: Int, row: Int, body: B)

/**
 * 点中哪个角色（#1284），照原版 PlayScn.pas GetAttackFocusCharacter：自己不算，盖在上面的（行号大、同行后画的）先算。
 *  1. 按像素：鼠标下的身体图像素不透明，且上下左右 4 个点也不透明（Actor.pas CheckSelect），神兽大图的透明边不再抢走旁边人的点击；
 *  2. 都没点中再用宽松框：每个身体图只取正中最多 40×70 的一块；
 *  3. 还没有且 slop > 0（手指比鼠标粗）：取正中那块外 slop 以内最近的（本项目加的）。
 */
object Picking:
  val CoreW = 40
  val CoreH = 70

  /** solid(body, x, y)：身体图里 (x, y) 这点不透明；越界或读不到像素给 false。 */
  def pick[B](hits: Seq[Hit[B]], px: Double, py: Double, slop: Double, exclude: ActorId)(solid: (B, Int, Int) => Boolean): Option[ActorId] =
    val front = hits.zipWithIndex.filter(_._1.id != exclude).sortBy((h, i) => (-h.row, -i)).map(_._1)
    def byPixel = front.find { h =>
      val lx = math.floor(px - h.x).toInt
      val ly = math.floor(py - h.y).toInt
      List((0, 0), (-1, 0), (1, 0), (0, -1), (0, 1)).forall((dx, dy) => inside(h, lx + dx, ly + dy) && solid(h.body, lx + dx, ly + dy))
    }
    // 到正中那块的距离（0 是在里面）
    def coreDistance(h: Hit[B]): Double =
      val cx = math.max(0, (h.w - CoreW) / 2)
      val cy = math.max(0, (h.h - CoreH) / 2)
      val dx = math.max(math.max(h.x + cx - px, 0), px - (h.x + h.w - cx))
      val dy = math.max(math.max(h.y + cy - py, 0), py - (h.y + h.h - cy))
      math.hypot(dx, dy)
    def byCore = front.find(coreDistance(_) == 0)
    // 放宽也只从正中那块算起，不然大图的透明边又会抢点击
    def bySlop = Option.when(slop > 0)(front).flatMap(_.map(h => h -> coreDistance(h)).filter(_._2 <= slop).minByOption(_._2).map(_._1))
    byPixel.orElse(byCore).orElse(bySlop).map(_.id)

  private def inside(h: Hit[?], x: Int, y: Int): Boolean = x >= 0 && y >= 0 && x < h.w && y < h.h
