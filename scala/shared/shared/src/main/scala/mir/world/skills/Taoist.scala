package mir.world.skills

import mir.core.*
import mir.model.PoisonKind
import mir.protocol.{Anchor, Tone}
import mir.rules.SpellRules
import mir.rules.Rng
import mir.world.*
import mir.world.Sounds.SpellPhase

/** 道士技能。公式出处 ref/Mir176 MagicManager.cs，特效照 MagicEff.pas。 */
object Taoist:
  private def fx(start: Int): Option[CastFx] = Some(CastFx("magic", start, 10, 60))

  /** 放出去的治疗有没有人收到（都满血就不加熟练度） */
  private def healed(any: Boolean): Effect = if any then Effect.Cast else Effect.Spent

  /** 治愈术：选中的是自己人就治它，否则治自己；血慢慢回（SpellCtx.mend）。 */
  object Healing extends SkillHandler:
    override val castFx = fx(200)
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      val who = friendOrSelf
      c.fx("magic", 200, 10, 60, Anchor.OnActor(who.id))
      healed(mend(who, SpellRules.heal(d, lv, sc)))

  /** 群体治疗术：中心周围 1 格的自己人（MagBigHealing）。 */
  object GroupHealing extends SkillHandler:
    override val castFx = fx(1790)
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      val heal   = SpellRules.heal(d, lv, sc)
      val center = friendCenter
      val any    = friendsAround(center, SpellRules.GroupRadius).map(mend(_, heal)).foldLeft(false)(_ || _)
      c.fx("magic", 1800, 10, 80, Anchor.OnCell(center))
      healed(any)

  /** 施毒术：灰色药粉绿毒、黄色药粉红毒；两种都带着时每次换一种。 */
  object Poisoning extends SkillHandler:
    override val castFx = fx(600)
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(enemy.flatMap { t =>
        poisonPowder.map { (a, kind) =>
          consume(a)
          p.lastPoison = Some(kind)
          val secs = SpellRules.poisonSecs(d, lv, sc, green = kind == PoisonKind.Green)
          w.conditions.poison(t, kind, secs * 1000L, SpellRules.poisonTick(sc), p.id)
          t match
            case m: Monster => provoke(m)
            case _          => ()
          c.fx("magic", 610, 10, 60, Anchor.OnActor(t.id))
        }
      })

  /** 灵魂火符：要护身符。 */
  object SoulFireBall extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(enemy.flatMap { t =>
        charm.map { a =>
          consume(a)
          val power = SpellRules.scDamage(d, lv, sc)
          val delay = 80 * p.cell.chebyshev(t.cell)
          c.fx("magic", 1160 + p.cell.dir16(t.cell) * 10, 3, 60, Anchor.OnActor(t.id), from = Some(p.cell), duration = Some(delay))
          sound(p.cell, SpellPhase.Fly)
          w.afterOn(t, delay) { t =>
            sound(t.cell, SpellPhase.Burst)
            hitMagic(t, power)
            c.fx("magic", 1320, 10, 50, Anchor.OnActor(t.id))
          }
        }
      })

  /** 幽灵盾（魔防）、神圣战甲术（防御）：中心周围 3 格的自己人（MagMakeDefenceArea）。 */
  final class DefenceUp(magic: Boolean, burst: Int) extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(charm.map { a =>
        consume(a)
        val secs   = SpellRules.defenceUpSecs(d, lv, sc)
        val center = friendCenter
        friendsAround(center, SpellRules.DefenceRadius).foreach { a =>
          w.conditions.defenceUp(a, magic = magic, SpellRules.defenceUpAmount(levelOf(a)), secs)
        }
        charmFly(center, burst, 16, 50)
      })

  val SoulShield = DefenceUp(magic = true, burst = 1320)
  val HolyArmor  = DefenceUp(magic = false, burst = 1340)

  /** 困魔咒：目标周围 1 格等级够低的怪定住，四周围一圈光（MagMakeHolyCurtain）。 */
  object HolyCurtain extends SkillHandler:
    override val castFx = fx(1380)
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(inReach(aim).flatMap(_ => charm).map { a =>
        consume(a)
        val secs   = SpellRules.holyCurtainSecs(d, lv, sc)
        val caught = enemiesAround(aim, SpellRules.GroupRadius).collect {
          case m: Monster if SpellRules.holyCurtainCatches(p.level, m.defn.level) => m
        }
        caught.foreach(w.conditions.seize(_, secs))
        if caught.nonEmpty then
          List((-1, -2), (1, -2), (-2, -1), (2, -1), (-2, 1), (2, 1), (-1, 2), (1, 2)).foreach { (dx, dy) =>
            w.conditions.addField(Cell(aim.x + dx, aim.y + dy), FieldEffect.HolyCurtain, secs * 1000L)
          }
          w.out.notice(p, s"困住 ${caught.size} 只怪 $secs 秒（挨打会醒）", Tone.System)
        else w.out.notice(p, "没有困住怪（要比你低几级的怪才困得住）", Tone.System)
      })

  /** 召唤宝宝：同名的最多 SpellRules.SummonsEach 只，按技能等级加血加攻。 */
  final class Summon(pet: MonsterName, override val castFx: Option[CastFx]) extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      val have = p.pets.flatMap(w.actor).count {
        case m: Monster => m.defn.name == pet
        case _          => false
      }
      charm match
        case Left(fail) => fail
        case Right(_) if have >= SpellRules.SummonsEach =>
          Effect.Fail(s"${pet.value}最多 ${SpellRules.SummonsEach} 只")
        case Right(a) =>
          w.catalog.monster(pet) match
            case None => Effect.Fail("召唤失败")
            case Some(md) =>
              consume(a)
              w.spawner.spawnMonster(md, None, p.cell.step(p.dir), 1).foreach { m =>
                val boost = SpellRules.summonBoost(lv)
                def scale(v: Int) = math.floor(v * boost + 0.5).toInt
                m.master = Some(p.id)
                m.petLevel = lv
                w.vitals.setMaxHp(m, scale(md.hp))
                m.dc = mir.data.Span(scale(md.dc.lo), scale(md.dc.hi))
                p.pets += m.id
                c.fx("magic", 1500, 10, 60, Anchor.OnActor(m.id))
              }
              Effect.Cast

  val SummonSkeleton = Summon(SpellRules.SkeletonPet, fx(1500))
  val SummonShinsu   = Summon(SpellRules.ShinsuPet, None)

  /** 隐身：盯着他的怪（看不穿隐身的）丢掉目标。 */
  private def makeInvisible(w: World, a: Actor, secs: Int): Unit =
    w.conditions.addStatus(a, Status.Invisible, secs * 1000L)
    w.monsters.filter(m => m.target.contains(a.id) && m.defn.coolEye == 0).foreach(_.target = None)
    a match
      case q: Player => w.out.notice(q, s"隐身 $secs 秒（打怪或被打会现身）", Tone.System)
      case _         => ()

  object Invisible extends SkillHandler:
    override val castFx         = fx(1520)
    override val keepsInvisible = true
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(charm.map { a =>
        consume(a)
        makeInvisible(w, p, SpellRules.invisibleSecs(d, lv, sc))
      })

  /** 集体隐身术：中心周围 1 格还没隐身的自己人（MagMakeGroupTransparent）。 */
  object GroupInvisible extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(charm.map { a =>
        consume(a)
        val secs   = SpellRules.invisibleSecs(d, lv, sc)
        val center = friendCenter
        friendsAround(center, SpellRules.GroupRadius).filterNot(_.statuses.has(StatusKind.Invisible)).foreach(makeInvisible(w, _, secs))
        charmFly(center, 1540, 10, 60)
      })

  /** 心灵启示：看穿目标的血量（ShowHp）；20 帧、帧时减半（HumActor.pas）。 */
  object ShowHp extends SkillHandler:
    override val castFx = Some(CastFx("magic", 3960, 20, 30))
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      target.filter(a => !a.isInstanceOf[Npc]) match
        case None => Effect.Fail("需要选中一个目标")
        case Some(a) =>
          run(inReach(a.cell).map { _ =>
            c.fx("magic", 3990, 10, 80, Anchor.OnActor(a.id))
            val name = displayName(a)
            if SpellRules.showHpWorks(lv) then
              val secs = SpellRules.showHpSecs(d, lv, sc)
              w.out.notice(p, s"$name 血量 ${a.hp}/${a.maxHp}（看穿 $secs 秒）", Tone.System)
            else w.out.notice(p, "心灵启示失败", Tone.System)
          })
