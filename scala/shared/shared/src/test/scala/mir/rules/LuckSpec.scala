package mir.rules

import mir.core.*
import mir.data.*
import mir.model.*
import mir.testkit.TestFiles
import zio.test.*

/** 幸运（#1320）：魔法道术吃幸运、祝福油（照原版 GetAttackPower、WeaptonMakeLuck，概率按用户 11-26 定稍微放宽）。 */
object LuckSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private def item(n: String) = catalog.item(ItemName(n)).get
  private def sword(bonus: Option[ItemBonus] = None) = ItemInstance.fresh(ItemUid(1L), item("铁剑")).copy(bonus = bonus)

  def spec = suite("幸运（#1320）")(
    test("luckyPower：幸运触发打出最大值、诅咒触发打出最小值，幸运 0 不多抽随机数") {
      // Rng.sequence：第一个数定区间里的值，第二个数是幸运判定（0 = 触发）
      assertTrue(
        Rules.luckyPower(10, 5, 0)(using Rng.sequence(2)) == 12,
        Rules.luckyPower(10, 5, 3)(using Rng.sequence(2, 0)) == 15,
        Rules.luckyPower(10, 5, 3)(using Rng.sequence(2, 1)) == 12,
        Rules.luckyPower(10, 5, -3)(using Rng.sequence(2, 0)) == 10,
        Rules.meleePower(Span(4, 9), 9)(using Rng.sequence(1, 0)) == 9
      )
    },
    test("攻击魔法、灵魂火符、治愈术吃幸运：幸运 9 时次次是上限") {
      val fire = catalog.skill(SkillId(1)).get
      val soul = catalog.skill(SkillId(13)).get
      val heal = catalog.skill(SkillId(2)).get
      val mc   = Span(3, 9)
      val sc   = Span(5, 12)
      // 幸运 9：1/1 触发；把每个随机数都取 0 也得到上限
      def maxOf(f: Rng => Int) = f(Rng.highest)
      assertTrue(
        SpellRules.mcDamage(fire, 3, mc, 9)(using Rng.lowest) == SpellRules.mcDamage(fire, 3, mc, 0)(using Rng.sequence(0, 0, mc.hi - mc.lo + 1)),
        SpellRules.scDamage(soul, 3, sc, 9)(using Rng.lowest) == SpellRules.scDamage(soul, 3, sc, 0)(using Rng.sequence(0, 0, sc.hi - sc.lo + 1)),
        SpellRules.heal(heal, 3, sc, 9)(using Rng.lowest) == SpellRules.heal(heal, 3, sc, 0)(using Rng.sequence(0, 0, (sc.hi - sc.lo) * 2 + 1)),
        SpellRules.mcDamage(fire, 3, mc, 9)(using Rng.lowest) > SpellRules.mcDamage(fire, 3, mc, 0)(using Rng.lowest),
        maxOf(r => SpellRules.mcDamage(fire, 3, mc, 0)(using r)) >= SpellRules.mcDamage(fire, 3, mc, 9)(using Rng.lowest)
      )
    },
    test("祝福油机会：0→1 必成，1→3 是 1/(4+k)，3→7 是 1/(30×max(1,k))，7 封顶") {
      val k = BlessOil.steps(item("裁决之杖"))
      assertTrue(
        BlessOil.odds(0, 6).contains(1),
        BlessOil.odds(1, 0).contains(4),
        BlessOil.odds(2, 6).contains(10),
        BlessOil.odds(3, 0).contains(30),
        BlessOil.odds(6, 6).contains(180),
        BlessOil.odds(7, 0).isEmpty,
        k == math.abs(item("裁决之杖").dc.hi - item("裁决之杖").dc.lo) / 5
      )
    },
    test("祝福油结果：加幸运、先抵诅咒、被诅咒先扣幸运，诅咒最多 10") {
      val d = item("铁剑")
      def use(b: Option[ItemBonus], rolls: Int*) = BlessOil(sword(b), d)(using Rng.sequence(rolls*))
      def blessed(b: ItemBonus.Weapon) = BlessOil.Outcome.Blessed(sword(Some(b)))
      def cursed(b: ItemBonus.Weapon)  = BlessOil.Outcome.Cursed(sword(Some(b)))
      assertTrue(
        use(None, 1) == blessed(ItemBonus.Weapon(luck = 1)),                              // 没被诅咒，0→1 必成
        use(Some(ItemBonus.Weapon(dc = 2, luck = 1)), 1, 0) == blessed(ItemBonus.Weapon(dc = 2, luck = 2)),
        use(Some(ItemBonus.Weapon(luck = 1)), 1, 1) == BlessOil.Outcome.NoEffect,
        use(Some(ItemBonus.Weapon(curse = 2)), 1) == blessed(ItemBonus.Weapon(curse = 1)), // 先去诅咒
        use(Some(ItemBonus.Weapon(luck = 3)), 0) == cursed(ItemBonus.Weapon(luck = 2)),     // 被诅咒先扣幸运
        use(None, 0) == cursed(ItemBonus.Weapon(curse = 1)),
        use(Some(ItemBonus.Weapon(curse = 10)), 0) == cursed(ItemBonus.Weapon(curse = 10)),
        use(Some(ItemBonus.Weapon(luck = 7)), 1) == BlessOil.Outcome.NoEffect,
        use(Some(ItemBonus.Weapon(luck = 1)), 0) == BlessOil.Outcome.Cursed(sword(None))  // 扣光了不留空附加
      )
    },
    test("武器上祝福来的幸运、诅咒计入人物幸运") {
      val d = item("铁剑")
      val e = ItemEffects.of(d, Some(ItemBonus.Weapon(luck = 3, curse = 1)))
      val s = Rules.computeStats(Job.Warrior, 10, List(e), PassiveSkills(None, None, None))
      assertTrue(e.luck == ItemEffects.of(d).luck + 3, e.curse == ItemEffects.of(d).curse + 1, s.luck == e.luck - e.curse)
    },
    test("祝福油归为可用的卷轴；旧存档里的武器附加没有幸运字段也能读") {
      val old = """{"Weapon":{"dc":2,"mc":0,"sc":0,"hit":0,"attackSpeed":0,"strength":1}}"""
      assertTrue(
        ItemKind.of(item("祝福油")) == ItemKind.Scroll(ScrollEffect.Bless),
        zio.json.DecoderOps(old).fromJson[ItemBonus] == Right(ItemBonus.Weapon(dc = 2, strength = 1))
      )
    }
  )
