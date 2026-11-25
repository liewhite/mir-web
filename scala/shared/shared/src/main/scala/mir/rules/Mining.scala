package mir.rules

import mir.core.ItemName
import mir.data.ItemDef

/**
 * 挖矿（#1286，照原版 ObjPlay.pas TPlayObject.PileStones / MakeMine、Event.pas TStoneMineEvent，参数是 M2Share.pas 的默认配置）：
 * 带 MINE 标记的地图（1.76 数据里只有比奇的「废矿入口」）上，拿着鹤嘴锄朝挡路的格子（矿壁）攻击就是挖。
 * - 每个矿壁格有一条矿脉，第一次挖时定下还剩几下能挖（Random(VeinMax)），每挖一下少一下；
 *   挖空了，离上次补满过了 RefillMs 再挖就补成这一格固定的补充量（Random(RefillMax)，这一下不出东西）。
 * - 有剩的时候每挖一下：1/ChipOdds 敲下碎石（武器磨 Random(15)+5 点持久），敲下就出一块矿石（包裹满了不出）。
 * 本服加快（用户 11-25 定「提高挖矿效率，玩家不要在挖矿耗费太多时间」）：原版敲下 1/4、敲下时再 1/12 出矿（每下 1/48），
 *   这里每下 1/ChipOdds 出矿；出哪种矿、纯度、矿脉仍照原版。
 */
object Mining:
  /** 鹤嘴锄：武器外观 Shape 19 */
  val PickaxeShape = 19
  val VeinMax      = 200
  val RefillMax    = 80
  val RefillMs     = 10 * 60_000L
  val ChipOdds     = 2

  def isPickaxe(d: ItemDef): Boolean = ItemKind.slots(ItemKind.of(d)).contains(mir.model.EquipSlot.Weapon) && d.shape == PickaxeShape

  /** 敲下碎石时武器磨多少（原始持久单位，1000 = 显示的 1 点） */
  def wear()(using rng: Rng): Int = rng.random(15) + 5

  /** 几种矿石（数据表里的名字）和 MakeMine 里 Random(120) 落在哪段出哪种：其余（0、57–119）出铜矿 */
  enum Ore(val item: ItemName, val from: Int, val to: Int):
    case Gold      extends Ore(ItemName("金矿"), 1, 2)
    case Silver    extends Ore(ItemName("银矿"), 3, 20)
    case Iron      extends Ore(ItemName("铁矿"), 21, 45)
    case BlackIron extends Ore(ItemName("黑铁矿石"), 46, 56)
    case Copper    extends Ore(ItemName("铜矿"), -1, -1)

  val OreRoll = 120

  /** 出哪种矿石 */
  def ore()(using rng: Rng): Ore =
    val n = rng.random(OreRoll)
    Ore.values.find(o => n >= o.from && n <= o.to).getOrElse(Ore.Copper)

  /** 矿石的纯度（存在持久里，原始单位；显示 ÷1000）：Random(13000)+3000，1/20 机会再加 Random(10000)。 */
  def purity()(using rng: Rng): Int =
    val base = rng.random(13000) + 3000
    if rng.random(20) == 0 then base + rng.random(10000) else base

  /** 显示的纯度（原版 Round(Dura/1000)，银行家舍入） */
  def shownPurity(dura: Int): Int = math.rint(dura / 1000.0).toInt

  /** 一条矿脉：还能挖几下、挖空后补成多少、上次补满的时刻。 */
  final case class Vein(left: Int, refill: Int, refilledAt: Long)

  object Vein:
    def fresh(now: Long)(using rng: Rng): Vein = Vein(rng.random(VeinMax), rng.random(RefillMax), now)

  /** 挖一下的结果 */
  enum Strike:
    /** 没敲下东西 */
    case Nothing
    /** 这处矿脉挖空了（还没到补充的时候） */
    case Exhausted
    /** 敲下碎石；ore：同时出一块矿石（哪种、纯度再由 ore()、purity() 定） */
    case Chip(ore: Boolean)

  /** 挖矿脉一下：返回挖过后的矿脉和结果。roomForOre：包裹还放得下矿石。 */
  def strike(v: Vein, now: Long, roomForOre: Boolean)(using rng: Rng): (Vein, Strike) =
    if v.left <= 0 then
      if now - v.refilledAt > RefillMs then (v.copy(left = v.refill, refilledAt = now), Strike.Nothing)
      else (v, Strike.Exhausted)
    else
      val dug = v.copy(left = v.left - 1)
      if rng.random(ChipOdds) != 0 then (dug, Strike.Nothing)
      else
        (dug, Strike.Chip(roomForOre))
