package mir.server.store

import mir.core.*
import mir.world.{Carried, FrozenMap}
import mir.world.FrozenCodecs.given
import zio.json.*

/**
 * 停服快照（#1279）：正常停服时全服不进角色存档的东西，开服时接上。
 * - maps：开着的每张图（活着的野怪、地上的东西、刷怪点补怪时刻）；
 * - carried：停服时在线角色身上带的（宝宝、限时状态、没回完的药、PK 降值计时），按角色名，重连进游戏时领回；
 * - parties：小组（第一个是队长）。
 */
final case class Frozen(
    maps: Map[MapId, FrozenMap] = Map.empty,
    carried: Map[String, Carried] = Map.empty,
    parties: Vector[Vector[String]] = Vector.empty
):
  def isEmpty: Boolean = maps.isEmpty && carried.isEmpty && parties.isEmpty

/** 快照存哪儿：只在停服时 put 一次、开服时 take 一次（取完就没了，崩溃后开服不会拿到旧快照）。 */
trait Freezer:
  def put(f: Frozen): Unit
  def take(): Frozen

object Freezer:
  /** 不存（测试、命令行） */
  val Off: Freezer = new Freezer:
    def put(f: Frozen): Unit = ()
    def take(): Frozen       = Frozen()

  /** 存在内存里（测试「停服→开服」往返用） */
  def inMemory(): Freezer = new Freezer:
    private var held = Frozen()
    def put(f: Frozen): Unit = held = f
    def take(): Frozen =
      val f = held
      held = Frozen()
      f

/**
 * 快照存进数据库 snapshot 表，每一部分一行（一张图、一个角色、全部小组），各自是 JSON、各自解码：
 * 新版本改了物品、怪的结构而解不开的那一行只打日志跳过（那张图按新开图处理、那个人不带宝宝），不影响开服。
 * format 是快照格式的版本，和现在的不同的整行跳过。
 */
final class Snapshots(db: Db) extends Freezer:
  import Snapshots.*

  def put(f: Frozen): Unit =
    val rows =
      f.maps.toVector.map((id, m) => s"map:${id.value}" -> m.toJson) ++
        f.carried.toVector.map((name, c) => s"carried:$name" -> c.toJson) ++
        Option.when(f.parties.nonEmpty)("parties" -> f.parties.toJson)
    val now = System.currentTimeMillis()
    db.transaction { c =>
      Db.update(c, "DELETE FROM snapshot")
      rows.foreach((part, json) => Db.update(c, "INSERT INTO snapshot(part, format, data, saved_at) VALUES (?, ?, ?, ?)", part, Format, json, now))
    }

  def take(): Frozen =
    val rows = db.transaction { c =>
      val all = Db.query(c, "SELECT part, format, data FROM snapshot")(rs => (rs.getString(1), rs.getInt(2), rs.getString(3)))
      Db.update(c, "DELETE FROM snapshot")
      all
    }
    rows.foldLeft(Frozen()) { case (f, (part, format, json)) =>
      def skip(why: String): Frozen =
        System.err.println(s"停服快照 $part 跳过：$why")
        f
      def decoded[A: JsonDecoder](add: A => Frozen): Frozen = json.fromJson[A].fold(e => skip(s"解不开（$e）"), add)
      if format != Format then skip(s"格式版本 $format，现在是 $Format")
      else part.split(":", 2) match
        case Array("map", id)       => decoded[FrozenMap](m => f.copy(maps = f.maps + (MapId(id) -> m)))
        case Array("carried", name) => decoded[Carried](c => f.copy(carried = f.carried + (name -> c)))
        case Array("parties")       => decoded[Vector[Vector[String]]](p => f.copy(parties = p))
        case _                      => skip("不认识这一部分")
    }

object Snapshots:
  /** 快照格式版本：快照的结构改到旧快照解不开或解错时加一 */
  val Format = 1
