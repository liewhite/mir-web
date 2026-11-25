package mir.rules

import mir.core.*
import mir.data.*
import mir.model.*
import mir.testkit.TestFiles
import zio.test.*

/** 挖矿和武器升级的规则（#1286，照原版 ObjPlay.pas MakeMine、ObjNpc.pas UpgradeWapon / GetBackupgWeapon）。 */
object RefineSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private def item(n: String) = catalog.item(ItemName(n)).get
  private var uid             = 0L
  private def inst(n: String, dura: Option[Int] = None): ItemInstance =
    uid += 1
    val it = ItemInstance.fresh(ItemUid(uid), item(n))
    dura.fold(it)(d => it.copy(dura = d))
  private def ore(purity: Int) = inst("黑铁矿石", Some(purity))

  private def near(a: Double, b: Double) = math.abs(a - b) < 1e-9

  def spec = suite("挖矿、武器升级（#1286）")(
    test("材料：包裹里全部黑铁矿石和首饰类（含头盔）都收，别的不收；点数照原版折算") {
      val bag = Vector(
        ore(16000), ore(15000), ore(12400), ore(9500), ore(8000), ore(3000),
        inst("力量戒指"), inst("绿色项链"), inst("死神手套"), inst("骷髅头盔"),
        inst("金创药(小量)"), inst("木剑"), inst("铁矿", Some(15000))
      )
      val m = WeaponRefine.materials(bag, catalog.item)
      val r = WeaponRefine.recipe(m)
      assertTrue(
        m.ores.size == 6,
        m.jewels.map(_._1.name.value).toSet == Set("力量戒指", "绿色项链", "死神手套", "骷髅头盔"),
        // 攻击：项链 2+5=7、戒指 0+6=6、手镯 1+2+1=4 → 7/5 + 6/3 = 3；魔法、道术只有手镯的 +1 → 0
        // 纯度：16、15、12、10（9.5 银行家舍入）、8 前五块合计 61 → Round(5 + 61/5) = 17
        r.contains(WeaponRefine.Recipe(3, 0, 0, 17)),
        WeaponRefine.attempt(r.get) == WeaponRefine.Attempt(WeaponRefine.Aspect.Dc, 31)
      )
    },
    test("往哪升：最高的一项，并列时道术 > 魔法 > 攻击；升道术的成功率按魔法点数（原版）；封顶 85%") {
      import WeaponRefine.{Aspect, Attempt, Recipe, attempt}
      assertTrue(
        attempt(Recipe(0, 0, 0, 10)) == Attempt(Aspect.Sc, 10),
        attempt(Recipe(4, 4, 0, 10)) == Attempt(Aspect.Mc, 38),
        attempt(Recipe(0, 2, 8, 10)) == Attempt(Aspect.Sc, 24),
        attempt(Recipe(20, 0, 0, 10)) == Attempt(Aspect.Dc, 85),
        attempt(Recipe(9, 0, 0, 10)) == Attempt(Aspect.Dc, 73)
      )
    },
    test("确认前给的概率和实际掷骰逐一核对（把每种随机取值都走一遍）") {
      val sword = inst("炼狱")
      def check(r: WeaponRefine.Recipe) =
        val f = WeaponRefine.forecast(sword, r)
        val exact = Odds.of(limit = 1_000_000)(rng =>
          WeaponRefine.refine(sword, r)(using rng) match
            case WeaponRefine.Result.Raised(_, by, _) => by
            case WeaponRefine.Result.Broken           => 0
        ).get
        (1 to 3).forall(k => near(f.raised.getOrElse(k, 0.0), exact.getOrElse(k, 0.0))) && near(f.broken, exact.getOrElse(0, 0.0))
      assertTrue(
        check(WeaponRefine.Recipe(3, 0, 0, 16)),
        check(WeaponRefine.Recipe(9, 0, 0, 16)),
        check(WeaponRefine.Recipe(11, 0, 0, 16))
      )
    },
    test("成功加在对应附加上、持久跟着变；附加合计到 20 点必碎") {
      given Rng = Rng.sequence(0) // 每次都取 0：成功，且 2 点、3 点都中
      val sword  = inst("炼狱")
      val raised = WeaponRefine.refine(sword, WeaponRefine.Recipe(11, 0, 0, 30))
      val full   = sword.copy(bonus = Some(ItemBonus.Weapon(dc = 15, mc = 5)))
      assertTrue(
        raised match
          case WeaponRefine.Result.Raised(WeaponRefine.Aspect.Dc, 3, w) =>
            w.bonus.contains(ItemBonus.Weapon(dc = 3)) && w.duraMax == sword.duraMax // 纯度 30：Random(12) 取 0 不加
          case _ => false
        ,
        WeaponRefine.refine(full, WeaponRefine.Recipe(11, 0, 0, 30)) == WeaponRefine.Result.Broken,
        WeaponRefine.forecast(full, WeaponRefine.Recipe(11, 0, 0, 30)).broken == 1.0
      )
    },
    test("持久上限：纯度分 ≤8 扣 3 点（不到 3 点的减半）、9–15 有 6/纯度 机会扣 1 点、16–17 不变、≥18 有机会加") {
      import WeaponRefine.duraMaxAfter
      def odds(max: Int, p: Int) = Odds.of()(rng => duraMaxAfter(max, p)(using rng) - max).get
      assertTrue(
        odds(28000, 8) == Map(-3000 -> 1.0),
        odds(2000, 5) == Map(-1000 -> 1.0),
        near(odds(28000, 12)(-1000), 6.0 / 12),
        odds(28000, 17) == Map(0 -> 1.0),
        // 纯度分 30：Random(12) 取 0 不变、1–4 +1、5–7 +2、8–11 +4
        odds(28000, 30) == Map(0 -> 1.0 / 12, 1000 -> 4.0 / 12, 2000 -> 3.0 / 12, 4000 -> 4.0 / 12)
      )
    },
    test("不能升级的原因：没拿武器、没黑铁、钱不够") {
      val m  = WeaponRefine.materials(Vector(ore(12000)), catalog.item)
      val no = WeaponRefine.materials(Vector(inst("力量戒指")), catalog.item)
      val w  = Some(inst("炼狱"))
      assertTrue(
        WeaponRefine.refusal(None, 50000, m).isDefined,
        WeaponRefine.refusal(w, 50000, no).isDefined,
        WeaponRefine.refusal(w, 9999, m).isDefined,
        WeaponRefine.refusal(w, 10000, m).isEmpty
      )
    },
    test("挖矿：出哪种矿照原版 Random(120) 分段；敲下碎石 1/4，敲下时出矿 1/12，包裹满了不出") {
      val kinds = Odds.of()(rng => Mining.ore()(using rng)).get
      val v     = Mining.Vein(left = 5, refill = 7, refilledAt = 0)
      val hit   = Odds.of()(rng => Mining.strike(v, 1000, roomForOre = true)(using rng)._2 match
        case Mining.Strike.Chip(true)    => 2
        case Mining.Strike.Chip(false)   => 1
        case Mining.Strike.Nothing       => 0
      ).get
      val full = Odds.chance()(rng => Mining.strike(v, 1000, roomForOre = false)(using rng)._2 match
        case Mining.Strike.Chip(ore) => ore
        case _                       => false
      ).get
      assertTrue(
        near(kinds(Mining.Ore.BlackIron), 11.0 / 120),
        near(kinds(Mining.Ore.Gold), 2.0 / 120),
        near(kinds(Mining.Ore.Copper), 64.0 / 120),
        near(hit(0), 3.0 / 4),
        near(hit(2), 1.0 / 48),
        full == 0.0
      )
    },
    test("矿脉挖空后不出东西，离上次补满过 10 分钟再挖就补上（这一下不出）") {
      given Rng   = Rng.sequence(0)
      val empty   = Mining.Vein(left = 0, refill = 7, refilledAt = 0)
      val (a, ra) = Mining.strike(empty, Mining.RefillMs, roomForOre = true)
      val (b, rb) = Mining.strike(empty, Mining.RefillMs + 1, roomForOre = true)
      val (c, rc) = Mining.strike(b, Mining.RefillMs + 2, roomForOre = true)
      assertTrue(
        a == empty, ra == Mining.Strike.Nothing,
        b.left == 7, rb == Mining.Strike.Nothing,
        c.left == 6, rc.isInstanceOf[Mining.Strike.Chip]
      )
    }
  )
