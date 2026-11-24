package mir.rules

import mir.core.*
import mir.data.*
import mir.model.*
import mir.testkit.TestFiles
import zio.json.*
import zio.test.*

object RandomUpgradeSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private def item(n: String) = catalog.item(ItemName(n)).get

  def spec = suite("极品（#1191）")(
    test("附加属性叠在物品表上限上，再按部位解读；武器攻速真实值相加") {
      val sword = item("铁剑")
      val armor = item("重盔甲(男)")
      val w     = ItemEffects.of(sword, Some(ItemBonus.Weapon(dc = 2, hit = 1, attackSpeed = -1, strength = 3)))
      val a     = ItemEffects.of(armor, Some(ItemBonus.Gear(ac = 2, mac = 1)))
      val base  = ItemEffects.of(sword)
      assertTrue(
        w.dc == Span(sword.dc.lo, sword.dc.hi + 2),
        w.hit == base.hit + 1,
        w.attackSpeed == base.attackSpeed - 1,
        a.ac == Span(armor.ac.lo, armor.ac.hi + 2),
        a.mac == Span(armor.mac.lo, armor.mac.hi + 1),
        ItemEffects.of(sword, None) == base
      )
    },
    test("概率照原版：爆出的武器约 3% 带附加、衣服约 1.4%，药不会；高档越来越少") {
      given Rng = Rng.seeded(1191)
      val n      = 200_000
      val sword  = item("铁剑")
      val armor  = item("重盔甲(男)")
      val potion = item("金创药(小量)")
      def drops(d: ItemDef) = (1 to n).map(i => RandomUpgrade.monsterDrop(ItemInstance.fresh(ItemUid(i.toLong), d), d))
      val sw    = drops(sword)
      val ar    = drops(armor)
      val rate  = (xs: Seq[ItemInstance]) => xs.count(_.special).toDouble / n
      val dcs   = sw.flatMap(_.bonus).collect { case b: ItemBonus.Weapon => b.dc }.filter(_ > 0)
      // 武器：1/10 × (1 − (14/15)³ × (1 − P攻速) × 23/24 × 9/10) ≈ 3.0%；衣服：1/10 × (1 − (29/30)² × (39/40)³) ≈ 1.4%
      assertTrue(
        rate(sw) > 0.027 && rate(sw) < 0.033,
        rate(ar) > 0.012 && rate(ar) < 0.016,
        drops(potion).forall(_.bonus.isEmpty),
        dcs.count(_ == 1) > dcs.count(_ == 2) * 8,
        sw.forall(i => i.dura == i.duraMax && i.duraMax >= sword.dura),
        sw.exists(_.duraMax > sword.dura)
      )
    },
    test("强度让武器少磨：强度 0 每刀 1/3，越高越少，6 起不磨") {
      val cs = (0 to 6).map(Durability.weaponWearChance)
      assertTrue(
        math.abs(cs(0) - 1.0 / 3) < 1e-9,
        cs.sliding(2).forall(p => p(1) < p(0) || p(1) == 0),
        cs(6) == 0.0
      )
    },
    test("存档：旧物品没有 bonus 照常读，带 bonus 的存取一致，普通物品不写这个字段") {
      val old   = """{"uid":1,"name":"铁剑","dura":9000,"duraMax":9000}""".fromJson[ItemInstance]
      val jp    = ItemInstance(ItemUid(2L), ItemName("铁剑"), 9000, 11000, 1, Some(ItemBonus.Weapon(dc = 1)))
      val plain = ItemInstance(ItemUid(3L), ItemName("铁剑"), 9000, 9000)
      assertTrue(
        old.map(_.bonus) == Right(None),
        jp.toJson.fromJson[ItemInstance] == Right(jp),
        !plain.toJson.contains("bonus")
      )
    }
  )
