package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.{PkStanding, Rng}
import mir.terrain.Terrain
import mir.testkit.TestFiles
import zio.test.*

/** 查看别人装备（#1263）：视野里活着的别的玩家才行，只给穿戴中的，不给包裹。 */
object InspectSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private val testMap = MapDef(MapId("T"), "T", "测试", Set.empty, 60, 60)

  private final class Sim:
    val w = World(catalog, testMap, Terrain.open(60, 60), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0))

    def cmd(p: Player, c: GameCmd): Vector[ServerMsg] =
      w.handle(p.id, c)
      w.flush().messages.getOrElse(p.id, Vector.empty)

    def join(name: String, at: Cell, sex: Sex = Sex.Male): Player =
      val d = CharacterData.newbie(catalog, name, Job.Warrior, sex, UidSource.counter(name.hashCode.abs.toLong * 1000))
      w.player(w.join(d.copy(place = Place(w.mapId, at)))).get

  private def inspected(r: Vector[ServerMsg]): Option[InspectView] = r.collectFirst { case ServerMsg.Inspected(v) => v }
  private def refused(r: Vector[ServerMsg]): Boolean = r.exists { case ServerMsg.Refused(_) => true; case _ => false }

  def spec = suite("查看别人装备（#1263）")(
    test("视野里的玩家：回他的名字、性别、发型、PK 身份和穿戴中的全部装备，不含包裹和快捷栏") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(30 + World.ViewRange, 30), Sex.Female)
      val ring = s.w.newItem(ItemName("古铜戒指")).get
      b.worn(EquipSlot.RingL) = ring
      val v = inspected(s.cmd(a, GameCmd.Inspect(b.id)))
      assertTrue(
        v.map(_.name).contains("乙"),
        v.map(_.sex).contains(Sex.Female),
        v.map(_.hair).contains(b.hair),
        v.map(_.pk).contains(PkStanding.Clean),
        v.map(_.worn).contains(b.worn.toMap),
        v.exists(_.worn.get(EquipSlot.RingL).contains(ring)),
        v.exists(_.worn.values.forall(it => !b.belt.flatten.contains(it)))
      )
    },
    test("视野外、不存在、自己都不行，回 Refused") {
      val s   = Sim()
      val a   = s.join("甲", Cell(10, 10))
      val far = s.join("丙", Cell(10 + World.ViewRange + 1, 10))
      val r1  = s.cmd(a, GameCmd.Inspect(far.id))
      val r2  = s.cmd(a, GameCmd.Inspect(ActorId(999_999)))
      val r3  = s.cmd(a, GameCmd.Inspect(a.id))
      assertTrue(
        refused(r1), inspected(r1).isEmpty,
        refused(r2), inspected(r2).isEmpty,
        refused(r3), inspected(r3).isEmpty
      )
    },
    test("死了的玩家不行") {
      val s = Sim()
      val a = s.join("甲", Cell(30, 30))
      val b = s.join("乙", Cell(31, 30))
      b.hp = 1
      s.w.vitals.strike(None, b, Blow(Defence.Pierce, 10))
      val r = s.cmd(a, GameCmd.Inspect(b.id))
      assertTrue(b.dead, refused(r), inspected(r).isEmpty)
    }
  )
