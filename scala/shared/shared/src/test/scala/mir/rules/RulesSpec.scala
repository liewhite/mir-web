package mir.rules

import mir.core.*
import mir.data.*
import mir.model.*
import mir.testkit.TestFiles
import zio.test.*

object RulesSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  def spec = suite("规则")(
    suite("方向与格子")(
      test("方向编号同原版：0 上顺时针，相反方向、取模") {
        assertTrue(
          Dir.values.map(_.code).toList == (0 to 7).toList,
          Dir.Up.opposite == Dir.Down,
          Dir.UpLeft.opposite == Dir.DownRight,
          Dir.fromCode(-1) == Dir.UpLeft,
          Dir.fromCode(9) == Dir.UpRight,
          Dir.Up.turn(2) == Dir.Right
        )
      },
      test("走一步、朝向、距离") {
        val c = Cell(10, 10)
        assertTrue(
          c.step(Dir.UpRight) == Cell(11, 9),
          c.step(Dir.Left, 2) == Cell(8, 10),
          c.dirTo(Cell(5, 15)) == Dir.DownLeft,
          c.dirTo(c) == Dir.Down,
          c.chebyshev(Cell(13, 8)) == 3,
          Dir.values.forall(d => c.dirTo(c.step(d, 3)) == d),
          c.dir16(Cell(10, 5)) == 0,
          c.dir16(Cell(15, 10)) == 4,
          Dir.fromVector(1, 0) == Dir.Right,
          Dir.fromVector(0, -1) == Dir.Up
        )
      }
    ),
    suite("随机数")(
      test("Random(n) 落在 0..n-1，n ≤ 0 为 0；同种子同序列") {
        val a  = Rng.seeded(7)
        val b  = Rng.seeded(7)
        val xs = List.fill(200)(a.random(6))
        val ys = List.fill(200)(b.random(6))
        assertTrue(xs == ys, xs.forall(x => x >= 0 && x < 6), xs.toSet.size == 6, a.random(0) == 0, a.random(-3) == 0)
      },
      test("固定序列按 n 取模") {
        val r = Rng.sequence(5, 1)
        assertTrue(r.random(3) == 2, r.random(3) == 1, r.random(10) == 5)
      }
    ),
    suite("带随机的公式")(
      test("命中：准确 < Random(敏捷) 打空") {
        assertTrue(
          Rules.hits(5, 15)(using Rng.sequence(5)),
          !Rules.hits(5, 15)(using Rng.sequence(6)),
          Rules.hitChance(5, 15) == 0.4,
          Rules.hitChance(20, 15) == 1.0,
          Rules.hitChance(3, 0) == 1.0
        )
      },
      test("防御抵消：威力 - 区间取值，不低于 0") {
        given Rng = Rng.sequence(2)
        assertTrue(Rules.roll(Span(3, 7)) == 5, Rules.afterArmor(10, Span(3, 7)) == 5, Rules.afterArmor(2, Span(3, 7)) == 0)
      },
      test("技能威力（MagicManager.GetPower / GetPower13）") {
        given Rng = Rng.sequence(1)
        // 火球术 power 8、defPower 2-2、3 级：round(8/4*4)=8，+2+Random(0)=10
        // GetPower13(30, 1, 2-4)：d10=10、d18=20，round(20/4*2 + 10 + 2 + Random(2)=1) = 23
        assertTrue(Rules.skillPower(8, 3, Span(2, 2)) == 10, Rules.skillPower13(30, 1, Span(2, 4)) == 23)
      },
      test("GetRPow、GetAttackPower") {
        given Rng = Rng.sequence(3)
        assertTrue(Rules.rpow(Span(5, 5)) == 5, Rules.rpow(Span(5, 10)) == 8, Rules.attackPower(10, 4) == 13, Rules.attackPower(10, -2) == 10)
      }
    ),
    suite("物品")(
      test("代表物品的用途与装备位") {
        def kind(n: String) = ItemKind.of(catalog.item(ItemName(n)).get)
        assertTrue(
          kind("金创药(小量)") == ItemKind.Potion(20, 0, false),
          kind("木剑") == ItemKind.Equip(List(EquipSlot.Weapon)),
          kind("回城卷") == ItemKind.Scroll(ScrollEffect.Town),
          kind("火球术") == ItemKind.Book(Some(Job.Wizard), 7),
          ItemKind.slots(kind("护身符")) == List(EquipSlot.BraceletL)
        )
      },
      test("穿戴要求按人物当前值判断") {
        val stats = Rules.computeStats(Job.Warrior, 10, Nil, PassiveSkills())
        val dc    = Requirement.Dc(5)
        assertTrue(Requirement.current(dc, 10, stats) == stats.dc.hi, Requirement.current(Requirement.Level(3), 10, stats) == 10)
      }
    ),
    suite("经验与倍率")(
      test("打怪经验 ×100，复活过的减半后取整") {
        assertTrue(Rates.killExp(5, 1.0) == Exp(500L), Rates.killExp(5, 0.5) == Exp(200L))
      },
      test("经验够了连升，扣掉每级要的；满级不再升") {
        val need1 = catalog.expToNext(1).get.value
        val need2 = catalog.expToNext(2).get.value
        val p     = Leveling.gain(catalog, Progress(1, Exp(0L)), Exp(need1 + need2 + 7))
        val top   = Leveling.gain(catalog, Progress(48, Exp(0L)), Exp(5L))
        val none  = Leveling.gain(catalog, Progress(3, Exp(9L)), Exp(0L))
        assertTrue(p == Progress(3, Exp(7L)), top == Progress(48, Exp(5L)), none == Progress(3, Exp(9L)))
      },
      test("高级装备爆率至少 20%，药按 ×20 封顶 100%") {
        val potion = DropDef(ItemName("金创药(小量)"), 1, 10, 1)
        val rare   = DropDef(ItemName("裁决之杖"), 1, 5000, 1)
        assertTrue(
          Rates.dropChance(potion, catalog.item(potion.item)) == 1.0,
          Rates.dropChance(rare, catalog.item(rare.item)) == 0.2,
          Rates.dropChance(DropDef(DropDef.GoldName, 1, 100, 50), None) == 0.2
        )
      }
    ),
    suite("说明用的精确上下限与概率（#1165）")(
      test("Rng.lowest/highest 求出区间端点，和逐个取值的最小最大一致") {
        val fire = catalog.skill(SkillId(1)).get
        val mc   = Span(3, 9)
        val all  = Odds.of()(r => SpellRules.mcDamage(fire, 2, mc)(using r)).get.keySet
        assertTrue(
          SpellRules.mcDamage(fire, 2, mc)(using Rng.lowest) == all.min,
          SpellRules.mcDamage(fire, 2, mc)(using Rng.highest) == all.max
        )
      },
      test("Odds：概率和为 1，简单情况与手算一致") {
        val two = Odds.of()(r => r.random(2) + r.random(3)).get
        assertTrue(
          math.abs(two.values.sum - 1.0) < 1e-9,
          math.abs(two(1) - 2.0 / 6) < 1e-9,
          // 瞬息移动 0 级：Random(11) < 4 → 4/11
          math.abs(Odds.chance()(r => SpellRules.spaceMoveWorks(0)(using r)).get - 4.0 / 11) < 1e-9,
          // 困魔咒：怪比你低 2 级必困住，同级 Random(4) ≥ 2 → 50%
          Odds.chance()(r => SpellRules.holyCurtainCatches(20, 18)(using r)).contains(1.0),
          Odds.chance()(r => SpellRules.holyCurtainCatches(20, 20)(using r)).contains(0.5),
          // 超过路数上限不给近似值
          Odds.of(limit = 10)(r => r.random(100)).isEmpty
        )
      },
      test("诱惑之光原版流程各结果概率和为 1") {
        val m = Odds.of()(r => SpellRules.tameOriginal(20, 1, SpellRules.TameTarget(20, 200, false))(using r)).get
        assertTrue(math.abs(m.values.sum - 1.0) < 1e-9, m.keySet.contains(SpellRules.TameOutcome.Tamed))
      }
    )
  )
