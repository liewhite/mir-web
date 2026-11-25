package mir.rules

import mir.core.{Exp, MonsterName}
import mir.data.{DropDef, ItemDef, SkillDef, Span}
import mir.model.Job

/** 爆率上怪分两档：普通怪和 boss（数据表没有这个标记，名单见 Rates.bosses）。 */
enum MonsterRank:
  case Common, Boss

/**
 * 倍率（用户 11-23 在 #1137 定：大幅降低升级难度、大幅提高爆率、每只怪能爆什么不变；爆率 11-24 在 #1241 改定）。
 * 原版数值在 game.json 里不动，只在这里换算。照 TS 版 game/src/world/rates.ts 移植。
 */
object Rates:
  /** 打怪经验倍数 */
  val exp: Int = 100
  /** 掉落概率倍数（所有怪、所有物品），最高 100% */
  val drop: Int = 20
  /** boss 掉高级装备（要求 20 级以上，或要求攻击/魔法/道术）的概率下限；普通怪没有保底（#1241） */
  val bossHighEquipMin: Double = 0.1
  val highEquipLevel: Int      = 20
  /**
   * 爆率算 boss 的怪（用户 11-24 在 #1241 定手写名单）：教主和王及其「暗之」版本；守卫、城墙不算。
   * 新加 boss 往这里补（TS 版 rates.ts 的 BOSSES 同步）。
   */
  val bosses: Set[MonsterName] = Set(
    "沃玛教主", "祖玛教主", "千年树妖", "石墓尸王", "牛魔王", "赤月恶魔", "双头金刚", "双头血魔", "黄泉教主", "虹魔教主",
    "暗之沃玛教主", "暗之牛魔王", "暗之双头金刚", "暗之双头血魔", "暗之黄泉教主", "暗之虹魔教主",
    "魔龙教主" // 1.80 魔龙血域（#1313）
  ).map(MonsterName(_))
  def rankOf(m: MonsterName): MonsterRank = if bosses(m) then MonsterRank.Boss else MonsterRank.Common
  /** boss 刷新间隔最长几分钟（用户 11-25 在 #1262 定：最长 2 小时）；刷怪表里更短的不变，普通怪不变 */
  val bossRespawnMaxMinutes: Int = 120

  /** 刷怪点实际的补怪间隔（分钟）：刷怪表的分钟数，boss 再封顶 bossRespawnMaxMinutes。 */
  def respawnMinutes(m: MonsterName, tableMinutes: Int): Int = rankOf(m) match
    case MonsterRank.Boss   => math.min(tableMinutes, bossRespawnMaxMinutes)
    case MonsterRank.Common => tableMinutes
  /** 怪掉的东西只给归属者和他的队友捡的时间（毫秒；用户 11-25 在 #1158 定 1 分钟，原版默认 2 分钟），见 Ward */
  val lootWardMs: Long = 60_000
  /** 召唤骷髅没仗打、往主人身边赶时每格最多多少毫秒（用户 11-25 在 #1275 要跟得上跑步的主人：和玩家跑步 2 格 600 毫秒一样快），打怪追怪照原版公式 SpellRules.skeletonWalkMs */
  val petCatchUpMs: Int = 300
  /** 打死一只怪最多掉几件物品（金币不算；一叠可叠物算一件；#1241），多了按 DropRules.cap 留最稀有的 */
  val maxDropsPerKill: Int = 10
  /** 诱惑之光收服概率倍数（用户 11-23 在 #1163 要求提升；原版 3 级技能对低级怪约 5%），最高 100% */
  val tame: Int = 10
  /** 战士的攻击、防御、魔防（上下限都乘，含装备加成，四舍五入）倍数（用户 11-24 在 #1176 定 1.5，11-25 在 #1285 提到 1.8） */
  val warriorBody: Double = 1.8
  /** 法师攻击魔法（火球、雷电、冰咆哮、火墙等）算出的伤害再乘的倍数，四舍五入；不动魔法面板和魔法盾（用户 11-25 在 #1285 定） */
  val mageSpell: Double = 1.3
  /**
   * 神兽（两种形态）的成长（用户 11-25 在 #1285 要削弱初始攻防魔防和成长）：数据表攻击、防御、魔防打 85 折，
   * 技能每级 +30%（骷髅 35%），道术上限每点加血 +1.6%、加攻击防御魔防 +1.2%（骷髅都是 1.8%；
   * 攻防那份用户 11-26 在 #1245 要求再降，从 1.6% 降到 1.2%）。
   */
  val shinsuGrowth: SummonGrowth = SummonGrowth(basePermille = 850, levelPermille = 300, hpScPermille = 16, scPermille = 12)
  /** 技能每级要的熟练度除以这个数（向上取整、至少 1；用户 11-24 在 #1179 定） */
  val skillTrain: Int = 10

  /** 技能从 level（0..2）升到 level+1 实际要的熟练度：数据表数值 ÷ skillTrain。 */
  def trainNeed(d: SkillDef, level: Int): Int = math.max(1, (d.train(level) + skillTrain - 1) / skillTrain)

  /** 职业倍率作用到一段属性区间上（只有战士有）。 */
  def jobSpan(job: Job, s: Span): Span = job match
    case Job.Warrior =>
      def scale(v: Int) = math.floor(v * warriorBody + 0.5).toInt
      Span(scale(s.lo), scale(s.hi))
    case _ => s

  /** 一行爆率实际的掉落概率（0..1）：原版 × drop；boss 的高级装备至少 bossHighEquipMin。item 为空表示金币。 */
  def dropChance(rank: MonsterRank, d: DropDef, item: Option[ItemDef]): Double =
    val scaled = math.min(1.0, d.numerator.toDouble / d.denominator * drop)
    rank match
      case MonsterRank.Boss if item.exists(isHighEquip) => math.max(scaled, bossHighEquipMin)
      case _                                           => scaled

  /** 打死一只怪给的经验：数据表经验 × 复活折扣（复活一次减半），取整后乘经验倍数。 */
  def killExp(baseExp: Int, scale: Double): Exp = Exp(math.floor(baseExp * scale).toLong * exp)

  def isHighEquip(d: ItemDef): Boolean = ItemKind.of(d) match
    case ItemKind.Equip(_) => d.need != 0 || d.needLevel >= highEquipLevel
    case _                 => false
