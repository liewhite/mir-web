package mir.world.skills

import mir.core.*
import mir.protocol.Anchor
import mir.protocol.Tone
import mir.rules.{Pace, SpellRules}
import mir.rules.Rng
import mir.world.*
import mir.world.Sounds.SpellPhase

/** 法师技能。公式出处 ref/Mir176 MagicManager.cs，特效照 MagicEff.pas。 */
object Wizard:
  private def fx(start: Int): Option[CastFx] = Some(CastFx("magic", start, 10, 60))

  /** 单体飞弹：火球术、大火球（飞过去炸开）、雷电术（从天而降，打不死系 ×1.5）。 */
  final class Bolt(fly: Option[Int], undeadBonus: Boolean, override val castFx: Option[CastFx]) extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(enemy.flatMap { t =>
        if !w.terrain.flyable(t.cell) then Left(Effect.Fail("被挡住了"))
        else
          val power = if undeadBonus && isUndead(t) then SpellRules.vsUndead(mcPower()) else mcPower()
          val delay = fly.fold(100)(_ => 80 * p.cell.chebyshev(t.cell))
          sound(p.cell, SpellPhase.Fly)
          w.afterOn(t, delay) { t =>
            sound(t.cell, SpellPhase.Burst)
            hitMagic(t, power)
            fly.foreach(base => c.fx("magic", base + 170, 10, 50, Anchor.OnActor(t.id))) // 炸开
          }
          fly match
            case Some(base) => c.fx("magic", base + 10 + p.cell.dir16(t.cell) * 10, 6, 50, Anchor.OnActor(t.id), from = Some(p.cell), duration = Some(delay))
            case None       => c.fx("magic2", 10, 5, 80, Anchor.OnActor(t.id))
          Right(())
      })

  val FireBall      = Bolt(fly = Some(0), undeadBonus = false, fx(0))
  val GreatFireBall = Bolt(fly = Some(400), undeadBonus = false, fx(400))
  val Thunder       = Bolt(fly = None, undeadBonus = true, Some(CastFx("magic2", 20, 3, 60)))

  /** 直线穿透（MagPassThroughMagic）：地狱火 5 格、疾光电影 8 格（打不死系 ×1.5）。 */
  object HellFire extends SkillHandler:
    override val castFx = fx(920)
    def cast(c: SpellCtx)(using Rng): Effect =
      val cells = (1 to SpellRules.HellFireLength).map(c.p.cell.step(c.aimDir, _))
      cells.foreach(cell => c.fx("magic", 930, 6, 70, Anchor.OnCell(cell)))
      lineHit(c, cells, undeadBonus = false)

  object Lightning extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      val cells = (1 to SpellRules.LightningLength).map(c.p.cell.step(c.aimDir, _))
      c.fx("magic", 970 + c.aimDir16 * 10, 6, 50, Anchor.OnActor(c.p.id)) // TLightingThunder：在施法者身上按 16 方向画光束
      lineHit(c, cells, undeadBonus = true)

  private def lineHit(c: SpellCtx, cells: Seq[Cell], undeadBonus: Boolean)(using Rng): Effect =
    import c.*
    val power = mcPower()
    cells.flatMap(w.occupant).distinctBy(_.id).filter(w.isEnemy(p, _)).foreach { a =>
      val pw = if undeadBonus && isUndead(a) then SpellRules.vsUndead(power) else power
      w.afterOn(a, 100)(hitMagic(_, pw))
    }
    Effect.Cast

  /** 目标周围 1 格（MagBigExplosion）：爆裂火焰、冰咆哮。 */
  final class Blast(burst: Int, override val castFx: Option[CastFx]) extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(inReach(aim).map { _ =>
        val power = mcPower()
        val hit   = enemiesAround(aim, SpellRules.BlastRadius)
        c.fx("magic", burst, 20, 80, Anchor.OnCell(aim))
        hit.foreach(w.afterOn(_, 200)(hitMagic(_, power)))
      })

  val FireBang = Blast(1660, fx(1650))
  val IceStorm = Blast(3850, fx(3840))

  /** 地狱雷光：自己周围 2 格，不死系全额、其余十分之一（MagElecBlizzard）。 */
  object ThunderStorm extends SkillHandler:
    override val castFx = fx(1680)
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      val power = mcPower()
      enemiesAround(p.cell, SpellRules.ThunderStormRadius).foreach { a =>
        val pw = SpellRules.thunderStorm(power, isUndead(a))
        w.afterOn(a, 200)(hitMagic(_, pw))
      }
      Effect.Cast

  /** 火墙：目标格和上下左右共 5 格烧一阵（MagMakeFireCross / FireBurnEvent）。 */
  object FireWall extends SkillHandler:
    override val castFx = fx(1620)
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(inReach(aim).map { _ =>
        val damage = mcPower()
        val secs   = SpellRules.fireWallSecs(d, lv, mc)
        val cells  = List(aim.step(Dir.Up), aim.step(Dir.Left), aim, aim.step(Dir.Right), aim.step(Dir.Down))
        cells.filter(w.terrain.walkable).foreach(cell => w.conditions.addField(cell, FieldEffect.Fire(p.id, damage), secs * 1000L))
      })

  /** 抗拒火环：推开身边比自己等级低的（MagPushArround）。 */
  object Repel extends SkillHandler:
    override val castFx = fx(900)
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      enemiesAround(p.cell, 1).foreach { a =>
        SpellRules.repelPush(p.level, levelOf(a), lv).foreach(n => w.grid.pushBack(a, p.cell.dirTo(a.cell), n): Unit)
      }
      c.fx("magic", 900, 6, 80, Anchor.OnActor(p.id))
      Effect.Cast

  /** 瞬息移动：成功时回到回城点那张图的随机位置（MagSaceMove → MapRandomMove(HomeMap)）。 */
  object SpaceMove extends SkillHandler:
    override val castFx = fx(1590)
    def cast(c: SpellCtx)(using rng: Rng): Effect =
      import c.*
      if SpellRules.spaceMoveWorks(lv) then
        w.afterOn(p, Pace.SpellAnimMs) { p =>
          val home = p.home
          val dest =
            if home.map != w.mapId then Some(home) // 别的图的地形这里不知道：送到回城点，由那张图找空格
            else Iterator.fill(300)(Cell(rng.random(w.terrain.width), rng.random(w.terrain.height))).find(w.canEnter).map(Place(w.mapId, _))
          dest.foreach(w.roster.transfer(p, _))
        }
      else w.out.notice(p, "瞬息移动失败", Tone.Bad)
      Effect.Cast

  object MagicShield extends SkillHandler:
    override val castFx = Some(CastFx("magic", 3880, 10, 60))
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      w.conditions.addStatus(p, Status.Shielded(lv), SpellRules.shieldSecs(d, lv, mc) * 1000L)
      w.out.notice(p, "魔法盾开启", Tone.System)
      Effect.Cast

  /** 诱惑之光（MagTamming）。 */
  object Tame extends SkillHandler:
    override val castFx = fx(1560)
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      target match
        case Some(m: Monster) if m.master.contains(p.id) => Effect.Fail("已经是你的宝宝了")
        case Some(m: Monster) if w.isEnemy(p, m) =>
          val t = SpellRules.TameTarget(m.defn.level, m.maxHp, isUndead(m))
          if Behavior.isAnimal(m.defn) then Effect.Fail("鸡、鹿、羊、狼这类动物不能诱惑")
          else
            run(inReach(m.cell).flatMap(_ => SpellRules.tameBlocker(p.level, t, p.pets.size).map(Effect.Fail(_)).toLeft(())).map { _ =>
              c.fx("magic", 1570, 10, 80, Anchor.OnActor(m.id))
              val name = displayName(m)
              SpellRules.tame(p.level, lv, t) match
                case SpellRules.TameOutcome.Tamed =>
                  makePet(c, m)
                  w.out.notice(p, s"$name 成了你的宝宝", Tone.Good)
                case SpellRules.TameOutcome.Seized(secs) =>
                  w.conditions.seize(m, secs)
                  w.out.notice(p, s"$name 被定住了 $secs 秒（挨打会醒），再试一次", Tone.System)
                case SpellRules.TameOutcome.Crazy(secs) =>
                  w.conditions.enrage(m, secs)
                  w.out.notice(p, s"$name 发狂了，$secs 秒内见谁打谁", Tone.Bad)
                case SpellRules.TameOutcome.Killed =>
                  w.vitals.damage(m, m.hp, Some(p.id))
                  w.out.notice(p, s"$name 被诱惑之光打死了", Tone.System)
                case SpellRules.TameOutcome.NoEffect =>
                  w.out.notice(p, s"诱惑 $name 没有成功", Tone.System)
            })
        case _ => Effect.Fail("需要选中一只怪")

    /** 诱惑成功：认主、不再算刷怪点的、解除定住发狂、按技能等级加快走路出手。 */
    private def makePet(c: SpellCtx, m: Monster): Unit =
      m.master = Some(c.p.id)
      m.target = None
      m.spawn = None
      m.statuses.remove(StatusKind.Seized)
      m.statuses.remove(StatusKind.Crazy)
      m.petLevel = c.lv
      m.walkInterval = math.min(m.walkInterval, SpellRules.petWalkCap(c.lv))
      m.attackInterval = math.min(m.attackInterval, SpellRules.petAttackCap(c.lv))
      c.p.pets += m.id

  /** 圣言术：有机会直接打死不死系（MagTurnUndead）。 */
  object TurnUndead extends SkillHandler:
    override val castFx = Some(CastFx("magic", 3920, 10, 60))
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      run(enemyMonster.flatMap { m =>
        if !isUndead(m) then Left(Effect.Fail("圣言术只对不死系的怪有效"))
        else
          c.fx("magic", 3930, 16, 80, Anchor.OnActor(m.id))
          val name = displayName(m)
          if SpellRules.turnUndeadKills(p.level, m.defn.level, lv) then
            w.vitals.damage(m, m.hp, Some(p.id))
            w.out.notice(p, s"圣言术超度了 $name", Tone.Good)
          else
            provoke(m)
            w.out.notice(p, s"圣言术没有超度 $name", Tone.System)
          Right(())
      })
