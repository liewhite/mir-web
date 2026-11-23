package mir.rules

import mir.data.{SkillDef, Span}

/**
 * 法师、道士技能的数值公式：威力、持续秒数、成功率、范围。出处 ref/Mir176 MagicManager.cs（配置取 GameSvrConf 默认值）。
 * 只算数、不改状态；随机数经 Rng 传入（技能说明用「全取最小 / 全取最大」的 Rng 求精确上下限，#1165）。
 * 技能等级 level 为 0–3。
 */
object SpellRules:
  private def round(x: Double): Int = math.floor(x + 0.5).toInt

  // ---------------- 范围与时间（配置默认值） ----------------

  /** 魔法锁定范围：目标格离自己超过这么多格放不出（MagicAttackRage） */
  val CastRange = 8
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
  /** 诱惑之光：宝宝上限（MagTammingCount）、怪物等级上限（MagTammingLevel） */
  val MaxPets         = 5
  val TameLevelLimit  = 50
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

  /** 施毒术：持续秒数（绿毒 GetPower13(40)、红毒 GetPower13(30)，+ 道术随机值×2）。 */
  def poisonSecs(d: SkillDef, level: Int, sc: Span, green: Boolean)(using Rng): Int =
    Rules.skillPower13(if green then 40 else 30, level, d.defPower) + Rules.rpow(sc) * 2

  /** 红毒（黄色药粉）：中毒期间挨打的伤害 ×1.2（StruckDamage，PosionDamagarmor = 12）。 */
  def redPoisonDamage(damage: Int): Int = round(damage * 1.2)

  /** 施毒术绿毒每 2 秒掉的血。 */
  def poisonTick(sc: Span)(using Rng): Int = math.max(1, round(Rules.rpow(sc) / 2.0) + 1)

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

  /** 收服后宝宝走路、出手间隔的上限（毫秒）：1500 - 等级×200、2000 - 等级×200。 */
  def petWalkCap(level: Int): Int   = 1500 - level * 200
  def petAttackCap(level: Int): Int = 2000 - level * 200
