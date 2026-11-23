package mir.client.ui

import mir.data.{ItemDef, SkillDef, Span}
import mir.model.{ItemInstance, Job, LearnedSkill, PoisonKind}
import mir.protocol.SelfStats
import mir.rules.{ItemEffects, ItemKind, Requirement, Rules, ScrollEffect}
import mir.world.Combat

/**
 * 把物品、属性、技能讲成人话（用户要求：属性增益要明确）。纯函数，只出文字。照 TS 版 ui/describe.ts。
 */
object Describe:
  private def r(v: Span): String  = if v.lo == v.hi then s"${v.lo}" else s"${v.lo}-${v.hi}"
  private def has(v: Span): Boolean = v.lo > 0 || v.hi > 0

  /** 一件装备的效果，逐项写成说明。 */
  def effectLines(e: ItemEffects): List[Line] =
    List(
      Option.when(has(e.dc))(Line(s"攻击 +${r(e.dc)}　（普通攻击和战士剑术的伤害）")),
      Option.when(has(e.mc))(Line(s"魔法 +${r(e.mc)}　（法师技能的伤害）")),
      Option.when(has(e.sc))(Line(s"道术 +${r(e.sc)}　（道士技能的伤害、治疗量和召唤物强度）")),
      Option.when(has(e.ac))(Line(s"防御 +${r(e.ac)}　（每次挨打减去这个范围里的随机值）")),
      Option.when(has(e.mac))(Line(s"魔防 +${r(e.mac)}　（魔法伤害减去这个范围里的随机值）")),
      Option.when(e.hit != 0)(Line(s"准确 +${e.hit}　（更不容易打空）")),
      Option.when(e.agility != 0)(Line(s"敏捷 +${e.agility}　（更容易躲开怪物的攻击）")),
      Option.when(e.attackSpeed > 0)(Line(s"攻击速度 +${e.attackSpeed}　（每 2 点让两刀之间快 60 毫秒）", Tint.Good)),
      Option.when(e.attackSpeed < 0)(Line(s"攻击速度 ${e.attackSpeed}　（出刀变慢）", Tint.Bad)),
      Option.when(e.luck != 0)(Line(s"幸运 +${e.luck}　（打出最大攻击的机会更高）", Tint.Good)),
      Option.when(e.curse != 0)(Line(s"诅咒 +${e.curse}　（打出最小攻击的机会更高）", Tint.Bad)),
      Option.when(e.magicAvoid != 0)(Line(s"魔法躲避 +${e.magicAvoid}　（有机会完全躲开魔法）")),
      Option.when(e.poisonAvoid != 0)(Line(s"毒物躲避 +${e.poisonAvoid}　（有机会不中毒）")),
      Option.when(e.hpRegen != 0)(Line(s"体力恢复 +${e.hpRegen}　（每 2 秒多回 ${e.hpRegen} 点血）")),
      Option.when(e.mpRegen != 0)(Line(s"魔法恢复 +${e.mpRegen}　（每 2 秒多回 ${e.mpRegen} 点蓝）")),
      Option.when(e.poisonRegen != 0)(Line(s"中毒恢复 +${e.poisonRegen}　（中毒时间缩短）"))
    ).flatten

  /** 物品说明（含与身上同部位装备的对比）。 */
  def itemLines(d: ItemDef, inst: Option[ItemInstance], job: Job, me: SelfStats, compareWith: Option[ItemDef]): List[Line] =
    val k = ItemKind.of(d)
    val body: List[Line] = k match
      case ItemKind.Potion(hp, mp, instant) =>
        val how = if instant then "（立即）" else "（几秒内慢慢回满）"
        List(Option.when(hp > 0)(Line(s"回复体力 $hp$how")), Option.when(mp > 0)(Line(s"回复魔法 $mp$how"))).flatten
      case ItemKind.Book(bookJob, level) =>
        val ok = bookJob.contains(job) && me.level >= level
        List(Line(s"${bookJob.fold("")(_.label)}技能书，$level 级可学", if ok then Tint.Plain else Tint.Bad))
      case ItemKind.Scroll(e) =>
        List(Line(e match
          case ScrollEffect.Town   => "回到城里"
          case ScrollEffect.Escape => "离开地下城回到城里"
          case ScrollEffect.Random => "随机传送到本地图某处"
          case ScrollEffect.Other  => "特殊卷轴"
        ))
      case ItemKind.Bundle(_) => List(Line("双击拆成 6 瓶（需要 6 个空位）"))
      case ItemKind.Amulet(poison) =>
        List(
          Some(Line(poison match
            case None                   => "道士施法消耗（灵魂火符、召唤、隐身）"
            case Some(PoisonKind.Green) => "施毒术用：绿毒，持续掉血"
            case Some(PoisonKind.Red)   => "施毒术用：红毒，降低防御"
          )),
          Some(Line("装备在左手镯位置使用", Tint.Dim)),
          inst.map(i => Line(s"剩余 ${math.ceil(i.dura / 100.0).toInt} 次", Tint.Dim))
        ).flatten
      case ItemKind.Equip(_) =>
        val e = ItemEffects.of(d)
        effectLines(e) ++ compareWith.filter(_.name != d.name).fold(Nil)(c => compareLines(e, ItemEffects.of(c), c.name.value))
      case ItemKind.Material | ItemKind.Other => Nil
    val need = k match
      case ItemKind.Equip(_) =>
        val req = Requirement.of(d)
        Option.when(req.value > 0) {
          val have = Requirement.current(req, me.level, me.stats)
          Line(s"需要${req.label} ${req.value}", if have >= req.value then Tint.Dim else Tint.Bad)
        }.toList
      case _ => Nil
    val dura = (inst, k) match
      case (Some(i), ItemKind.Equip(_)) => s"　持久 ${math.ceil(i.dura / 1000.0).toInt}/${math.ceil(i.duraMax / 1000.0).toInt}"
      case _                            => ""
    Line(d.name.value, Tint.Title) :: body ++ need ++ List(
      Line(s"重量 ${d.weight}$dura", Tint.Dim),
      Line(s"卖给商店约 ${d.price / 2} 金币", Tint.Gold)
    )

  private def compareLines(a: ItemEffects, b: ItemEffects, oldName: String): List[Line] =
    def sign(n: Int) = if n > 0 then s"+$n" else s"$n"
    def range(name: String, x: Span, y: Span): Option[Line] =
      val (d0, d1) = (x.lo - y.lo, x.hi - y.hi)
      Option.when(d0 != 0 || d1 != 0)(Line(s"$name ${sign(d0)}/${sign(d1)}", if d0 + d1 >= 0 then Tint.Good else Tint.Bad))
    def num(name: String, x: Int, y: Int): Option[Line] =
      val d = x - y
      Option.when(d != 0)(Line(s"$name ${sign(d)}", if d > 0 then Tint.Good else Tint.Bad))
    val diffs = List(
      range("攻击", a.dc, b.dc), range("魔法", a.mc, b.mc), range("道术", a.sc, b.sc), range("防御", a.ac, b.ac),
      range("魔防", a.mac, b.mac), num("准确", a.hit, b.hit), num("敏捷", a.agility, b.agility),
      num("攻速", a.attackSpeed, b.attackSpeed), num("幸运", a.luck - a.curse, b.luck - b.curse)
    ).flatten
    if diffs.isEmpty then Nil else Line(s"换下 $oldName 后：", Tint.Dim) :: diffs

  /** 属性面板：每项属性写出它实际意味着什么。用附近常见怪的敏捷、准确估算命中率。 */
  def statLines(name: String, job: Job, me: SelfStats, monsterAgility: Int, monsterHit: Int): List[Line] =
    val s       = me.stats
    val hitPct  = math.round(Rules.hitChance(s.hit, monsterAgility) * 100)
    val dodgePct = math.round((1 - Rules.hitChance(monsterHit, s.agility)) * 100)
    List(
      Some(Line(s"$name　${job.label}　${me.level} 级", Tint.Title)),
      Some(Line(s"体力 ${me.hp.value}/${s.maxHp}　魔法 ${me.mp.value}/${s.maxMp}")),
      Some(Line(s"攻击 ${r(s.dc)}：普通攻击每刀伤害在这之间随机，再减去怪物防御")),
      Option.when(has(s.mc))(Line(s"魔法 ${r(s.mc)}：法师技能威力 = 技能基础值 + 这个范围里的随机值")),
      Option.when(has(s.sc))(Line(s"道术 ${r(s.sc)}：治愈、火符威力和召唤物强度都按它算")),
      Some(Line(s"防御 ${r(s.ac)}：每次挨打先减掉这个范围里的随机值")),
      Some(Line(s"魔防 ${r(s.mac)}：魔法伤害先减掉这个范围里的随机值")),
      Some(Line(s"准确 ${s.hit}：打附近怪物（敏捷约 $monsterAgility）命中率约 $hitPct%")),
      Some(Line(s"敏捷 ${s.agility}：附近怪物（准确约 $monsterHit）打你约 $dodgePct% 打空")),
      Some(Line(s"攻击间隔 ${s.hitInterval} 毫秒（装备攻速每 2 点快 60 毫秒）")),
      Option.when(s.luck != 0)(Line(s"幸运 ${s.luck}")),
      Some(Line(s"负重：包裹 ${s.maxBagWeight}，穿戴 ${s.maxWearWeight}，腕力 ${s.maxHandWeight}（能拿的武器重量）", Tint.Dim))
    ).flatten

  /** 技能说明。 */
  def skillLines(d: SkillDef, ls: LearnedSkill): List[Line] =
    List(
      Line(s"${d.name}　${ls.level} 级　${d.job.label}", Tint.Title),
      Line(s"耗魔法 ${Combat.manaCost(d, ls.level)}"),
      Line(
        if ls.level < 3 then s"熟练度 ${ls.train}/${d.train(ls.level)}，人物 ${d.needLevel(ls.level)} 级后才能继续涨" else "已练满",
        Tint.Dim
      ),
      Line("点图标设置 F1-F8 快捷键", Tint.Dim)
    )
