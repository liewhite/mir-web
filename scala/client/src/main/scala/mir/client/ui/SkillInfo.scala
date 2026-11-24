package mir.client.ui

import mir.core.{MonsterName, SkillId}
import mir.data.{Catalog, SkillDef}
import mir.model.LearnedSkill
import mir.protocol.SelfStats
import mir.rules.{Odds, Rates, Rng, Skill, SkillUse, SpellRules, SwordRules}

import scala.collection.mutable

/**
 * 技能说明（#1165）：每级的具体数值。所有数都由 shared 的规则函数现算（SpellRules、SwordRules、Skill），
 * 不另抄公式：
 *   - 区间：同一个公式分别喂「每次取最小」「每次取最大」的随机数（Rng.lowest / Rng.highest）得出上下限；
 *   - 概率：Odds 把每种随机取值都走一遍，得出精确概率。
 * 用到人物当前的魔法、道术、攻击、等级，所以说明随装备和等级变。
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
    val cost  = skill.map(_.use) match
      case Some(SkillUse.Passive) => Line("被动技能：学会就一直生效，不用放", Tint.Dim)
      case Some(SkillUse.Toggle)  => Line("开关技能：按一下开、再按一下关，开关本身不耗魔法", Tint.Dim)
      case _ =>
        val mp = Levels.map(l => Skill.manaCost(d, l))
        val cd = skill.flatMap(_.cooldownMs(d)).fold("")(ms => s"，放完 ${sec(ms)}后才能再放")
        Line(s"耗魔法 ${Skill.manaCost(d, lv)}${if mp.distinct.size > 1 then s"（各级 ${mp.mkString("/")}）" else ""}$cd")
    val now   = skill.fold(List("这个技能还没做，放不出来"))(k.effect(_, lv)).map(Line(_))
    val table = skill.flatMap(k.headline).map(h => Line(s"各级：${Levels.map(l => s"$l 级 ${h(l)}").mkString("｜")}", Tint.Dim)).toList
    val train = Line(
      if lv < 3 then s"熟练度 ${ls.train}/${Rates.trainNeed(d, lv)}；升 ${lv + 1} 级要人物 ${d.needLevel(lv)} 级（各级熟练度 ${(0 to 2).map(Rates.trainNeed(d, _)).mkString("/")}，已按原版 1/${Rates.skillTrain} 算）"
      else "已练满 3 级",
      Tint.Dim
    )
    val key = Option.unless(Skill.isPassive(d.id))(Line("点图标设置 F1-F8 快捷键；技能环上长按看说明", Tint.Dim))
    title :: cost :: now ++ table ++ List(train) ++ key

  /** 一个技能在某个等级的效果文字（effect），和放进「各级」对照的那个关键数（headline，没有就不列）。 */
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
    private val armor                  = "，再减目标魔防"
    private def melee(f: Int => Int)   = if s.dc.lo == s.dc.hi then s"${f(s.dc.lo)}" else s"${f(s.dc.lo)}-${f(s.dc.hi)}"

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

    private def petLine(name: String, l: Int): String =
      catalog.monster(MonsterName(name)).fold(name) { md =>
        val b = SpellRules.summonBoost(l)
        def x(v: Int) = math.floor(v * b + 0.5).toInt
        s"$name 血 ${x(md.hp)}、攻击 ${x(md.dc.lo)}-${x(md.dc.hi)}（基础 ${md.hp}、${md.dc.lo}-${md.dc.hi} ×$b）"
      }

    def headline(skill: Skill): Option[Int => String] = skill match
      case Skill.FireBall | Skill.GreatFireBall | Skill.HellFire | Skill.FireBang | Skill.IceStorm => Some(l => s"威力 ${mcDmg(l)}")
      case Skill.Lightning | Skill.Thunder             => Some(l => s"威力 ${mcDmg(l)}（不死系 ${mcVsUndead(l)}）")
      case Skill.ThunderStorm => Some(l => s"不死系 ${mcDmg(l)}，其他 ${span(r => SpellRules.thunderStorm(SpellRules.mcDamage(d, l, mc)(using r), false))}")
      case Skill.FireWall => Some(l => s"每跳 ${mcDmg(l)}，烧 ${span(r => SpellRules.fireWallSecs(d, l, mc)(using r))} 秒")
      case Skill.Repel  => Some(l => s"推开低 1 级的 ${repelOdds(l, 1)}")
      case Skill.SpaceMove => Some(l => s"成功 ${chance(r => SpellRules.spaceMoveWorks(l)(using r))}")
      case Skill.MagicShield => Some(l => s"挡 ${pct(SpellRules.shieldAbsorb(l))}，${span(r => SpellRules.shieldSecs(d, l, mc)(using r))} 秒")
      case Skill.Tame => Some(l => s"收服 ${tamePct(l, "收服")}")
      case Skill.Healing | Skill.GroupHealing => Some(l => s"回血 ${heal(l)}")
      case Skill.SoulFireBall => Some(l => s"威力 ${scDmg(l)}")
      case Skill.Poisoning  => Some(l => s"绿毒 ${span(r => SpellRules.poisonSecs(d, l, sc, green = true)(using r))} 秒、红毒 ${span(r => SpellRules.poisonSecs(d, l, sc, green = false)(using r))} 秒")
      case Skill.SoulShield | Skill.HolyArmor => Some(l => s"${span(r => SpellRules.defenceUpSecs(d, l, sc)(using r))} 秒")
      case Skill.HolyCurtain => Some(l => s"${span(r => SpellRules.holyCurtainSecs(d, l, sc)(using r))} 秒")
      case Skill.SummonSkeleton | Skill.SummonShinsu => Some(l => s"×${SpellRules.summonBoost(l)}")
      case Skill.Invisible | Skill.GroupInvisible => Some(l => s"${span(r => SpellRules.invisibleSecs(d, l, sc)(using r))} 秒")
      case Skill.ShowHp => Some(l => s"成功 ${chance(r => SpellRules.showHpWorks(l)(using r))}")
      case Skill.TurnUndead => Some(l => s"低 2 级的 ${turnUndead(l, 2)}")
      case Skill.OneSword  => Some(l => s"准确 +${SwordRules.oneSwordHit(l)}")
      case Skill.SpiritSword  => Some(l => s"准确 +${SwordRules.spiritSwordHit(l)}")
      case Skill.PowerHit  => Some(l => s"每 ${SwordRules.powerHitEvery(l)} 刀 +${SwordRules.powerHitPower(0, l)}")
      case Skill.Rush => Some(l => s"冲 ${SpellRules.rushSteps(l)} 格，撞 ${span(r => SpellRules.rushImpact(l, 0)(using r))}")
      case Skill.WideHit => Some(l => s"旁边 ${melee(SwordRules.widePower(_, l))}")
      case Skill.FireSword => Some(l => s"${melee(SwordRules.firePower(_, l))}")
      case Skill.LongHit => Some(l => s"隔一格 ${pct(SwordRules.longPower(100, l) / 100.0)}")

    def effect(skill: Skill, l: Int): List[String] = skill match
      case Skill.FireBall | Skill.GreatFireBall => List(s"打一个目标（最远 $R 格），威力 ${mcDmg(l)}$armor", s"威力 = 技能基础 + 你的魔法 ${mc.lo}-${mc.hi}")
      case Skill.Thunder =>
        List(s"打一个目标（最远 $R 格），威力 ${mcDmg(l)}$armor", s"打不死系（骷髅、僵尸等）×1.5：${mcVsUndead(l)}")
      case Skill.HellFire => List(s"朝目标方向直线 ${SpellRules.HellFireLength} 格，每个敌人威力 ${mcDmg(l)}$armor")
      case Skill.Lightning =>
        List(s"朝目标方向直线 ${SpellRules.LightningLength} 格，每个敌人威力 ${mcDmg(l)}$armor", s"不死系 ×1.5：${mcVsUndead(l)}")
      case Skill.FireBang | Skill.IceStorm =>
        val w = SpellRules.BlastRadius * 2 + 1
        List(s"目标周围 ${w}×$w 格内每个敌人威力 ${mcDmg(l)}$armor（最远 $R 格）")
      case Skill.ThunderStorm =>
        val w = SpellRules.ThunderStormRadius * 2 + 1
        List(
          s"自己周围 ${w}×$w 格内的敌人：不死系威力 ${mcDmg(l)}",
          s"非不死系只有十分之一：${span(r => SpellRules.thunderStorm(SpellRules.mcDamage(d, l, mc)(using r), false))}$armor"
        )
      case Skill.FireWall =>
        List(
          s"目标格和上下左右共 5 格起火，持续 ${span(r => SpellRules.fireWallSecs(d, l, mc)(using r))} 秒",
          s"每 ${sec(SpellRules.FireWallTickMs)}烧一次站在火里的敌人，威力 ${mcDmg(l)}$armor（放的那一下定好）"
        )
      case Skill.Repel =>
        val cells = Odds.of()(r => SpellRules.repelPush(L, L - 1, l)(using r)).toList.flatMap(_.keys.flatten)
        List(
          Some("把身边一圈比你等级低的敌人往外推（同级或更高推不动）"),
          Some(s"成功率：低 1 级 ${repelOdds(l, 1)}、低 5 级 ${repelOdds(l, 5)}、低 10 级 ${repelOdds(l, 10)}"),
          Option.when(cells.nonEmpty)(s"推 ${cells.min}-${cells.max} 格（被挡住就停）")
        ).flatten
      case Skill.SpaceMove => List(s"成功率 ${chance(r => SpellRules.spaceMoveWorks(l)(using r))}，成功后传到当前地图的随机位置（部分地图不能用）")
      case Skill.MagicShield =>
        List(
          s"持续 ${span(r => SpellRules.shieldSecs(d, l, mc)(using r))} 秒，期间挨打的伤害少 ${pct(SpellRules.shieldAbsorb(l))}（每下至少掉 1 点）"
        )
      case Skill.Tame =>
        val odds = tameOdds(l)
        List(
          s"诱惑一只怪（最远 $R 格）当宝宝，最多 ${SpellRules.MaxPets} 只（召唤的骷髅、神兽不算）；比你高 2 级以上、${SpellRules.TameLevelLimit} 级以上、不死系的诱惑不了",
          odds.fold("概率未算出") { m =>
            val order = List("收服", "发狂", "打死")
            val parts = order.map(k => s"$k ${pct(m.getOrElse(k, 0))}") ++
              m.toList.collect { case (k, p) if k.startsWith("定住") => s"$k ${pct(p)}" }
            s"对和你同级、血量 300 以下的怪每放一次：${parts.mkString("，")}"
          },
          s"血越多越难收：同级怪血 500 收服 ${pct(SpellRules.tameChance(L, l, SpellRules.TameTarget(L, 500, false)))}、" +
            s"血 1000 收服 ${pct(SpellRules.tameChance(L, l, SpellRules.TameTarget(L, 1000, false)))}；比你低 10 级、血 300 以下 " +
            pct(SpellRules.tameChance(L, l, SpellRules.TameTarget(L - 10, 200, false)))
        )
      case Skill.Healing => List(s"给选中的自己人（没选就是自己）回血 ${heal(l)}", s"= 技能基础 + 道术 ${sc.lo}-${sc.hi} ×2")
      case Skill.GroupHealing =>
        val w = SpellRules.GroupRadius * 2 + 1
        List(s"选中的自己人（没选就是自己）周围 ${w}×$w 格的自己人每人回血 ${heal(l)}")
      case Skill.Poisoning =>
        List(
          "用灰色药粉（绿毒）或黄色药粉（红毒），放包裹里就行，每次用 1 份；两种都带着时红绿交替下",
          s"绿毒：持续 ${span(r => SpellRules.poisonSecs(d, l, sc, green = true)(using r))} 秒，每 2 秒掉 ${span(r => SpellRules.poisonTick(sc)(using r))} 血",
          s"红毒：持续 ${span(r => SpellRules.poisonSecs(d, l, sc, green = false)(using r))} 秒，期间挨打伤害 ×${SpellRules.redPoisonDamage(100) / 100.0}"
        )
      case Skill.SoulFireBall => List(s"用 1 张护身符打一个目标（最远 $R 格），威力 ${scDmg(l)}$armor", s"= 技能基础 + 道术 ${sc.lo}-${sc.hi}")
      case Skill.SoulShield | Skill.HolyArmor =>
        val what = if skill == Skill.SoulShield then "魔防" else "防御"
        val w    = SpellRules.DefenceRadius * 2 + 1
        List(
          s"用 1 张护身符，选中的自己人（没选就是自己）周围 ${w}×$w 格的自己人${what}上限 +（各自等级÷7+1），你是 +${SpellRules.defenceUpAmount(L)}",
          s"持续 ${span(r => SpellRules.defenceUpSecs(d, l, sc)(using r))} 秒"
        )
      case Skill.HolyCurtain =>
        val w = SpellRules.GroupRadius * 2 + 1
        def at(k: Int) = chance(r => SpellRules.holyCurtainCatches(L, L - 2 + k)(using r))
        List(
          s"用 1 张护身符，目标周围 ${w}×$w 格的怪定住 ${span(r => SpellRules.holyCurtainSecs(d, l, sc)(using r))} 秒（挨打会醒）",
          s"怪物 ≤ ${L - 2} 级 ${at(0)}，${L - 1} 级 ${at(1)}，$L 级 ${at(2)}，${L + 1} 级 ${at(3)}，${L + 2} 级 ${at(4)}"
        )
      case Skill.SummonSkeleton =>
        List(s"用 1 张护身符召唤一只，同时最多 ${SpellRules.SummonsEach} 只（不占诱惑之光的名额）", petLine(SpellRules.SkeletonPet.value, l))
      case Skill.SummonShinsu =>
        List(s"用 1 张护身符召唤一只，同时最多 ${SpellRules.SummonsEach} 只（不占诱惑之光的名额）", petLine(SpellRules.ShinsuPet.value, l))
      case Skill.Invisible => List(s"用 1 张护身符隐身 ${span(r => SpellRules.invisibleSecs(d, l, sc)(using r))} 秒，怪物看不见你（打怪或放别的技能就现身）")
      case Skill.GroupInvisible =>
        val w = SpellRules.GroupRadius * 2 + 1
        List(s"用 1 张护身符，选中的自己人（没选就是自己）周围 ${w}×$w 格的自己人隐身 ${span(r => SpellRules.invisibleSecs(d, l, sc)(using r))} 秒")
      case Skill.ShowHp =>
        List(s"成功率 ${chance(r => SpellRules.showHpWorks(l)(using r))}，看穿目标血量 ${span(r => SpellRules.showHpSecs(d, l, sc)(using r))} 秒")
      case Skill.TurnUndead =>
        List(
          s"只对不死系、${SpellRules.TurnUndeadLevelLimit} 级以下：一下打死（最远 $R 格）",
          s"成功率：比你低 1 级 ${turnUndead(l, 1)}、低 2 级 ${turnUndead(l, 2)}、低 5 级 ${turnUndead(l, 5)}、低 10 级 ${turnUndead(l, 10)}"
        )
      case Skill.OneSword => List(s"准确 +${SwordRules.oneSwordHit(l)}")
      case Skill.SpiritSword => List(s"准确 +${SwordRules.spiritSwordHit(l)}")
      case Skill.PowerHit =>
        List(
          s"准确 +${SwordRules.powerHitHit(l)}",
          s"拿武器每砍 ${SwordRules.powerHitEvery(l)} 刀随机一刀是攻杀，威力 +${SwordRules.powerHitPower(0, l)}（你的攻杀 ${melee(SwordRules.powerHitPower(_, l))}，再减防御）"
        )
      case Skill.LongHit =>
        List(
          s"开着时，普攻同时打到正前方隔一格的敌人，那下威力是这一刀的 ${pct(SwordRules.longPower(100, l) / 100.0)}（0 级 40%，每级 +20%，3 级 100%）",
          s"隔一格那下你是 ${melee(SwordRules.longPower(_, l))}，不扣对方防御，但可能被躲开"
        )
      case Skill.WideHit =>
        List(
          s"开着时每刀耗魔法 ${SwordRules.WideHitMp}，魔法不够就是普通一刀",
          s"正前方照常；身边另外 7 格的敌人威力 = 这一刀 ×${l + 1}/4 + 1（你是 ${melee(SwordRules.widePower(_, l))}，再减防御）"
        )
      case Skill.FireSword =>
        List(
          s"放后下一刀砍敌人时威力 ×${SwordRules.firePower(100, l) / 100.0}（你是 ${melee(SwordRules.firePower(_, l))}，再减防御）",
          s"蓄力后 ${sec(SwordRules.FireCooldownMs)}内不能再蓄"
        )
      case Skill.Rush =>
        def push(below: Int) = chance(r => SpellRules.rushPushes(L, L - below, l)(using r))
        List(
          s"朝目标（没有就朝面向）最多冲 ${SpellRules.rushSteps(l)} 格，把挡路的比你等级低的推着走（同级或更高推不动）",
          s"每格推得动的机会：低 1 级 ${push(1)}、低 5 级 ${push(5)}、低 10 级 ${push(10)}",
          s"撞到的挨 ${span(r => SpellRules.rushImpact(l, 0)(using r))}（一格没推动时最重，推着走了 1 格 ${span(r => SpellRules.rushImpact(l, 1)(using r))}），再减防御",
          s"冲劲没用完就撞墙：自己挨 ${span(r => SpellRules.rushCrash(l)(using r))}，再减防御"
        )
