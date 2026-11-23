package mir.world

import mir.data.Catalog
import mir.rules.{Skill, SkillUse}
import mir.testkit.TestFiles
import mir.world.skills.{CastFx, SkillHandlers}
import zio.test.*

/** 技能注册表（#1171）：数据表里的技能都有对应的 Skill 和处理器，编号、职业对得上。 */
object SkillRegistrySpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  /** 数据表里有、还没做的技能（编号 名字）。做了一个就从这里删掉。 */
  private val NotYetDone: List[String] = Nil

  def spec = suite("SkillRegistry")(
    test("数据表里每个技能都做了（没做的列在 NotYetDone）") {
      val missing = catalog.data.skills.filter(d => Skill.of(d.id).isEmpty).map(d => s"${d.id.value} ${d.name}")
      assertTrue(missing == NotYetDone)
    },
    test("每个 Skill 在数据表里都有，编号不重") {
      val unknown = Skill.values.toList.filter(s => catalog.skill(s.id).isEmpty)
      assertTrue(unknown.isEmpty, Skill.values.map(_.code).distinct.length == Skill.values.length)
    },
    test("起手特效照原版 EffectBase（按技能表 Effect 编号）") {
      // MagicEff.pas EffectBase：Effect 编号 → 起始帧；疾光电影、灵魂火符、幽灵盾、神圣战甲术、集体隐身术、召唤神兽为 940（没有）
      val base = Map(
        1 -> ("magic", 0), 2 -> ("magic", 200), 3 -> ("magic", 400), 4 -> ("magic", 600), 6 -> ("magic", 900), 7 -> ("magic", 920),
        9 -> ("magic2", 20), 14 -> ("magic", 1380), 15 -> ("magic", 1500), 16 -> ("magic", 1520), 18 -> ("magic", 1560),
        19 -> ("magic", 1590), 20 -> ("magic", 1620), 21 -> ("magic", 1650), 22 -> ("magic", 1680), 26 -> ("magic", 3960),
        27 -> ("magic", 1790), 29 -> ("magic", 3880), 30 -> ("magic", 3920), 31 -> ("magic", 3840)
      )
      val wrong = Skill.values.toList.filter(_.use == SkillUse.Spell).flatMap { s =>
        val want = catalog.skill(s.id).flatMap(d => base.get(d.effect))
        val got  = SkillHandlers.of(s).castFx.map { case CastFx(lib, start, _, _) => (lib, start) }
        Option.when(want != got)(s"$s 期望 $want 实际 $got")
      }
      assertTrue(wrong.isEmpty)
    }
  )
