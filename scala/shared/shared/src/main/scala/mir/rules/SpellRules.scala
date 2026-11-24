package mir.rules

import mir.core.MonsterName
import mir.data.{MonsterDef, SkillDef, Span}
import mir.model.PoisonKind

/** 一次下的毒：持续秒数和强度（绿毒是每跳掉的血，红毒是挨打加深的百分比）。 */
final case class PoisonDose(secs: Int, power: Int)

/** 宝宝的身板：血上限、攻击、防御、魔防（召唤加成或收服时定下，跟着主人换图带走）。 */
final case class PetBody(hp: Int, dc: Span, ac: Span, mac: Span)

object PetBody:
  /** 数据表原样的身板（野怪、收服的宝宝） */
  def of(d: MonsterDef): PetBody = PetBody(d.hp, d.dc, d.ac, d.mac)

/**
 * 召唤那一刻定下的加成：技能等级、主人道术上限。宝宝换形态（神兽变身）时按新形态的数据表底数重算身板，
 * 跟着主人换图带走。
 */
final case class Summoning(level: Int, scMax: Int):
  def body(d: MonsterDef): PetBody = SpellRules.summonedBody(d, level, scMax)

/**
 * 技能的数值公式：威力、持续秒数、成功率、范围。出处 ref/Mir176 MagicManager.cs（配置取 GameSvrConf 默认值），
 * 野蛮冲撞出自 PlayObject.Base.cs DoMotaebo。
 * 只算数、不改状态；随机数经 Rng 传入（技能说明用「全取最小 / 全取最大」的 Rng 求精确上下限，#1165）。
 * 技能等级 level 为 0–3。
 */
object SpellRules:
  private def round(x: Double): Int = math.floor(x + 0.5).toInt

  // ---------------- 范围与时间（配置默认值） ----------------

  /** 魔法锁定范围：目标格离自己超过这么多格放不出（MagicAttackRage） */
  val CastRange = 8
  /** 放完一个魔法到能放下一个（毫秒），也是带冷却的魔法的最短冷却 */
  val SpellIntervalMs = 800
  /** 爆裂火焰、冰咆哮：目标周围几格（FireBoomRage / SnowWindRange） */
  val BlastRadius = 1
  /** 地狱雷光：自己周围几格（ElecBlizzardRange） */
  val ThunderStormRadius = 2
  /** 地狱火、疾光电影打多远 */
  val HellFireLength  = 5
  val LightningLength = 8
  /** 火墙每隔多久烧一次（FireBurnEvent.Run） */
  val FireWallTickMs = 3000
  /** 幽灵盾、神圣战甲术：目标周围几格的自己人（MagMakeDefenceArea 的 3） */
  val DefenceRadius = 3
  /** 群体治疗术、集体隐身术、困魔咒：目标周围几格 */
  val GroupRadius = 1
  /** 诱惑之光：收服的宝宝上限（MagTammingCount，召唤的不算）、怪物等级上限（MagTammingLevel） */
  val MaxPets         = 5
  val TameLevelLimit  = 50
  /** 召唤骷髅、召唤神兽召出的宝宝，同种各最多几只（原版 1 只，用户 11-24 在 #1176 定 3 只） */
  val SummonsEach     = 3
  val SkeletonPet     = MonsterName("变异骷髅")
  val ShinsuPet       = MonsterName("神兽")
  /** 神兽有仗打时变成的大神兽（原版 g_Config.sDragon1），没仗打 60 秒变回 ShinsuPet */
  val ShinsuWarrior   = MonsterName("神兽1")
  /** 大神兽连续多久没仗打变回小神兽（TElfWarriorMonster.Run 6 * 10 * 1000） */
  val ShinsuCalmMs    = 60_000
  /** 召唤出来的宝宝算哪一种召唤（同种计数用；大神兽算神兽），不是召唤的为 None */
  def summonedAs(pet: MonsterName): Option[MonsterName] =
    if pet == SkeletonPet then Some(SkeletonPet)
    else if pet == ShinsuPet || pet == ShinsuWarrior then Some(ShinsuPet)
    else None
  /** 是不是召唤出来的宝宝（不占诱惑之光的名额） */
  def isSummoned(pet: MonsterName): Boolean = summonedAs(pet).isDefined
  /** 圣言术：怪物等级上限（MagTurnUndeadLevel） */
  val TurnUndeadLevelLimit = 50

  // ---------------- 威力 ----------------

  /** 法师攻击魔法的威力：GetAttackPower(GetPower(MPow) + 魔法下限, 魔法上限 - 下限 + 1)（火球、雷电、地狱火、疾光电影、爆裂火焰、地狱雷光、冰咆哮、火墙每跳）。 */
  def mcDamage(d: SkillDef, level: Int, mc: Span)(using Rng): Int =
    Rules.attackPower(Rules.skillPower(Rules.magicBase(d.power), level, d.defPower) + mc.lo, mc.hi - mc.lo + 1)

  /** 灵魂火符的威力（道术）。 */
  def scDamage(d: SkillDef, level: Int, sc: Span)(using Rng): Int =
    Rules.attackPower(Rules.skillPower(Rules.magicBase(d.power), level, d.defPower) + sc.lo, sc.hi - sc.lo + 1)

  /** 治愈术、群体治疗术回多少血：GetAttackPower(GetPower(MPow) + 道术下限×2, (上限 - 下限)×2 + 1)。 */
  def heal(d: SkillDef, level: Int, sc: Span)(using Rng): Int =
    Rules.attackPower(Rules.skillPower(Rules.magicBase(d.power), level, d.defPower) + sc.lo * 2, (sc.hi - sc.lo) * 2 + 1)

  /**
   * 治愈术、群体治疗术不是一下回满，而是进目标的「待回治疗量」慢慢回（ObjBase.pas RM_MAGHEALING 与回复节拍）：
   * 放出后 HealingDelayMs 落到目标身上，累计不超过 HealingPoolCap；每拍回 HealingPerTick 点，
   * 一拍的间隔看目标等级（healingTickMs）；目标血满或死了，没回完的作废。
   */
  val HealingDelayMs = 800
  val HealingPoolCap = 300
  val HealingPerTick = 5

  /** 回复节拍：600 − min(400, 等级×10) 毫秒（20 级以上 200 毫秒）。 */
  def healingTickMs(level: Int): Int = 600 - math.min(400, level * 10)

  /** 待回治疗量再加一份：加完不到上限就累加，到了就是上限。 */
  def addHealing(pool: Int, amount: Int): Int = if pool + amount < HealingPoolCap then pool + amount else HealingPoolCap

  /** 雷电术、疾光电影打不死系 ×1.5。 */
  def vsUndead(power: Int): Int = round(power * 1.5)

  /** 地狱雷光：对不死系全额，其余只有十分之一（MagElecBlizzard）。 */
  def thunderStorm(power: Int, undead: Boolean): Int = if undead then power else power / 10

  // ---------------- 持续时间（秒） ----------------

  /** 火墙烧多久：GetPower(10) + 魔法随机值/2。 */
  def fireWallSecs(d: SkillDef, level: Int, mc: Span)(using Rng): Int =
    Rules.skillPower(10, level, d.defPower) + (Rules.rpow(mc) >> 1)

  /** 魔法盾持续：GetPower(魔法随机值 + 15)。 */
  def shieldSecs(d: SkillDef, level: Int, mc: Span)(using Rng): Int =
    Rules.skillPower(15 + Rules.rpow(mc), level, d.defPower)

  /** 幽灵盾、神圣战甲术持续：GetAttackPower(GetPower13(60) + 道术下限×10, 道术上限 - 下限 + 1)。 */
  def defenceUpSecs(d: SkillDef, level: Int, sc: Span)(using Rng): Int =
    Rules.attackPower(Rules.skillPower13(60, level, d.defPower) + sc.lo * 10, sc.hi - sc.lo + 1)

  /** 幽灵盾、神圣战甲术加多少（加在魔防/防御上限）：被加者等级/7 + 1（RecalcAbilitys）。 */
  def defenceUpAmount(targetLevel: Int): Int = targetLevel / 7 + 1

  /** 隐身术、集体隐身术持续：GetPower13(30) + 道术随机值×3。 */
  def invisibleSecs(d: SkillDef, level: Int, sc: Span)(using Rng): Int =
    Rules.skillPower13(30, level, d.defPower) + Rules.rpow(sc) * 3

  /** 困魔咒困住多久：GetPower13(40) + 道术随机值×3。 */
  def holyCurtainSecs(d: SkillDef, level: Int, sc: Span)(using Rng): Int =
    Rules.skillPower13(40, level, d.defPower) + Rules.rpow(sc) * 3

  /** 绿毒每隔多久掉一次血（毫秒） */
  val PoisonTickMs = 2000

  /**
   * 施毒术下一次毒（用户 11-24 在 #1212 定）：只掷一次道术随机值，持续时间和强度都由它和技能等级确定地算出，技能本身不随机。
   * - 持续秒数：绿毒 GetPower13(40)、红毒 GetPower13(30)（施毒术没有默认威力，按等级是定数），+ 道术随机值×2
   * - 绿毒每跳掉血：道术随机值÷2 + 技能等级 + 1
   * - 红毒挨打加深百分比：10 + 技能等级×5 + 道术随机值÷2
   */
  def poisonDose(d: SkillDef, level: Int, sc: Span, kind: PoisonKind)(using Rng): PoisonDose =
    val roll = Rules.rpow(sc)
    kind match
      case PoisonKind.Green => PoisonDose(Rules.skillPower13(40, level, d.defPower) + roll * 2, roll / 2 + level + 1)
      case PoisonKind.Red   => PoisonDose(Rules.skillPower13(30, level, d.defPower) + roll * 2, 10 + level * 5 + roll / 2)

  /** 中红毒挨打的伤害：加深 pct%（四舍五入）。 */
  def redPoisonDamage(damage: Int, pct: Int): Int = round(damage * (100 + pct) / 100.0)

  /** 心灵启示看穿多久：GetPower13(道术随机值×2 + 30)。 */
  def showHpSecs(d: SkillDef, level: Int, sc: Span)(using Rng): Int =
    Rules.skillPower13(Rules.rpow(sc) * 2 + 30, level, d.defPower)

  // ---------------- 成功率 ----------------

  /** 困魔咒能不能困住这只怪：Random(4) + (施法者等级 - 1) > 怪物等级。 */
  def holyCurtainCatches(casterLevel: Int, monsterLevel: Int)(using rng: Rng): Boolean =
    rng.random(4) + (casterLevel - 1) > monsterLevel

  /** 瞬息移动成功：Random(11) < 等级×2 + 4。 */
  def spaceMoveWorks(level: Int)(using rng: Rng): Boolean = rng.random(11) < level * 2 + 4

  /** 心灵启示成功：Random(6) <= 等级 + 3。 */
  def showHpWorks(level: Int)(using rng: Rng): Boolean = rng.random(6) <= level + 3

  /** 圣言术把不死系直接打死：Random(2) + (施法者等级 - 1) > 怪物等级，怪物等级 < 50，再 Random(100) < 等级×7 + 15 + 等级差。 */
  def turnUndeadKills(casterLevel: Int, monsterLevel: Int, level: Int)(using rng: Rng): Boolean =
    rng.random(2) + (casterLevel - 1) > monsterLevel &&
      monsterLevel < TurnUndeadLevelLimit &&
      rng.random(100) < level * 7 + 15 + (casterLevel - monsterLevel)

  /** 抗拒火环推开一个比自己等级低的对象：成功时推几格，失败为 None（MagPushArround）。 */
  def repelPush(casterLevel: Int, targetLevel: Int, level: Int)(using rng: Rng): Option[Int] =
    Option.when(casterLevel > targetLevel && rng.random(20) < 6 + level * 3 + (casterLevel - targetLevel)) {
      1 + math.max(0, level - 1) + rng.random(2)
    }

  // ---------------- 野蛮冲撞 ----------------

  /** 野蛮冲撞多久能放一次（DoMotaeboTick 的 3 秒） */
  val RushCooldownMs = 3000

  /** 一次最多冲几格：max(2, 技能等级 + 1)。 */
  def rushSteps(level: Int): Int = math.max(2, level + 1)

  /** 能不能把挡路的推开一格：比自己等级低，且 Random(20) < 技能等级×4 + 6 + 等级差（DoMotaeboCanMotaebo）。 */
  def rushPushes(casterLevel: Int, targetLevel: Int, level: Int)(using rng: Rng): Boolean =
    casterLevel > targetLevel && rng.random(20) < level * 4 + 6 + (casterLevel - targetLevel)

  /**
   * 撞到的对象挨多少（扣防御前）：Random((n+1)×10) + (n+1)×10。
   * n = 技能等级 + 1 - 推着它走的格数（不小于 0），推得越少撞得越狠。
   */
  def rushImpact(level: Int, pushedSteps: Int)(using rng: Rng): Int =
    val n = math.max(0, level + 1 - pushedSteps)
    rng.random((n + 1) * 10) + (n + 1) * 10

  /** 空冲撞墙、自己挨多少（扣防御前）：Random(n×10) + (n+1)×3，n = 技能等级 + 1。 */
  def rushCrash(level: Int)(using rng: Rng): Int =
    val n = level + 1
    rng.random(n * 10) + (n + 1) * 3

  // ---------------- 诱惑之光 ----------------

  /** 诱惑之光的对象：等级、最大血量、是不是不死系。 */
  final case class TameTarget(level: Int, maxHp: Int, undead: Boolean)

  /** 诱惑一次的结果。 */
  enum TameOutcome:
    /** 收为宝宝 */
    case Tamed
    /** 被定住若干秒（原版 HolySeize：不动不打，挨打解除） */
    case Seized(secs: Int)
    /** 发狂若干秒（原版 CrazyMode：见谁打谁） */
    case Crazy(secs: Int)
    /** 被诱惑之光打死（原版小概率） */
    case Killed
    /** 什么也没发生 */
    case NoEffect

  /**
   * 诱惑之光为什么一定不会成功（放之前就告诉玩家，不扣蓝）；None 表示有机会。
   * 原版这些情况也会走一遍流程、可能定住或惹怒怪物，这里改成直接提示，免得白放。
   */
  def tameBlocker(casterLevel: Int, t: TameTarget, pets: Int): Option[String] =
    if t.undead then Some("不死系的怪不能诱惑")
    else if t.level >= TameLevelLimit then Some(s"$TameLevelLimit 级以上的怪不能诱惑")
    else if t.level > casterLevel + 2 then Some(s"这只怪 ${t.level} 级，最多能诱惑 ${casterLevel + 2} 级的")
    else if pets >= MaxPets then Some(s"宝宝最多 $MaxPets 只")
    else None

  /**
   * 诱惑一只怪（MagTamming，不可诱惑的情况已由 tameBlocker 挡掉）：
   * 1. Random(4 - 技能等级) == 0 才起作用（3 级必起作用）；
   * 2. 一半机会只是定住 (等级×5 + 10) 秒；
   * 3. 另一半：Random(3) != 0 时惹怒（发狂 10–29 秒）；
   * 4. 再 Random(施法者等级 + 20 + 等级×5) > 怪物等级 + 10 才进入收服判定，否则 1/20 发狂；
   * 5. 收服：Random(n) == 0，n = 最大血量/100（≤2 取 2，否则翻倍）；失败时 1/14 被打死。
   */
  def tame(casterLevel: Int, level: Int, t: TameTarget)(using rng: Rng): TameOutcome =
    // 倍率：先按「原版收服概率 × Rates.tame」判一次；没收服再走原版流程决定定住/发狂/打死（流程里的收服改为没效果）
    if rng.random(1_000_000) < math.round(tameChance(casterLevel, level, t) * 1_000_000).toInt then TameOutcome.Tamed
    else
      tameOriginal(casterLevel, level, t) match
        case TameOutcome.Tamed => TameOutcome.NoEffect
        case other             => other

  /** 一次诱惑收服的概率（含倍率，最高 1）：原版各关相乘 × Rates.tame。一定不行的由 tameBlocker 挡。 */
  def tameChance(casterLevel: Int, level: Int, t: TameTarget): Double =
    val n0   = t.maxHp / 100
    val n    = if n0 <= 2 then 2 else n0 * 2
    val roll = casterLevel + 20 + level * 5
    val pass = math.max(0, roll - t.level - 11).toDouble / roll
    math.min(1.0, 1.0 / (4 - level) * 0.5 * (1.0 / 3) * pass / n * Rates.tame)

  /** 原版 MagTamming 的流程（技能说明用它算定住、发狂、打死的概率）。 */
  def tameOriginal(casterLevel: Int, level: Int, t: TameTarget)(using rng: Rng): TameOutcome =
    def crazy = TameOutcome.Crazy(rng.random(20) + 10)
    if rng.random(4 - level) != 0 then TameOutcome.NoEffect
    else if rng.random(2) != 0 then TameOutcome.Seized(level * 5 + 10)
    else if rng.random(3) != 0 then crazy
    else if rng.random(casterLevel + 20 + level * 5) <= t.level + 10 then
      if rng.random(20) == 0 then crazy else TameOutcome.NoEffect
    else
      val n0 = t.maxHp / 100
      val n  = if n0 <= 2 then 2 else n0 * 2
      if rng.random(n) == 0 then TameOutcome.Tamed
      else if rng.random(14) == 0 then TameOutcome.Killed
      else TameOutcome.NoEffect

  /** 魔法盾挡掉的伤害比例：技能 0–3 级 30%/40%/50%/60%（挨打至少还掉 1 点）。 */
  def shieldAbsorb(level: Int): Double = Vector(0.3, 0.4, 0.5, 0.6).lift(level).getOrElse(0.3)

  /** 召唤骷髅、神兽：血量和攻击 ×(1 + 技能等级×0.35)。 */
  def summonBoost(level: Int): Double = 1 + level * 0.35

  /**
   * 道术加成（#1209，本服加的）：召唤那一刻主人道术上限每点加千分之几，不封顶。
   * #1258 用户 11-25 定：从每点 2%、最多 +60% 改成每点 1.8%、取消上限，道术越高宝宝越强。
   */
  val SummonScPermillePerPoint = 18
  /** 技能说明用：每点加的百分数（「1.8」） */
  def summonScPctText: String = BigDecimal(SummonScPermillePerPoint) / 10 match
    case p if p.isWhole => p.toInt.toString
    case p              => p.toString

  /** 道术加成的倍数：1 + 道术上限×1.8%（一次除法，显示不出 1.5400000001 这种尾数）。 */
  def summonScBoost(scMax: Int): Double =
    (1000 + math.max(scMax, 0) * SummonScPermillePerPoint) / 1000.0

  /** 召唤出来的宝宝：技能等级加成乘血量和攻击，道术加成乘血量、攻击、防御、魔防。 */
  def summonedBody(base: MonsterDef, level: Int, scMax: Int): PetBody =
    val sc   = summonScBoost(scMax)
    val both = summonBoost(level) * sc
    PetBody(scaled(base.hp, both), scaled(base.dc, both), scaled(base.ac, sc), scaled(base.mac, sc))

  private def scaled(v: Int, k: Double): Int    = math.floor(v * k + 0.5).toInt
  private def scaled(s: Span, k: Double): Span = Span(scaled(s.lo, k), scaled(s.hi, k))

  /** 收服后宝宝走路、出手间隔的上限（毫秒）：1500 - 等级×200、2000 - 等级×200。 */
  def petWalkCap(level: Int): Int   = 1500 - level * 200
  def petAttackCap(level: Int): Int = 2000 - level * 200

  /** 神兽两种形态的走路、出手间隔（毫秒），level 是宝宝等级（召唤技能等级）：500 - 等级×50、1500 - 等级×100（ObjMon.pas ResetElfMon）。 */
  def shinsuWalkMs(level: Int): Int = 500 - level * 50
  def shinsuHitMs(level: Int): Int  = 1500 - level * 100
