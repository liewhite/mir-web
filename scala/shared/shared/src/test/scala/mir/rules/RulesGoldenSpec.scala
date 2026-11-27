package mir.rules

import mir.core.*
import mir.data.*
import mir.model.*
import mir.testkit.TestFiles
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 和 TS 版逐条对照（testdata/ts-golden.json 由 gen-golden.sh 用 game/src/world 算出）。 */
object RulesGoldenSpec extends ZIOSpecDefault:
  private given JsonDecoder[LevelAbility] = DeriveJsonDecoder.gen
  private given JsonDecoder[ItemEffects]  = DeriveJsonDecoder.gen

  private final case class TsPassive(oneSword: Int, spiritSword: Int, powerHit: Int):
    def toScala: PassiveSkills =
      def lv(n: Int) = Option.when(n >= 0)(n)
      PassiveSkills(lv(oneSword), lv(spiritSword), lv(powerHit))
  private given JsonDecoder[TsPassive] = DeriveJsonDecoder.gen

  private final case class StatsCase(job: Int, level: Int, items: List[ItemName], passive: TsPassive, stats: Stats)
  private given JsonDecoder[StatsCase] = DeriveJsonDecoder.gen

  private final case class ItemCase(name: ItemName, kind: Json, effects: ItemEffects, requirement: Json, highEquip: Boolean)
  private given JsonDecoder[ItemCase] = DeriveJsonDecoder.gen

  private final case class DropCase(monster: MonsterName, item: ItemName, chance: Double)
  private given JsonDecoder[DropCase] = DeriveJsonDecoder.gen

  private final case class TsRates(
      exp: Int,
      drop: Int,
      bossHighEquipMin: Double,
      highEquipLevel: Int,
      maxDropsPerKill: Int,
      warriorBody: Double,
      skillTrain: Int
  )
  private given JsonDecoder[TsRates] = DeriveJsonDecoder.gen

  private final case class Golden(
      rates: TsRates,
      levelAbility: List[List[LevelAbility]],
      items: List[ItemCase],
      stats: List[StatsCase],
      drops: List[DropCase]
  )
  private given JsonDecoder[Golden] = DeriveJsonDecoder.gen

  private lazy val golden: Golden =
    TestFiles.tsGolden.fromJson[Golden].fold(e => throw new AssertionError(s"ts-golden.json：$e"), identity)
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private def job(code: Int): Job = Job.values.find(_.code == code).get

  /** TS 版（只作对照）没有 1.80 才有的腰带、靴子位（#1311、#1312 只做了 Scala 版），也没有勋章格（#1332），这几类不比。 */
  private val scalaOnly = Set(EquipSlot.Belt, EquipSlot.Boots, EquipSlot.Medal)
  private def compared(d: ItemDef): Boolean = ItemKind.of(d) match
    case ItemKind.Equip(slots) => !slots.exists(scalaOnly)
    case _                     => true

  /** 把 ItemKind 写成 TS 版 itemKind() 的 JSON 形状，好直接比。 */
  private def tsKind(k: ItemKind): Json =
    def obj(kind: String, fields: (String, Json)*) = Json.Obj(("kind" -> Json.Str(kind)) +: fields*)
    k match
      case ItemKind.Potion(hp, mp, instant) =>
        obj("potion", "hp" -> Json.Num(hp), "mp" -> Json.Num(mp), "instant" -> Json.Bool(instant))
      case ItemKind.Scroll(e) =>
        val name = e match
          case ScrollEffect.Escape => "escape"
          case ScrollEffect.Random => "random"
          case ScrollEffect.Town   => "town"
          case ScrollEffect.Other  => "other"
          case ScrollEffect.Bless  => "other" // TS 版没做祝福油（#1320）
        obj("scroll", "effect" -> Json.Str(name))
      case ItemKind.Book(j, level) => obj("book", "job" -> Json.Num(j.fold(-1)(_.code)), "level" -> Json.Num(level))
      case ItemKind.Bundle(of)     => obj("bundle", "of" -> Json.Num(of))
      case ItemKind.Equip(slots)   => obj("equip", "slots" -> Json.Arr(slots.map(s => Json.Str(s.key))*))
      case ItemKind.Amulet(p) =>
        obj("amulet", "poison" -> Json.Num(p.fold(0) { case PoisonKind.Green => 1; case PoisonKind.Red => 2 }))
      case ItemKind.Material => obj("material")
      case ItemKind.Other    => obj("other")

  private def tsRequirement(r: Requirement): Json =
    val stat = r match
      case Requirement.Level(_) => "level"
      case Requirement.Dc(_)    => "dc"
      case Requirement.Mc(_)    => "mc"
      case Requirement.Sc(_)    => "sc"
    Json.Obj("stat" -> Json.Str(stat), "value" -> Json.Num(r.value))

  def spec = suite("规则与 TS 版一致")(
    test("倍率（rates.ts）") {
      val scala = TsRates(
        Rates.exp, Rates.drop, Rates.bossHighEquipMin, Rates.highEquipLevel,
        Rates.maxDropsPerKill, Rates.warriorBody, Rates.skillTrain
      )
      assertTrue(golden.rates == scala)
    },
    test("技能各级熟练度是原表的 1/100（向上取整、至少 1）") {
      val fire = catalog.data.skills.find(_.name == "火球术").get
      val bad  = for
        d <- catalog.data.skills
        l <- 0 to 2
        if Rates.trainNeed(d, l) != math.max(1, math.ceil(d.train(l) / 100.0).toInt)
      yield (d.name, l)
      assertTrue((0 to 2).map(Rates.trainNeed(fire, _)) == Seq(5, 15, 30), bad.isEmpty)
    },
    test("等级基础属性：三职业 1–60 级") {
      val diffs = for
        (rows, j) <- golden.levelAbility.zipWithIndex
        (want, i) <- rows.zipWithIndex
        got = Rules.levelAbility(job(j), i + 1)
        if got != want
      yield (j, i + 1, got, want)
      assertTrue(golden.levelAbility.flatten.size == 180, diffs.isEmpty)
    },
    test("每件物品的用途、加成、穿戴要求、是否高级装备") {
      val diffs = golden.items.flatMap { c =>
        val d = catalog.item(c.name).get
        if !compared(d) then Nil
        else List(
          Option.when(tsKind(ItemKind.of(d)) != c.kind)(s"${c.name} 用途"),
          Option.when(ItemEffects.of(d) != c.effects)(s"${c.name} 加成"),
          Option.when(tsRequirement(Requirement.of(d)) != c.requirement)(s"${c.name} 要求"),
          Option.when(Rates.isHighEquip(d) != c.highEquip)(s"${c.name} 高级装备")
        ).flatten
      }
      assertTrue(golden.items.size == catalog.data.items.size, diffs.isEmpty)
    },
    test("人物最终属性：装备两件 + 被动技能") {
      val diffs = golden.stats.filter { c =>
        val effects = c.items.map(n => ItemEffects.of(catalog.item(n).get))
        Rules.computeStats(job(c.job), c.level, effects, c.passive.toScala) != c.stats
      }
      assertTrue(golden.stats.nonEmpty, diffs.isEmpty)
    },
    test("每一行爆率的实际掉落概率") {
      // 同一只怪的爆率表里同一物品可能出现几行，按怪物分组后逐行按顺序比
      // TS 认不出腰带靴子、勋章，boss 保底在两边不同：这几类的行不比
      def keep(item: ItemName) = catalog.item(item).forall(compared)
      val want = golden.drops.groupBy(_.monster).view.mapValues(_.collect { case c if keep(c.item) => (c.item, c.chance) }).toMap
      val got = catalog.data.drops.map { (m, rs) =>
        m -> rs.collect { case d if d.isGold || keep(d.item) =>
          (d.item, Rates.dropChance(Rates.rankOf(m), d, if d.isGold then None else catalog.item(d.item)))
        }
      }
      val diffs = (want.keySet ++ got.keySet).filter(m => want.getOrElse(m, Nil) != got.getOrElse(m, Nil))
      assertTrue(golden.drops.size == 6833 + 309 + 69 * 2 + 195 + 210, diffs.isEmpty) // + 1.80 爆率（#1314）、暗之魔龙教主（#1329）、暗之赤月恶魔（#1331）、圣域（#1332）
    }
  )
