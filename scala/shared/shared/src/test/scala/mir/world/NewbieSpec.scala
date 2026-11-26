package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.rules.{ItemEffects, ItemKind, PassiveSkills, Requirement, Rng, Rules}
import mir.terrain.Terrain
import mir.testkit.TestFiles
import zio.test.*

/** 新建角色（#1310）：直接 10 级、经验 0、满血满蓝，八个部位都穿着自己穿得上的基础装。 */
object NewbieSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private val testMap = MapDef(MapId("T"), "T", "测试", Set.empty, 30, 30)

  private val kinds = for j <- Job.values.toList; s <- Sex.values.toList yield (j, s)

  def spec = suite("新建角色（#1310）")(
    test("每个职业、性别：10 级、经验 0，八个部位齐全，每件都在数据表里、放得进那个部位、穿得上") {
      val problems = kinds.flatMap { (job, sex) =>
        val d       = CharacterData.newbie(catalog, "甲", job, sex, UidSource.counter(1))
        val effects = d.worn.values.flatMap(it => catalog.item(it.name).map(ItemEffects.of(_, it)))
        val stats   = Rules.computeStats(job, d.level, effects, PassiveSkills())
        // 1.76 的八个部位；腰带、靴子（#1311）是 1.80 加的，新人装里没有
        val slots   = EquipSlot.values.toList.filterNot(Set(EquipSlot.Belt, EquipSlot.Boots, EquipSlot.Medal)).filterNot(d.worn.contains).map(s => s"$job/$sex 缺 ${s.label}")
        val items = d.worn.toList.flatMap { (slot, it) =>
          catalog.item(it.name) match
            case None => List(s"$job/$sex ${it.name.value} 不在数据表")
            case Some(defn) =>
              Option.unless(ItemKind.slots(ItemKind.of(defn)).contains(slot))(s"$job/$sex ${it.name.value} 不能放 ${slot.label}").toList ++
                Requirement.refusal(defn, d.level, stats, sex).map(r => s"$job/$sex ${it.name.value}：$r")
        }
        val vitals = Option.unless(d.hp.value == stats.maxHp && d.mp.value == stats.maxMp)(s"$job/$sex 不是满血满蓝").toList
        slots ++ items ++ vitals
      }
      val d = CharacterData.newbie(catalog, "甲", Job.Warrior, Sex.Male, UidSource.counter(1))
      assertTrue(problems.isEmpty, d.level == 10, d.exp == Exp.zero)
    },
    test("进图后血蓝和世界重算的上限一致") {
      val w = World(catalog, testMap, Terrain.open(30, 30), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0))
      val ok = kinds.zipWithIndex.forall { case ((job, sex), i) =>
        val d = CharacterData.newbie(catalog, s"甲$i", job, sex, UidSource.counter(1000L * (i + 1)))
        val p = w.player(w.join(d.copy(place = Place(w.mapId, Cell(5 + i, 5))))).get
        p.hp == p.stats.maxHp && p.mp == p.stats.maxMp && p.level == 10
      }
      assertTrue(ok)
    }
  )
