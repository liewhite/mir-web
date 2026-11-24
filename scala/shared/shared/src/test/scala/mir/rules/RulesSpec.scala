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
      test("战士攻击、防御、魔防（含装备）×1.5 四舍五入，别的职业不变（#1176）") {
        val e  = ItemEffects.of(catalog.item(ItemName("裁决之杖")).get)
        val w  = Rules.computeStats(Job.Warrior, 30, List(e), PassiveSkills())
        val lv = Rules.levelAbility(Job.Warrior, 30)
        def x(v: Int) = math.floor(v * 1.5 + 0.5).toInt
        val t  = Rules.computeStats(Job.Taoist, 30, List(e), PassiveSkills())
        val tl = Rules.levelAbility(Job.Taoist, 30)
        assertTrue(
          w.dc == Span(x(lv.dc.lo + e.dc.lo), x(lv.dc.hi + e.dc.hi)),
          w.ac == Span(x(lv.ac.lo + e.ac.lo), x(lv.ac.hi + e.ac.hi)),
          w.mac == Span(x(lv.mac.lo + e.mac.lo), x(lv.mac.hi + e.mac.hi)),
          w.dc.hi > lv.dc.hi + e.dc.hi,
          t.dc == Span(tl.dc.lo + e.dc.lo, tl.dc.hi + e.dc.hi)
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
      test("爆率：普通怪一律原版 ×20 封顶 100%，boss 的高级装备至少 10%（#1241）") {
        // 按数据表里真实的爆率行算（第一行）
        def chance(monster: String, name: String) =
          val m = MonsterName(monster)
          val d = catalog.drops(m).find(_.item == ItemName(name)).get
          Rates.dropChance(Rates.rankOf(m), d, if d.isGold then None else catalog.item(d.item))
        def near(a: Double, b: Double) = math.abs(a - b) < 1e-12
        assertTrue(
          Rates.bosses.forall(catalog.monster(_).isDefined),
          Rates.rankOf(MonsterName("祖玛教主")) == MonsterRank.Boss,
          Rates.rankOf(MonsterName("祖玛卫士3")) == MonsterRank.Common,
          // 普通怪：高级装备也没有保底
          near(chance("祖玛卫士3", "裁决之杖"), 10.0 / 900000 * 20), // ≈ 0.022%
          near(chance("祖玛卫士3", "黑铁头盔"), 10.0 / 90000 * 20),  // ≈ 0.22%
          near(chance("祖玛卫士3", "强效金创药"), 10.0 / 300 * 20),
          // boss：高级装备原版 ×20，低于 10% 的抬到 10%
          near(chance("祖玛教主", "力量戒指"), 0.4),  // 1/50 ×20
          near(chance("沃玛教主", "三眼手镯"), 0.2),  // 1/100 ×20
          chance("祖玛教主", "裁决之杖") == 0.1,       // 1/6000 ×20 → 10%
          chance("祖玛教主", "井中月") == 0.1,         // 1/300 ×20 ≈ 6.7% → 10%
          // boss 的非高级装备、药不保底
          near(chance("祖玛教主", "复活戒指"), 1.0 / 100000 * 20),
          chance("祖玛教主", "强效金创药") == 1.0,
          Rates.dropChance(MonsterRank.Boss, DropDef(DropDef.GoldName, 1, 100, 50), None) == 0.2
        )
      },
      test("boss 刷新最长 2 小时，表里更短的和普通怪照表（#1262）") {
        def minutesOf(name: String) =
          val m = MonsterName(name)
          catalog.data.spawns.filter(_.monster == m).map(s => Rates.respawnMinutes(m, s.minutes)).toSet
        val bossMax = catalog.data.spawns.filter(s => Rates.bosses(s.monster)).map(s => Rates.respawnMinutes(s.monster, s.minutes)).max
        assertTrue(
          minutesOf("赤月恶魔") == Set(120), // 表里 360
          minutesOf("祖玛教主") == Set(120), // 表里 180
          minutesOf("石墓尸王") == Set(60),  // 表里 60，不变
          bossMax == 120,
          Rates.respawnMinutes(MonsterName("祖玛卫士3"), 360) == 360
        )
      },
      test("一只怪最多掉 10 件：多了留原版爆率最低的，同爆率按表顺序；可叠的一叠算一件（#1241）") {
        def item(n: String) = catalog.item(ItemName(n)).get
        def row(n: String, den: Int, amount: Int = 1) = DropDef(ItemName(n), 1, den, amount)
        val potions = (1 to 12).toList.flatMap(i => DropRules.pieces(row("金创药(中量)", 10 + i), item("金创药(中量)")))
        val charm   = DropRules.pieces(row("护身符", 10, amount = 50), item("护身符"))
        val rare    = List(row("力量戒指", 800), row("裁决之杖", 5000), row("绿色项链", 800))
          .flatMap(r => DropRules.pieces(r, item(r.item.value)))
        val kept = DropRules.cap(potions ++ charm ++ rare)
        val few  = potions.take(4) ++ charm
        assertTrue(
          charm.map(_.count) == List(50),
          DropRules.pieces(row("金创药(中量)", 10, amount = 3), item("金创药(中量)")).map(_.count) == List(3),
          DropRules.pieces(row("木剑", 10, amount = 3), item("木剑")).map(_.count) == List(1, 1, 1),
          kept.size == 10,
          kept.take(3).map(_.item.name.value) == List("裁决之杖", "力量戒指", "绿色项链"),
          // 药按爆率 1/22、1/21、…从低到高留；护身符 1/10 最常见，被截掉
          kept.drop(3).map(_.row.denominator) == List(22, 21, 20, 19, 18, 17, 16),
          !kept.exists(_.item.name.value == "护身符"),
          DropRules.cap(few) == few
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
      test("刺杀隔一格威力：0 级 40%，每级 +20%，3 级 100%") {
        assertTrue(
          (0 to 3).map(SwordRules.longPower(100, _)) == Vector(40, 60, 80, 100),
          SwordRules.longPower(7, 0) == 3, // 2.8 四舍五入
          SwordRules.longPower(1, 0) == 0
        )
      },
      test("诱惑之光原版流程各结果概率和为 1") {
        val m = Odds.of()(r => SpellRules.tameOriginal(20, 1, SpellRules.TameTarget(20, 200, false))(using r)).get
        assertTrue(math.abs(m.values.sum - 1.0) < 1e-9, m.keySet.contains(SpellRules.TameOutcome.Tamed))
      }
    )
  )
