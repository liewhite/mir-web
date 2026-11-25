package mir.client.ui

import mir.core.{MonsterName, SkillId}
import mir.data.{Catalog, SkillDef}
import mir.model.{LearnedSkill, PoisonKind}
import mir.protocol.SelfStats
import mir.rules.{Odds, Rates, Rng, Skill, SkillUse, SpellRules, SummonGrowth, SwordRules}

import scala.collection.mutable

/**
 * 技能说明（#1165）：每级的具体数值。所有数都由 shared 的规则函数现算（SpellRules、SwordRules、Skill），
 * 不另抄公式：
 *   - 区间：同一个公式分别喂「每次取最小」「每次取最大」的随机数（Rng.lowest / Rng.highest）得出上下限；
 *   - 概率：Odds 把每种随机取值都走一遍，得出精确概率。
 * 用到人物当前的魔法、道术、攻击、等级，所以说明随装备和等级变。
 *
 * 排版（#1295）：一件事一行，每行尽量不超过说明框宽（约 26 个汉字），不靠自动折行；
 * 顺序是 标题 / 耗魔法与冷却 / 当前等级的效果（算法、附注用灰字）/ 各级对照（每级一行，当前级绿字）/ 熟练度 / 操作提示。
 */
object SkillInfo:
  private def lo(f: Rng => Int): Int = f(Rng.lowest)
  private def hi(f: Rng => Int): Int = f(Rng.highest)
  private def span(f: Rng => Int): String =
    val (a, b) = (lo(f), hi(f))
    if a == b then s"$a" else s"$a-$b"
  /** 百分比，最多一位小数（整数不带 .0）。 */
  private def pct(p: Double): String =
    val v = math.round(p * 1000) / 10.0
    if v == math.rint(v) then s"${v.toLong}%" else s"$v%"
  private def chance(f: Rng => Boolean): String = Odds.chance()(f).fold("未算出")(pct)
  private def sec(ms: Int): String = if ms % 1000 == 0 then s"${ms / 1000} 秒" else s"${BigDecimal(ms) / 1000} 秒"
  private def range(a: Int, b: Int): String = if a == b then s"$a" else s"$a-$b"

  /** 正文（白字）与附注（灰字：算法、限制、补充说明） */
  private def say(s: String): Line  = Line(s)
  private def note(s: String): Line = Line(s, Tint.Dim)

  private val Levels = 0 to 3

  /** 一个技能的说明：标题、耗蓝冷却、当前等级的效果、各级对照、熟练度。算一次要走很多随机分支，按输入缓存。 */
  def lines(catalog: Catalog, d: SkillDef, ls: LearnedSkill, me: SelfStats): List[Line] =
    cache.getOrElseUpdate((d.id, ls, me.level, me.stats), {
      if cache.size > 64 then cache.clear()
      build(catalog, d, ls, me)
    })

  private val cache = mutable.HashMap.empty[(SkillId, LearnedSkill, Int, mir.rules.Stats), List[Line]]

  private def build(catalog: Catalog, d: SkillDef, ls: LearnedSkill, me: SelfStats): List[Line] =
    val skill = Skill.of(d.id)
    val k     = Kit(catalog, d, me)
    val lv    = ls.level
    val title = Line(s"${d.name}　${lv} 级　${d.job.label}", Tint.Title)
    // 主动技能各级耗魔法不同时放进各级对照，这里只写当前级
    val perLevelMana = skill.map(_.use) match
      case Some(SkillUse.Passive) | Some(SkillUse.Toggle) => None
      case _ => Some(Levels.map(l => Skill.manaCost(d, l))).filter(_.distinct.size > 1)
    val cost = skill.map(_.use) match
      case Some(SkillUse.Passive) => note("被动技能：学会就一直生效，不用放")
      case Some(SkillUse.Toggle)  => note("开关技能：按一下开、再按一下关，开关不耗魔法")
      case _ =>
        val cd = skill.flatMap(_.cooldownMs(d)).fold("")(ms => s"，放完 ${sec(ms)}后才能再放")
        say(s"耗魔法 ${Skill.manaCost(d, lv)}$cd")
    val now = skill.fold(List(note("这个技能还没做，放不出来")))(k.effect(_, lv))
    val headline = skill.flatMap(k.headline)
    val table =
      if headline.isEmpty && perLevelMana.isEmpty then Nil
      else
        Levels.toList.map { l =>
          val parts = perLevelMana.map(mp => s"耗魔法 ${mp(l)}").toList ++ headline.map(_(l))
          Line(s"$l 级：${parts.mkString("，")}", if l == lv then Tint.Good else Tint.Dim)
        }
    val train =
      if lv < 3 then
        List(
          say(s"熟练度 ${ls.train}/${Rates.trainNeed(d, lv)}，升 ${lv + 1} 级要人物 ${d.needLevel(lv)} 级"),
          note(s"各级要熟练度 ${(0 to 2).map(Rates.trainNeed(d, _)).mkString("/")}（已按原版的 1/${Rates.skillTrain}）")
        )
      else List(note("已练满 3 级"))
    val key = Option.unless(Skill.isPassive(d.id))(note("点图标设置 F1-F8 快捷键；技能环上长按看说明"))
    title :: cost :: now ++ table ++ train ++ key

  /** 一个技能在某个等级的效果（effect），和放进各级对照的那个关键数（headline，没有就不列）。 */
  private final case class Kit(catalog: Catalog, d: SkillDef, me: SelfStats):
    private val s  = me.stats
    private val mc = s.mc
    private val sc = s.sc
    private val L  = me.level
    private val R  = SpellRules.CastRange

    private def mcDmg(l: Int)          = span(r => SpellRules.mcDamage(d, l, mc)(using r))
    private def mcVsUndead(l: Int)     = span(r => SpellRules.vsUndead(SpellRules.mcDamage(d, l, mc)(using r)))
    private def scDmg(l: Int)          = span(r => SpellRules.scDamage(d, l, sc)(using r))
    private def heal(l: Int)           = span(r => SpellRules.heal(d, l, sc)(using r))
    private def poisonSecs(l: Int, k: PoisonKind)  = span(r => SpellRules.poisonDose(d, l, sc, k)(using r).secs)
    private def poisonPower(l: Int, k: PoisonKind) = span(r => SpellRules.poisonDose(d, l, sc, k)(using r).power)
    private def stormOthers(l: Int)    = span(r => SpellRules.thunderStorm(SpellRules.mcDamage(d, l, mc)(using r), false))
    private val armor                  = "，再减目标魔防"
    /** 「选中的自己人周围 w×w 格」这类范围 */
    private def around(radius: Int)    = s"选中的自己人（没选就是自己）周围 ${radius * 2 + 1}×${radius * 2 + 1} 格"
    /** 治愈术、群体治疗术怎么回（SpellRules 的回血池规则） */
    private val healingNotes = List(
      note(s"不是一下回满：20 级以上每 ${SpellRules.healingTickMs(20) / 1000.0} 秒回 ${SpellRules.HealingPerTick} 点"),
      note(s"等级越低回得越慢（1 级 ${SpellRules.healingTickMs(1) / 1000.0} 秒回一次）"),
      note(s"连放几次会累加，最多攒 ${SpellRules.HealingPoolCap} 点；血满了就作废"),
      note("满血的目标放了不涨熟练度")
    )
    private def melee(f: Int => Int)   = range(f(s.dc.lo), f(s.dc.hi))

    /** 诱惑一次各结果的概率：先按 tameChance 收服，没收服再走原版流程（流程里的收服算没反应）。 */
    private def tameOdds(l: Int): Option[Map[String, Double]] =
      val t    = SpellRules.TameTarget(L, 200, undead = false)
      val p    = SpellRules.tameChance(L, l, t)
      val rest = Odds.of()(r => SpellRules.tameOriginal(L, l, t)(using r) match
        case SpellRules.TameOutcome.Tamed => SpellRules.TameOutcome.NoEffect
        case o                            => o
      )
      rest.map(m => (m.view.mapValues(_ * (1 - p)).toMap + (SpellRules.TameOutcome.Tamed -> p)).toList.groupMapReduce {
        case (SpellRules.TameOutcome.Tamed, _)        => "收服"
        case (SpellRules.TameOutcome.Seized(secs), _) => s"定住 $secs 秒"
        case (SpellRules.TameOutcome.Crazy(_), _)     => "发狂"
        case (SpellRules.TameOutcome.Killed, _)       => "打死"
        case (SpellRules.TameOutcome.NoEffect, _)     => "没反应"
      }(_._2)(_ + _))

    private def tamePct(l: Int, what: String) = tameOdds(l).fold("未算出")(m => pct(m.getOrElse(what, 0.0)))

    private def repelOdds(l: Int, below: Int) = chance(r => SpellRules.repelPush(L, L - below, l)(using r).isDefined)
    private def turnUndead(l: Int, below: Int) = chance(r => SpellRules.turnUndeadKills(L, L - below, l)(using r))

    private def summonHead: List[Line] = List(
      say("用 1 张护身符召唤一只"),
      note(s"同时最多 ${SpellRules.SummonsEach} 只，不占诱惑之光的名额")
    )

    /** 召唤出来的身板：一行现值、一行基础值（灰字）。 */
    private def petBody(name: String, l: Int): List[Line] =
      catalog.monster(MonsterName(name)).fold(List(say(name))) { md =>
        val b = SpellRules.summonedBody(md, l, sc.hi)
        List(
          say(s"$name：血 ${b.hp}，攻击 ${range(b.dc.lo, b.dc.hi)}，防御 ${range(b.ac.lo, b.ac.hi)}，魔防 ${range(b.mac.lo, b.mac.hi)}"),
          note(s"　基础：血 ${md.hp}，攻击 ${range(md.dc.lo, md.dc.hi)}，防御 ${range(md.ac.lo, md.ac.hi)}，魔防 ${range(md.mac.lo, md.mac.hi)}")
        )
      }

    /** 身板怎么从基础值算出来；技能等级的血、攻击加成在各级对照里（手机上说明框放不了太多行，不重复写）。 */
    private def petRule(g: SummonGrowth): List[Line] =
      List(
        note("道术加成（召唤时定下，之后换装不变）："),
        note(s"　每点道术上限 +${g.scPctText}%，不封顶"),
        note(s"　你现在 ${sc.hi}：血、攻击、防御、魔防 ×${g.scBoost(sc.hi)}")
      ) ++ g.baseText.map(k => note(s"本服攻击、防御、魔防先 ×$k")).toList

    def headline(skill: Skill): Option[Int => String] = skill match
      case Skill.FireBall | Skill.GreatFireBall | Skill.HellFire | Skill.FireBang | Skill.IceStorm => Some(l => s"威力 ${mcDmg(l)}")
      case Skill.Lightning | Skill.Thunder             => Some(l => s"威力 ${mcDmg(l)}（不死系 ${mcVsUndead(l)}）")
      case Skill.ThunderStorm => Some(l => s"不死系 ${mcDmg(l)}，其他 ${stormOthers(l)}")
      case Skill.FireWall => Some(l => s"每跳 ${mcDmg(l)}，烧 ${span(r => SpellRules.fireWallSecs(d, l, mc)(using r))} 秒")
      case Skill.Repel  => Some(l => s"推开低 1 级的 ${repelOdds(l, 1)}")
      case Skill.SpaceMove => Some(l => s"成功 ${chance(r => SpellRules.spaceMoveWorks(l)(using r))}")
      case Skill.MagicShield => Some(l => s"挡 ${pct(SpellRules.shieldAbsorb(l))}，${span(r => SpellRules.shieldSecs(d, l, mc)(using r))} 秒")
      case Skill.Tame => Some(l => s"收服 ${tamePct(l, "收服")}")
      case Skill.Healing | Skill.GroupHealing => Some(l => s"回血共 ${heal(l)}")
      case Skill.SoulFireBall => Some(l => s"威力 ${scDmg(l)}")
      case Skill.Poisoning  => Some(l => s"绿毒 ${poisonSecs(l, PoisonKind.Green)} 秒，红毒 ${poisonSecs(l, PoisonKind.Red)} 秒")
      case Skill.SoulShield | Skill.HolyArmor => Some(l => s"${span(r => SpellRules.defenceUpSecs(d, l, sc)(using r))} 秒")
      case Skill.HolyCurtain => Some(l => s"定住 ${span(r => SpellRules.holyCurtainSecs(d, l, sc)(using r))} 秒")
      case Skill.SummonSkeleton => Some(l => s"血、攻击 ×${SpellRules.growthOf(SpellRules.SkeletonPet).levelBoost(l)}")
      case Skill.SummonShinsu   => Some(l => s"血、攻击 ×${SpellRules.growthOf(SpellRules.ShinsuPet).levelBoost(l)}")
      case Skill.Invisible | Skill.GroupInvisible => Some(l => s"隐身 ${span(r => SpellRules.invisibleSecs(d, l, sc)(using r))} 秒")
      case Skill.ShowHp => Some(l => s"成功 ${chance(r => SpellRules.showHpWorks(l)(using r))}")
      case Skill.TurnUndead => Some(l => s"低 2 级的成功 ${turnUndead(l, 2)}")
      case Skill.OneSword  => Some(l => s"准确 +${SwordRules.oneSwordHit(l)}")
      case Skill.SpiritSword  => Some(l => s"准确 +${SwordRules.spiritSwordHit(l)}")
      case Skill.PowerHit  => Some(l => s"每 ${SwordRules.powerHitEvery(l)} 刀一次，威力 +${SwordRules.powerHitPower(0, l)}")
      case Skill.Rush => Some(l => s"冲 ${SpellRules.rushSteps(l)} 格，撞 ${span(r => SpellRules.rushImpact(l, 0)(using r))}")
      case Skill.WideHit => Some(l => s"旁边 ${melee(SwordRules.widePower(_, l))}")
      case Skill.FireSword => Some(l => s"威力 ×${SwordRules.firePower(100, l) / 100.0}，你是 ${melee(SwordRules.firePower(_, l))}")
      case Skill.LongHit => Some(l => s"隔一格 ${pct(SwordRules.longPower(100, l) / 100.0)}")

    def effect(skill: Skill, l: Int): List[Line] = skill match
      case Skill.FireBall | Skill.GreatFireBall =>
        List(say(s"打一个目标，最远 $R 格"), say(s"威力 ${mcDmg(l)}$armor"), note(s"威力 = 技能基础 + 你的魔法 ${range(mc.lo, mc.hi)}"))
      case Skill.Thunder =>
        List(
          say(s"打一个目标，最远 $R 格"),
          say(s"威力 ${mcDmg(l)}$armor"),
          say(s"打不死系（骷髅、僵尸等）×1.5：${mcVsUndead(l)}")
        )
      case Skill.HellFire =>
        List(say(s"朝目标方向直线 ${SpellRules.HellFireLength} 格"), say(s"每个敌人威力 ${mcDmg(l)}$armor"))
      case Skill.Lightning =>
        List(
          say(s"朝目标方向直线 ${SpellRules.LightningLength} 格"),
          say(s"每个敌人威力 ${mcDmg(l)}$armor"),
          say(s"打不死系 ×1.5：${mcVsUndead(l)}")
        )
      case Skill.FireBang | Skill.IceStorm =>
        val w = SpellRules.BlastRadius * 2 + 1
        List(say(s"目标周围 ${w}×$w 格，最远 $R 格"), say(s"每个敌人威力 ${mcDmg(l)}$armor"))
      case Skill.ThunderStorm =>
        val w = SpellRules.ThunderStormRadius * 2 + 1
        List(
          say(s"打自己周围 ${w}×$w 格内的敌人"),
          say(s"不死系威力 ${mcDmg(l)}"),
          say(s"其他只有十分之一：${stormOthers(l)}"),
          note("都再减目标魔防")
        )
      case Skill.FireWall =>
        List(
          say(s"目标格和上下左右共 5 格起火"),
          say(s"持续 ${span(r => SpellRules.fireWallSecs(d, l, mc)(using r))} 秒，每 ${sec(SpellRules.FireWallTickMs)}烧一次火里的敌人"),
          say(s"威力 ${mcDmg(l)}$armor"),
          note("威力在放的那一下定好")
        )
      case Skill.Repel =>
        val cells = Odds.of()(r => SpellRules.repelPush(L, L - 1, l)(using r)).toList.flatMap(_.keys.flatten)
        List(
          Some(say("把身边一圈比你等级低的敌人往外推")),
          Some(note("同级或更高的推不动")),
          Option.when(cells.nonEmpty)(say(s"推 ${range(cells.min, cells.max)} 格，被挡住就停")),
          Some(say(s"成功率：低 1 级 ${repelOdds(l, 1)}，低 5 级 ${repelOdds(l, 5)}，低 10 级 ${repelOdds(l, 10)}"))
        ).flatten
      case Skill.SpaceMove =>
        List(
          say(s"成功率 ${chance(r => SpellRules.spaceMoveWorks(l)(using r))}"),
          say("成功后传到当前地图的随机位置"),
          note("部分地图不能用")
        )
      case Skill.MagicShield =>
        List(
          say(s"持续 ${span(r => SpellRules.shieldSecs(d, l, mc)(using r))} 秒"),
          say(s"期间挨打的伤害少 ${pct(SpellRules.shieldAbsorb(l))}"),
          note("每下至少掉 1 点")
        )
      case Skill.Tame =>
        def tame(level: Int, hp: Int) = pct(SpellRules.tameChance(L, l, SpellRules.TameTarget(level, hp, false)))
        val odds = tameOdds(l).fold(List(note("概率未算出"))) { m =>
          val main   = List("收服", "发狂", "打死").map(k => s"$k ${pct(m.getOrElse(k, 0))}")
          val seized = m.toList.collect { case (k, p) if k.startsWith("定住") => s"$k ${pct(p)}" }.sorted
          List(say(s"　${main.mkString("，")}")) ++ Option.when(seized.nonEmpty)(say(s"　${seized.mkString("，")}"))
        }
        List(
          say(s"诱惑一只怪当宝宝，最远 $R 格"),
          note(s"最多 ${SpellRules.MaxPets} 只（召唤的骷髅、神兽不算）"),
          note(s"诱惑不了：比你高 2 级以上、${SpellRules.TameLevelLimit} 级以上、不死系"),
          say("对同级、血 300 以下的怪，每放一次：")
        ) ++ odds ++ List(
          say("血越多越难收（同级的怪）："),
          say(s"　血 500 收服 ${tame(L, 500)}，血 1000 收服 ${tame(L, 1000)}"),
          say(s"比你低 10 级、血 300 以下：收服 ${tame(L - 10, 200)}")
        )
      case Skill.Healing =>
        List(
          say("给选中的自己人回血（没选就是自己）"),
          say(s"慢慢回，共 ${heal(l)}"),
          note(s"= 技能基础 + 道术 ${range(sc.lo, sc.hi)} ×2")
        ) ++ healingNotes
      case Skill.GroupHealing =>
        List(say(around(SpellRules.GroupRadius)), say(s"每个自己人慢慢回血，共 ${heal(l)}")) ++ healingNotes
      case Skill.Poisoning =>
        List(
          say("要灰色药粉（绿毒）或黄色药粉（红毒）"),
          note("放包裹里就行，每次用掉 1 次；两种都带着时红绿交替下"),
          say(s"绿毒：持续 ${poisonSecs(l, PoisonKind.Green)} 秒，每 ${SpellRules.PoisonTickMs / 1000} 秒掉 ${poisonPower(l, PoisonKind.Green)} 血"),
          say(s"红毒：持续 ${poisonSecs(l, PoisonKind.Red)} 秒，挨打伤害加深 ${poisonPower(l, PoisonKind.Red)}%"),
          note(s"道术 ${range(sc.lo, sc.hi)} 里随机取值，和技能等级一起定下时长与强度"),
          note("已中同种毒再下：强度取较大的，时长重新算")
        )
      case Skill.SoulFireBall =>
        List(
          say(s"用 1 张护身符，打一个目标，最远 $R 格"),
          say(s"威力 ${scDmg(l)}$armor"),
          note(s"威力 = 技能基础 + 道术 ${range(sc.lo, sc.hi)}")
        )
      case Skill.SoulShield | Skill.HolyArmor =>
        val what = if skill == Skill.SoulShield then "魔防" else "防御"
        List(
          say("用 1 张护身符"),
          say(around(SpellRules.DefenceRadius)),
          say(s"每个自己人${what}上限 +（等级÷7+1），你是 +${SpellRules.defenceUpAmount(L)}"),
          say(s"持续 ${span(r => SpellRules.defenceUpSecs(d, l, sc)(using r))} 秒")
        )
      case Skill.HolyCurtain =>
        val w = SpellRules.GroupRadius * 2 + 1
        def at(k: Int) = chance(r => SpellRules.holyCurtainCatches(L, L - 2 + k)(using r))
        List(
          say(s"用 1 张护身符，定住目标周围 ${w}×$w 格的怪"),
          say(s"定住 ${span(r => SpellRules.holyCurtainSecs(d, l, sc)(using r))} 秒，挨打会醒"),
          say(s"按怪的等级：≤${L - 2} 级 ${at(0)}，${L - 1} 级 ${at(1)}，$L 级 ${at(2)}"),
          say(s"　${L + 1} 级 ${at(3)}，${L + 2} 级 ${at(4)}")
        )
      case Skill.SummonSkeleton =>
        val pet = SpellRules.SkeletonPet
        summonHead ++ petBody(pet.value, l) ++ petRule(SpellRules.growthOf(pet))
      case Skill.SummonShinsu =>
        val big  = SpellRules.ShinsuWarrior.value
        val calm = SpellRules.ShinsuCalmMs / 1000
        summonHead ++ List(
          say("平时是小神兽，跟着你，不打架"),
          say("它或你有仗打（你在打怪、刚被人打）时"),
          say(s"　原地变成$big，朝前方 2 格喷火"),
          say(s"　魔法伤害按魔防减，每 ${SpellRules.shinsuHitMs(l) / 1000.0} 秒喷一次"),
          say(s"$calm 秒没仗打变回小神兽")
        ) ++ petBody(SpellRules.ShinsuPet.value, l) ++ petBody(big, l) ++ petRule(SpellRules.growthOf(SpellRules.ShinsuPet))
      case Skill.Invisible =>
        List(
          say(s"用 1 张护身符隐身 ${span(r => SpellRules.invisibleSecs(d, l, sc)(using r))} 秒，怪物看不见你"),
          note("打怪或放别的技能就现身")
        )
      case Skill.GroupInvisible =>
        List(
          say("用 1 张护身符"),
          say(around(SpellRules.GroupRadius)),
          say(s"每个自己人隐身 ${span(r => SpellRules.invisibleSecs(d, l, sc)(using r))} 秒")
        )
      case Skill.ShowHp =>
        List(
          say(s"成功率 ${chance(r => SpellRules.showHpWorks(l)(using r))}"),
          say(s"看穿目标血量 ${span(r => SpellRules.showHpSecs(d, l, sc)(using r))} 秒")
        )
      case Skill.TurnUndead =>
        List(
          say(s"只对不死系、${SpellRules.TurnUndeadLevelLimit} 级以下的怪，最远 $R 格"),
          say("成功就一下打死"),
          say(s"成功率：比你低 1 级 ${turnUndead(l, 1)}，低 2 级 ${turnUndead(l, 2)}"),
          say(s"　低 5 级 ${turnUndead(l, 5)}，低 10 级 ${turnUndead(l, 10)}")
        )
      case Skill.OneSword    => List(say(s"准确 +${SwordRules.oneSwordHit(l)}"))
      case Skill.SpiritSword => List(say(s"准确 +${SwordRules.spiritSwordHit(l)}"))
      case Skill.PowerHit =>
        List(
          say(s"准确 +${SwordRules.powerHitHit(l)}"),
          say(s"拿武器每砍 ${SwordRules.powerHitEvery(l)} 刀，随机一刀是攻杀"),
          say(s"攻杀威力 +${SwordRules.powerHitPower(0, l)}，你是 ${melee(SwordRules.powerHitPower(_, l))}，再减防御")
        )
      case Skill.LongHit =>
        List(
          say("开着时，普攻同时打到正前方隔一格的敌人"),
          say(s"那一下威力是这一刀的 ${pct(SwordRules.longPower(100, l) / 100.0)}，你是 ${melee(SwordRules.longPower(_, l))}"),
          note("不扣对方防御，但可能被躲开")
        )
      case Skill.WideHit =>
        List(
          say(s"开着时每刀耗魔法 ${SwordRules.WideHitMp}，魔法不够就是普通一刀"),
          say("正前方照常打"),
          say(s"身边另外 7 格的敌人：这一刀 ×${l + 1}/4 + 1"),
          say(s"你是 ${melee(SwordRules.widePower(_, l))}，再减防御")
        )
      case Skill.FireSword =>
        List(
          say(s"放后下一刀砍敌人时威力 ×${SwordRules.firePower(100, l) / 100.0}"),
          say(s"你是 ${melee(SwordRules.firePower(_, l))}，再减防御")
        )
      case Skill.Rush =>
        def push(below: Int) = chance(r => SpellRules.rushPushes(L, L - below, l)(using r))
        List(
          say(s"朝目标（没有就朝面向）最多冲 ${SpellRules.rushSteps(l)} 格"),
          say("把挡路的、比你等级低的推着走"),
          note("同级或更高的推不动"),
          say(s"每格推得动：低 1 级 ${push(1)}，低 5 级 ${push(5)}，低 10 级 ${push(10)}"),
          say(s"撞到的挨 ${span(r => SpellRules.rushImpact(l, 0)(using r))}，再减防御"),
          note(s"　一格没推动时最重；推着走了 1 格是 ${span(r => SpellRules.rushImpact(l, 1)(using r))}"),
          say(s"冲劲没用完就撞墙：自己挨 ${span(r => SpellRules.rushCrash(l)(using r))}，再减防御")
        )
