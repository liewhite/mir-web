package mir.rules

import mir.data.Span
import mir.model.Job
import zio.json.*

/**
 * 数值规则：全部照 1.76 服务端（ref/Mir176 OpenMir2）的公式，出处写在每个函数上。
 * 只做计算，不改任何状态；随机数从 Rng 取（服务端给真随机，测试给固定序列）。
 * 照 TS 版 game/src/world/rules.ts 移植，取整方式与 JS 的 Math.round / Math.floor 一致。
 */

/** 随机数来源：Random(n) 取 0..n-1，n ≤ 0 时为 0（原版 Delphi 的 Random）。 */
trait Rng:
  def random(n: Int): Int

object Rng:
  /** 用 java.util.Random 的算法（JVM 和 Scala.js 结果一致），给种子可复现。 */
  def seeded(seed: Long): Rng = fromScala(new scala.util.Random(seed))

  def fromScala(r: scala.util.Random): Rng = n => if n > 0 then r.nextInt(n) else 0

  /** 用 rng 把列表随机打乱（Fisher–Yates，每种排列等可能）。 */
  def shuffle[A](xs: List[A])(using rng: Rng): List[A] =
    val a = xs.toArray[Any]
    (a.length - 1 until 0 by -1).foreach { i =>
      val j = rng.random(i + 1)
      val t = a(i); a(i) = a(j); a(j) = t
    }
    a.toList.asInstanceOf[List[A]]

  /** 每次都取最小值 0：配合单调的公式求下限（技能说明用）。 */
  val lowest: Rng = _ => 0

  /** 每次都取最大值 n-1：配合单调的公式求上限（技能说明用）。 */
  val highest: Rng = n => math.max(0, n - 1)

  /** 测试用：按顺序吐出给定的值（对 n 取模），用完从头再来。 */
  def sequence(values: Int*): Rng =
    require(values.nonEmpty)
    var i = 0
    n =>
      val v = values(i % values.size)
      i += 1
      if n > 0 then Math.floorMod(v, n) else 0

/** 等级带来的基础属性（PlayObject.RecalcLevelAbilitys，配置取默认值）。 */
final case class LevelAbility(
    maxHp: Int,
    maxMp: Int,
    dc: Span,
    mc: Span,
    sc: Span,
    ac: Span,
    mac: Span
)

/** 人物最终属性。不设负重上限（用户 11-23 定：包裹、穿戴、腕力都不限）。 */
final case class Stats(
    maxHp: Int,
    maxMp: Int,
    ac: Span,
    mac: Span,
    dc: Span,
    mc: Span,
    sc: Span,
    hit: Int,
    agility: Int,
    /** 两次普通攻击的最短间隔（毫秒） */
    hitInterval: Int,
    luck: Int,
    magicAvoid: Int,
    poisonAvoid: Int,
    hpRegen: Int,
    mpRegen: Int
)

object Stats:
  given JsonCodec[Stats] = DeriveJsonCodec.gen

/** 影响属性的被动技能等级（没学为 None）：基本剑术、精神力战法、攻杀剑术。 */
final case class PassiveSkills(oneSword: Option[Int] = None, spiritSword: Option[Int] = None, powerHit: Option[Int] = None)

object Rules:
  /** JS 的 Math.round：四舍五入，.5 向正无穷。 */
  private def round(x: Double): Int = math.floor(x + 0.5).toInt

  /** 在区间里取一个值：lo + Random(|hi - lo| + 1)。 */
  def roll(r: Span)(using rng: Rng): Int = r.lo + rng.random(math.abs(r.hi - r.lo) + 1)

  def levelAbility(job: Job, lv: Int): LevelAbility =
    val l  = lv.toDouble
    val n7 = lv / 7
    job match
      case Job.Taoist =>
        val n6 = round(l / 6)
        LevelAbility(
          maxHp = 14 + round((l / 6 + 2.5) * l),
          maxMp = 13 + round((l / 8) * 2.2 * l),
          dc = Span(math.max(n7 - 1, 0), math.max(1, n7)),
          mc = Span.zero,
          sc = Span(math.max(n7 - 1, 0), math.max(1, n7)),
          ac = Span.zero,
          mac = Span(n6 / 2, n6 + 1)
        )
      case Job.Wizard =>
        LevelAbility(
          maxHp = 14 + round((l / 15 + 1.8) * l),
          maxMp = 13 + round((l / 5 + 2) * 2.2 * l),
          dc = Span(math.max(n7 - 1, 0), math.max(1, n7)),
          mc = Span(math.max(n7 - 1, 0), math.max(1, n7)),
          sc = Span.zero,
          ac = Span.zero,
          mac = Span.zero
        )
      case Job.Warrior =>
        LevelAbility(
          maxHp = 14 + round((l / 4 + 4.5 + l / 20) * l),
          maxMp = 11 + round(l * 3.5),
          dc = Span(math.max(lv / 5 - 1, 1), math.max(1, lv / 5)),
          mc = Span.zero,
          sc = Span.zero,
          ac = Span(0, n7),
          mac = Span.zero
        )

  private def addCapped(a: Span, b: Span): Span = Span(math.min(255, a.lo + b.lo), math.min(255, a.hi + b.hi))

  /**
   * 汇总：等级属性 + 装备 + 被动技能，最后乘职业倍率（Rates.jobSpan：战士攻击、防御、魔防 ×Rates.warriorBody）。
   * 准确默认 5、敏捷默认 15（道士 +3）（PlayObject.RecalcHitSpeed）。
   * 攻速：装备攻速合计 x，x>0 取 x/2，否则 (x-1)/2（向零取整），上限 15；攻击间隔 = 900 - 攻速×60，至少 300。
   */
  def computeStats(job: Job, level: Int, equips: Iterable[ItemEffects], passive: PassiveSkills): Stats =
    val base = levelAbility(job, level)
    val start = Stats(
      maxHp = base.maxHp, maxMp = base.maxMp, ac = base.ac, mac = base.mac, dc = base.dc, mc = base.mc, sc = base.sc,
      hit = 5, agility = if job == Job.Taoist then 18 else 15, hitInterval = 900, luck = 0, magicAvoid = 0,
      poisonAvoid = 0, hpRegen = 0, mpRegen = 0
    )
    val worn = equips.foldLeft(start) { (s, e) =>
      s.copy(
        ac = addCapped(s.ac, e.ac), mac = addCapped(s.mac, e.mac), dc = addCapped(s.dc, e.dc),
        mc = addCapped(s.mc, e.mc), sc = addCapped(s.sc, e.sc),
        hit = s.hit + e.hit, agility = s.agility + e.agility, luck = s.luck + e.luck - e.curse,
        magicAvoid = s.magicAvoid + e.magicAvoid, poisonAvoid = s.poisonAvoid + e.poisonAvoid,
        hpRegen = s.hpRegen + e.hpRegen, mpRegen = s.mpRegen + e.mpRegen
      )
    }
    val passiveHit =
      passive.oneSword.fold(0)(SwordRules.oneSwordHit) +
        passive.spiritSword.fold(0)(SwordRules.spiritSwordHit) +
        passive.powerHit.fold(0)(SwordRules.powerHitHit)
    worn.copy(
      dc = Rates.jobSpan(job, worn.dc), ac = Rates.jobSpan(job, worn.ac), mac = Rates.jobSpan(job, worn.mac),
      hit = worn.hit + passiveHit, hitInterval = hitInterval(equips.iterator.map(_.attackSpeed).sum)
    )

  /** 没有攻速加成时两次普通攻击的间隔（毫秒）、每档攻速缩短的毫秒数、间隔下限、攻速档上限。 */
  val HitIntervalBaseMs = 900
  val HitIntervalStepMs = 60
  val HitIntervalMinMs  = 300
  val HitSpeedMaxLevel  = 15

  /** 全身装备攻速合计 → 攻速档：x>0 取 x/2，否则 (x-1)/2（向零取整），上限 15（PlayObject.RecalcHitSpeed）。 */
  def hitSpeedLevel(speedSum: Int): Int =
    math.min(HitSpeedMaxLevel, if speedSum > 0 then speedSum / 2 else (speedSum - 1) / 2)

  /** 全身装备攻速合计 → 两次普通攻击的最短间隔（毫秒）。 */
  def hitInterval(speedSum: Int): Int =
    math.max(HitIntervalMinMs, HitIntervalBaseMs - hitSpeedLevel(speedSum) * HitIntervalStepMs)

  /**
   * 幸运/诅咒触发的机会是「N 次里 1 次」，N = 10 - min(9, |幸运|)；幸运为 0 时不触发（None）。
   * 幸运触发：这一刀直接打出攻击上限；诅咒（幸运为负）触发：只打出攻击下限。
   */
  def luckOdds(luck: Int): Option[Int] = Option.when(luck != 0)(10 - math.min(9, math.abs(luck)))

  /** 普通攻击的威力：攻击区间里随机，再按幸运/诅咒改成上限/下限（BaseObject.GetAttackPower）。 */
  def meleePower(dc: Span, luck: Int)(using rng: Rng): Int =
    val power = attackPower(dc.lo, dc.hi - dc.lo)
    luckOdds(luck).filter(n => rng.random(n) == 0) match
      case Some(_) => if luck > 0 then dc.hi else dc.lo
      case None    => power

  /** 命中判定：攻方准确 < Random(守方敏捷) 时打空（BaseObject._Attack）。 */
  def hits(attackerHit: Int, defenderAgility: Int)(using rng: Rng): Boolean =
    !(attackerHit < rng.random(defenderAgility))

  /** 物理伤害被防御抵消后的值（BaseObject.GetHitStruckDamage）。 */
  def afterArmor(power: Int, ac: Span)(using Rng): Int = math.max(0, power - roll(ac))

  /** 魔法伤害被魔防抵消后的值（GetMagStruckDamage）。 */
  def afterMagicArmor(power: Int, mac: Span)(using Rng): Int = math.max(0, power - roll(mac))

  /**
   * 命中概率（给属性面板用的说明值）：攻方准确 h，守方敏捷 a，
   * 打空当且仅当 h < Random(a)，Random(a) 均匀取 0..a-1，所以命中率 = min(1, (h+1)/a)。
   */
  def hitChance(h: Int, a: Int): Double = if a <= 0 then 1.0 else math.min(1.0, (h + 1).toDouble / a)

  /** 技能威力（MagicManager.GetPower）：按技能等级放大的基础威力 + 默认威力随机部分。 */
  def skillPower(base: Int, level: Int, defPower: Span)(using rng: Rng): Int =
    round(base / 4.0 * (level + 1)) + defPower.lo + rng.random(defPower.hi - defPower.lo)

  /** MagicManager.GetPower13 */
  def skillPower13(n: Int, level: Int, defPower: Span)(using rng: Rng): Int =
    val d10 = n / 3.0
    val d18 = n - d10
    round(d18 / 4 * (level + 1) + d10 + (defPower.lo + rng.random(defPower.hi - defPower.lo)))

  /** MagicManager.MPow */
  def magicBase(power: Span)(using rng: Rng): Int = power.lo + rng.random(power.hi - power.lo)

  /** GetRPow：区间里随机一个值 */
  def rpow(r: Span)(using rng: Rng): Int = if r.hi > r.lo then rng.random(r.hi - r.lo + 1) + r.lo else r.lo

  /** GetAttackPower(base, spread)：base + Random(spread + 1) */
  def attackPower(base: Int, spread: Int)(using rng: Rng): Int = base + rng.random(math.max(0, spread) + 1)

/**
 * 自然回复：每隔 intervalMs 回一次血和蓝。中绿毒期间不回，毒消失后从头计时（原版绿毒每掉一次血就把回血计时清零，
 * ObjBase.pas 2729–2736，#1214）。
 * - 玩家：每 IntervalMs 回 max(1, 上限/75) + 装备的体力/魔法恢复（原版 1.76 是每 6 秒回 上限/75+1、
 *   蓝每 16 秒回 上限/18+1，这里沿用 TS 版加快后的规则）。
 * - 怪物和宝宝：照原版每 MonsterIntervalMs 回 上限/75+1（ObjBase.pas 2276–2320），没有蓝。
 */
object Recovery:
  /** 一次自然回复：隔多久、回多少血和蓝 */
  final case class Tick(intervalMs: Int, hp: Int, mp: Int)

  val IntervalMs        = 2000
  val MonsterIntervalMs = 6000
  def hpPerTick(s: Stats): Int = math.max(1, s.maxHp / 75) + s.hpRegen
  def mpPerTick(s: Stats): Int = math.max(1, s.maxMp / 75) + s.mpRegen
  def player(s: Stats): Tick   = Tick(IntervalMs, hpPerTick(s), mpPerTick(s))
  def monster(maxHp: Int): Tick = Tick(MonsterIntervalMs, maxHp / 75 + 1, 0)

/**
 * 精确概率：把一个用 Rng 的计算的每一种随机取值都走一遍，得出每种结果的概率（技能说明用，#1165）。
 * 只用于分支不多的公式；走的路数超过 limit 时返回 None（不给近似值）。
 */
object Odds:
  def of[A](limit: Int = 200_000)(f: Rng => A): Option[Map[A, Double]] =
    val out   = scala.collection.mutable.HashMap.empty[A, Double]
    var path  = Vector.empty[Int] // 这一路每次取的值
    var sizes = Vector.empty[Int] // 这一路每次的 n
    var runs  = 0
    var more  = true
    while more && runs < limit do
      var i     = 0
      val taken = Vector.newBuilder[Int]
      val ns    = Vector.newBuilder[Int]
      val rng: Rng = n =>
        val k = math.max(1, n)
        val v = if i < path.size then path(i) else 0
        i += 1
        taken += v
        ns += k
        if n > 0 then v else 0
      val a = f(rng)
      path = taken.result()
      sizes = ns.result()
      out(a) = out.getOrElse(a, 0.0) + sizes.foldLeft(1.0)((p, n) => p / n)
      runs += 1
      // 下一路：从最后一次往前找还能加 1 的
      val j = path.indices.lastIndexWhere(k => path(k) + 1 < sizes(k))
      if j < 0 then more = false else path = path.take(j) :+ (path(j) + 1)
    Option.when(!more)(out.toMap)

  /** 条件成立的概率。 */
  def chance(limit: Int = 200_000)(f: Rng => Boolean): Option[Double] = of(limit)(f).map(_.getOrElse(true, 0.0))
