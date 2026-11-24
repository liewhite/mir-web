package mir.rules

import mir.core.{Cell, EnumCodec, MapId}
import zio.json.JsonCodec

/**
 * 名字颜色代表的 PK 身份（#1225，原版 GetNamecolor / GetCharColor）：
 *  - Clean：清白；
 *  - Brawler：灰名，刚动手打了清白的人（原版 PKFlag，FlagMs 内），被他打的人还手不算犯罪，杀了他不涨 PK 值；
 *  - Yellow：黄名，PK 值 100–199；
 *  - Red：红名，PK 值 ≥ 200：守卫见了就砍，死了掉东西（见 Pk.deathDrop）。
 * 红名压过灰名，灰名压过黄名（原版 PKLevel < 2 时灰名才显示）。
 */
enum PkStanding:
  case Clean, Brawler, Yellow, Red

object PkStanding:
  given JsonCodec[PkStanding] = EnumCodec.byName(values, _.toString)

/**
 * PK 规则（#1225，照原版 M2Server ObjBase.pas / ObjPlay.pas 与默认配置 M2Share.g_Config）：
 *  - 杀死不是红名、也不是灰名的玩家：凶手 PK 值 +KillPoints（原版 IncPkPoint(100)，「你犯了谋杀罪」）；
 *    杀灰名的不加（「你受到正当规则保护」），杀红名的什么都不算；宝宝杀的算主人的；
 *  - 打清白的玩家（或他的宝宝）：自己变灰名 FlagMs（原版 SetPKFlag：双方都不是红名、挨打的人自己不是灰名）；
 *  - PK 值在线每 DecayMs 降 1（原版 DecPkPointTime 2 分钟）：杀一个人 200 分钟（3 小时 20 分）降回 0；
 *  - 竞技地图（MapFlag.Fight）里打人、杀人都不算，死了也不掉东西（原版 boFightZone）；
 *  - 安全区（SafeZone）里不能打人、也不能被人打（原版 IsProtectTarget）。
 * 不做的：监狱、红名复活回红名村（用户 11-24 定不做监狱）、杀人扣幸运（本服没有人物幸运值）。
 */
object Pk:
  val KillPoints: Int = 100
  val DecayMs: Long   = 2 * 60_000L
  val FlagMs: Long    = 60_000L
  /** 进图、传送后多久内不能打人也不挨人打（原版 IsProtectTarget：MapMoveTick 3 秒） */
  val ArrivalGraceMs: Long = 3_000L

  /** 原版 PKLevel = PK 值 div 100：1 黄名，2 及以上红名 */
  def level(points: Int): Int = points / PointsPerLevel
  val PointsPerLevel: Int = 100

  /** 到这一级的 PK 值（说明用） */
  def pointsFor(level: Int): Int = level * PointsPerLevel

  def isRed(points: Int): Boolean = level(points) >= 2

  def standing(points: Int, brawler: Boolean): PkStanding =
    if isRed(points) then PkStanding.Red
    else if brawler then PkStanding.Brawler
    else if level(points) == 1 then PkStanding.Yellow
    else PkStanding.Clean

  /** 杀了这个人算不算谋杀（他不是红名，也不是灰名） */
  def isMurder(victimPoints: Int, victimBrawler: Boolean): Boolean = !isRed(victimPoints) && !victimBrawler

  /** 挨打的人这一下会不会让打他的人变灰（原版 SetPKFlag：双方都不是红名、挨打的人自己不是灰名） */
  def brands(victimPoints: Int, victimBrawler: Boolean, attackerPoints: Int): Boolean =
    !isRed(victimPoints) && !isRed(attackerPoints) && !victimBrawler

  /**
   * 死了掉什么（#1225 用户定：红名死了掉东西，照原版 DieRedScatterBagAll / DieRedDropUseItemRate；不是红名的不掉）：
   * 包裹（含快捷栏）全掉，身上每件 1/wornOdds 的机会掉（PK 值 ≥ 300 是 1/15，200–299 是 1/30）；金币不掉。
   */
  final case class DeathDrop(wholeBag: Boolean, wornOdds: Int)

  def deathDrop(points: Int): Option[DeathDrop] =
    Option.when(isRed(points))(DeathDrop(wholeBag = true, wornOdds = if level(points) > 2 then 15 else 30))

/**
 * 安全区（原版 InSafeZone：地图带 SAFE 标记，或离 StartPoint.txt 里的出生点横竖都不超过 Radius 格）。
 * 1.76 数据表的地图没有带 SAFE 的；出生点照 Mir176 tools/build-classic-content.py 写的 StartPoint.txt。
 */
object SafeZone:
  val Radius: Int = 10

  /** 出生点：比奇省（银杏村、边界村、比奇城）、毒蛇山谷、盟重、封魔谷、苍月岛、白日门 */
  val Points: List[(MapId, Cell)] = List(
    MapId("0")  -> Cell(289, 618),
    MapId("0")  -> Cell(650, 631),
    MapId("0")  -> Cell(330, 266),
    MapId("2")  -> Cell(503, 483),
    MapId("3")  -> Cell(330, 330),
    MapId("4")  -> Cell(240, 200),
    MapId("5")  -> Cell(141, 337),
    MapId("11") -> Cell(178, 326)
  )

  def covers(map: MapId, c: Cell): Boolean =
    Points.exists((m, p) => m == map && math.abs(c.x - p.x) <= Radius && math.abs(c.y - p.y) <= Radius)
