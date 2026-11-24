package mir.client.ui

import mir.data.{Catalog, ItemDef, SkillDef, Span}
import mir.model.{EquipSlot, ItemBonus, ItemInstance, Job, LearnedSkill, PoisonKind}
import mir.protocol.SelfStats
import mir.rules.{Upgrade, SpellRules, Durability, Rates, ItemEffects, ItemKind, PotionRules, Pricing, Recovery, Requirement, Rules, ScrollEffect, dressSex}

/**
 * 把物品、属性、技能讲成可量化的说明（用户 #1165：所有效果给精确数值）。纯函数，只出文字。
 * 数值和规则一律从 shared 的规则代码取（Rules、Recovery、PotionRules、Durability、Pricing、ItemEffects），
 * 不在这里另抄一份：规则改了说明跟着变。
 */
object Describe:
  private def r(v: Span): String  = if v.lo == v.hi then s"${v.lo}" else s"${v.lo}-${v.hi}"
  private def has(v: Span): Boolean = v.lo > 0 || v.hi > 0
  private def pct(x: Double): String = s"${math.round(x * 100)}%"
  private def sec(ms: Int): String =
    if ms % 1000 == 0 then s"${ms / 1000} 秒" else s"${BigDecimal(ms) / 1000} 秒"
  private def signed(n: Int): String = if n >= 0 then s"+$n" else s"$n"

  private val notYet = "（本服暂未生效）"

  /** 一件装备的效果，逐项写成说明：加多少，以及这一项在规则里怎么起作用。 */
  def effectLines(e: ItemEffects): List[Line] =
    val step = Rules.HitIntervalStepMs
    List(
      Option.when(has(e.dc))(Line(s"攻击 +${r(e.dc)}　普攻每刀 = 攻击区间内随机 − 目标防御")),
      Option.when(has(e.mc))(Line(s"魔法 +${r(e.mc)}　法师攻击技能的伤害加成（见技能说明）")),
      Option.when(has(e.sc))(Line(s"道术 +${r(e.sc)}　道士技能的威力、治疗量、持续时间加成（见技能说明）")),
      Option.when(has(e.ac))(Line(s"防御 +${r(e.ac)}　挨物理攻击时伤害减去防御区间内随机值")),
      Option.when(has(e.mac))(Line(s"魔防 +${r(e.mac)}　挨魔法攻击时伤害减去魔防区间内随机值")),
      Option.when(e.hit != 0)(Line(s"准确 ${signed(e.hit)}　命中率 =（准确+1）÷ 目标敏捷")),
      Option.when(e.agility != 0)(Line(s"敏捷 ${signed(e.agility)}　被打中率 =（对方准确+1）÷ 你的敏捷")),
      Option.when(e.attackSpeed != 0)(Line(
        if e.attackSpeed > 0 then s"攻击速度 ${signed(e.attackSpeed)}　全身攻速合计每 2 点，两刀间隔 −$step 毫秒"
        else s"攻击速度 ${signed(e.attackSpeed)}　全身攻速合计为负时，从 −1 起每 2 点两刀间隔 +$step 毫秒",
        if e.attackSpeed > 0 then Tint.Good else Tint.Bad
      )),
      Option.when(e.luck != 0)(Line(s"幸运 +${e.luck}　全身幸运 L>0 时每刀有 1/(10−L) 机会打出攻击上限", Tint.Good)),
      Option.when(e.curse != 0)(Line(s"诅咒 +${e.curse}　抵消幸运；幸运为负时每刀有 1/(10−|L|) 机会只打出攻击下限", Tint.Bad)),
      Option.when(e.magicAvoid != 0)(Line(s"魔法躲避 +${e.magicAvoid}$notYet", Tint.Dim)),
      Option.when(e.poisonAvoid != 0)(Line(s"毒物躲避 +${e.poisonAvoid}$notYet", Tint.Dim)),
      Option.when(e.hpRegen != 0)(Line(s"体力恢复 +${e.hpRegen}　每 ${sec(Recovery.IntervalMs)}自然回血多 ${e.hpRegen} 点")),
      Option.when(e.mpRegen != 0)(Line(s"魔法恢复 +${e.mpRegen}　每 ${sec(Recovery.IntervalMs)}自然回蓝多 ${e.mpRegen} 点")),
      Option.when(e.poisonRegen != 0)(Line(s"中毒恢复 +${e.poisonRegen}$notYet", Tint.Dim))
    ).flatten

  private def scrollText(e: ScrollEffect): String = e match
    case ScrollEffect.Town   => "使用后立即回到回城点（角色出生的城）"
    case ScrollEffect.Escape => "使用后立即回到回城点（角色出生的城）"
    case ScrollEffect.Random => "传送到当前地图上随机一个能站的格子"
    case ScrollEffect.Other  => "暂时不能使用"

  private def potionLines(hp: Int, mp: Int, instant: Boolean): List[Line] =
    def what(f: Int => Int) =
      List(Option.when(hp > 0)(s"体力 ${f(hp)}"), Option.when(mp > 0)(s"魔法 ${f(mp)}")).flatten.mkString("、")
    if instant then List(Line(s"立即回复 ${what(identity)}"))
    else
      val (n, gap) = (PotionRules.Ticks, PotionRules.TickMs)
      List(
        Line(s"每 ${sec(gap)}回复 ${what(PotionRules.perTick)}，共 $n 次"),
        Line(s"${sec(gap * (n - 1))}内共回 ${what(v => PotionRules.perTick(v) * n)}，超过上限的部分浪费", Tint.Dim)
      )

  private def amuletLines(poison: Option[PoisonKind], inst: Option[ItemInstance]): List[Line] =
    List(
      Some(Line(poison match
        case None                   => "道士灵魂火符、召唤骷髅、召唤神兽、隐身术每次用 1 张"
        case Some(PoisonKind.Green) => "施毒术用：绿毒，中毒期间持续掉血（数值见施毒术说明）"
        case Some(PoisonKind.Red)   => s"施毒术用：红毒，中毒期间挨打伤害 ×${SpellRules.redPoisonDamage(100) / 100.0}（持续时间见施毒术说明）"
      )),
      Some(Line("放包裹里就能用（左手镯位戴着的先用）", Tint.Dim)),
      inst.map(i => Line(s"剩余 ${Durability.usesLeft(i.dura)} 次", Tint.Dim))
    ).flatten

  /** 穿上它要的东西：等级/属性要求、性别（不限重量）。 */
  private def wearLines(d: ItemDef, slots: List[EquipSlot], me: SelfStats): List[Line] =
    val s   = me.stats
    val req = Requirement.of(d)
    val need = Option.when(req.value > 0) {
      val have = Requirement.current(req, me.level, s)
      Line(s"需要${req.label} ${req.value}（你现在 $have）", if have >= req.value then Tint.Dim else Tint.Bad)
    }
    val sex = dressSex(d).map(x => Line(s"${x.label}穿", Tint.Dim))
    need.toList ++ sex

  private def strengthOf(inst: Option[ItemInstance]): Int =
    inst.flatMap(_.bonus).collect { case b: ItemBonus.Weapon => b.strength }.getOrElse(0)

  private def durabilityLines(slots: List[EquipSlot], inst: Option[ItemInstance]): List[Line] =
    inst.toList.flatMap { i =>
      val wear =
        if slots.contains(EquipSlot.Weapon) then
          val per = BigDecimal(Durability.WeaponWearPerHit) / Durability.PerPoint
          strengthOf(inst) match
            case 0 => s"每次砍中目标有 1/${Durability.WeaponWearOdds} 机会磨损 $per"
            case s => s"每次砍中目标有 ${pct(Durability.weaponWearChance(s))} 机会磨损 $per（强度 $s，普通武器 ${pct(Durability.weaponWearChance(0))}）"
        else "不会磨损"
      List(
        Line(s"持久 ${Durability.shown(i.dura)}/${Durability.shown(i.duraMax)}", if i.dura > 0 then Tint.Plain else Tint.Bad),
        Line(s"$wear；持久为 0 时不加任何属性", Tint.Dim)
      )
    }

  /**
   * 极品附加了哪几项（#1191）。原版客户端只显示加好后的总值（上面的效果行已含），
   * 这里再单列一行比同名普通件多了什么，不用自己对表。
   */
  private def bonusLines(d: ItemDef, inst: Option[ItemInstance]): List[Line] =
    inst.filter(_.special).toList.flatMap { i =>
      val parts = diffs(ItemEffects.of(d, i), ItemEffects.of(d)).map(_._1) ++
        Option.when(strengthOf(inst) > 0)(s"强度 +${strengthOf(inst)}")
      List(Line(s"极品：${parts.mkString("、")}（比普通的${d.name.value}）", Tint.Special))
    }

  /**
   * 物品说明（含与身上同部位装备的对比）。inst 为空是商店里的商品；compareWith 是身上同部位那件（表里的物品和它的极品附加）。
   */
  def itemLines(
      catalog: Catalog,
      d: ItemDef,
      inst: Option[ItemInstance],
      job: Job,
      me: SelfStats,
      compareWith: Option[(ItemDef, Option[ItemBonus])],
      upgrade: Option[Upgrade.Gain] = None
  ): List[Line] =
    val k = ItemKind.of(d)
    val body: List[Line] = k match
      case ItemKind.Potion(hp, mp, instant) => potionLines(hp, mp, instant)
      case ItemKind.Book(bookJob, level) =>
        val ok = bookJob.contains(job) && me.level >= level
        List(Line(s"技能书：${bookJob.fold("")(_.label)}人物 $level 级可学「${d.name.value}」", if ok then Tint.Plain else Tint.Bad))
      case ItemKind.Scroll(e) => List(Line(scrollText(e)))
      case ItemKind.Bundle(_) =>
        val one = catalog.bundleContent(d).fold("药")(x => s"「${x.name.value}」")
        List(Line(s"双击拆成 ${ItemKind.BundleSize} 个$one"), Line("并进包裹里同名的那叠，没有就占 1 格", Tint.Dim))
      case ItemKind.Amulet(poison) => amuletLines(poison, inst)
      case ItemKind.Equip(slots) =>
        val e = ItemEffects.of(d, inst.flatMap(_.bonus))
        Line(s"装备位置：${slots.map(_.label).mkString("或")}", Tint.Dim) :: effectLines(e) ++ bonusLines(d, inst) ++
          wearLines(d, slots, me) ++ durabilityLines(slots, inst) ++
          compareWith.fold(Nil)((c, cb) => compareLines(e, ItemEffects.of(c, cb), c.name.value))
      case ItemKind.Material | ItemKind.Other => Nil
    val titleText = if inst.exists(_.special) then s"${d.name.value}（极品）" else d.name.value
    val title = Line(titleText, if upgrade.isDefined then Tint.Upgrade else if inst.exists(_.special) then Tint.Special else Tint.Title)
    title :: upgradeLines(upgrade) ++ body ++ List(
      Line(s"商店收购价 ${Pricing.sell(d)} 金币", Tint.Gold)
    )

  /** 高级物品提醒（#1200）：换上它主属性净提升多少。 */
  private def upgradeLines(g: Option[Upgrade.Gain]): List[Line] =
    g.toList.map { u =>
      val vs = u.over.fold("该部位空着，穿上")(n => s"比身上的${n.value}")
      Line(s"$vs${u.stat}高 ${u.amount}　下限+上限合计", Tint.Upgrade)
    }

  /** a 比 b 多了什么：每项（文字，是不是往好的方向）。区间只有上限变的写成「攻击上限 +2」。 */
  private def diffs(a: ItemEffects, b: ItemEffects): List[(String, Boolean)] =
    def sign(n: Int) = if n > 0 then s"+$n" else s"$n"
    def range(name: String, x: Span, y: Span): Option[(String, Boolean)] =
      val (d0, d1) = (x.lo - y.lo, x.hi - y.hi)
      Option.when(d0 != 0 || d1 != 0)(
        (if d0 == 0 then s"${name}上限 ${sign(d1)}" else s"$name ${sign(d0)}/${sign(d1)}", d0 + d1 >= 0)
      )
    def num(name: String, x: Int, y: Int): Option[(String, Boolean)] =
      val d = x - y
      Option.when(d != 0)((s"$name ${sign(d)}", d > 0))
    List(
      range("攻击", a.dc, b.dc), range("魔法", a.mc, b.mc), range("道术", a.sc, b.sc), range("防御", a.ac, b.ac),
      range("魔防", a.mac, b.mac), num("准确", a.hit, b.hit), num("敏捷", a.agility, b.agility),
      num("攻速", a.attackSpeed, b.attackSpeed), num("幸运", a.luck - a.curse, b.luck - b.curse),
      num("魔法躲避", a.magicAvoid, b.magicAvoid), num("毒物躲避", a.poisonAvoid, b.poisonAvoid),
      num("体力恢复", a.hpRegen, b.hpRegen), num("魔法恢复", a.mpRegen, b.mpRegen),
      num("中毒恢复", a.poisonRegen, b.poisonRegen)
    ).flatten

  private def compareLines(a: ItemEffects, b: ItemEffects, oldName: String): List[Line] =
    val ds = diffs(a, b).map((t, good) => Line(t, if good then Tint.Good else Tint.Bad))
    if ds.isEmpty then Nil else Line(s"换下 $oldName 后：", Tint.Dim) :: ds

  /**
   * 属性面板：每项属性的当前值和它在规则里的具体作用。
   * 命中、闪避用附近怪物的平均敏捷 monsterAgility、平均准确 monsterHit 算出实际概率。
   */
  def statLines(name: String, job: Job, me: SelfStats, monsterAgility: Int, monsterHit: Int): List[Line] =
    val s     = me.stats
    val luck  = Rules.luckOdds(s.luck).map { n =>
      if s.luck > 0 then s"幸运 ${s.luck}：每刀有 1/$n（${pct(1.0 / n)}）机会直接打出攻击上限 ${s.dc.hi}"
      else s"幸运 ${s.luck}（诅咒）：每刀有 1/$n（${pct(1.0 / n)}）机会只打出攻击下限 ${s.dc.lo}"
    }
    List(
      Some(Line(s"$name　${job.label}　${me.level} 级", Tint.Title)),
      Some(Line(s"体力 ${me.hp.value}/${s.maxHp}：每 ${sec(Recovery.IntervalMs)}自然回 ${Recovery.hpPerTick(s)}")),
      Some(Line(s"魔法 ${me.mp.value}/${s.maxMp}：每 ${sec(Recovery.IntervalMs)}自然回 ${Recovery.mpPerTick(s)}")),
      Some(Line(s"攻击 ${r(s.dc)}：普攻每刀在 ${s.dc.lo}-${s.dc.hi} 间随机，再减目标防御，≤0 算打空")),
      luck.map(Line(_, if s.luck > 0 then Tint.Good else Tint.Bad)),
      Option.when(has(s.mc))(Line(s"魔法 ${r(s.mc)}：法师攻击技能的伤害加成（见技能说明）")),
      Option.when(has(s.sc))(Line(s"道术 ${r(s.sc)}：道士技能的威力、治疗量、持续时间加成（见技能说明）")),
      Some(Line(s"防御 ${r(s.ac)}：挨物理攻击时伤害减去 ${s.ac.lo}-${s.ac.hi} 间随机值")),
      Some(Line(s"魔防 ${r(s.mac)}：挨魔法攻击时伤害减去 ${s.mac.lo}-${s.mac.hi} 间随机值")),
      Option.when(job == Job.Warrior)(Line(s"本服战士加强：攻击、防御、魔防（含装备）都 ×${Rates.warriorBody}，上面已算进去", Tint.Good)),
      Some(Line(
        s"准确 ${s.hit}：命中率 =（${s.hit}+1）÷ 目标敏捷，打附近怪（敏捷约 $monsterAgility）${pct(Rules.hitChance(s.hit, monsterAgility))}"
      )),
      Some(Line(
        s"敏捷 ${s.agility}：被打中率 =（对方准确+1）÷ ${s.agility}，附近怪（准确约 $monsterHit）打中你 ${pct(Rules.hitChance(monsterHit, s.agility))}"
      )),
      Some(Line(
        s"攻击间隔 ${s.hitInterval} 毫秒：基础 ${Rules.hitInterval(0)}，全身攻速每 2 点 −${Rules.HitIntervalStepMs}，最快 ${Rules.HitIntervalMinMs}"
      )),
      Option.when(s.magicAvoid != 0)(Line(s"魔法躲避 ${s.magicAvoid}$notYet", Tint.Dim)),
      Option.when(s.poisonAvoid != 0)(Line(s"毒物躲避 ${s.poisonAvoid}$notYet", Tint.Dim)),
      Some(Line("包裹、穿戴、武器都不限重量，包裹只看格子", Tint.Dim))
    ).flatten

  /** 技能说明（每级精确数值，见 SkillInfo）。 */
  def skillLines(catalog: Catalog, d: SkillDef, ls: LearnedSkill, me: SelfStats): List[Line] = SkillInfo.lines(catalog, d, ls, me)
