package mir.world.skills

import mir.protocol.{SwordArt, Tone}
import mir.rules.Rng
import mir.world.*

/** 战士技能里要「放」的：剑术开关、烈火蓄力、野蛮冲撞（攻杀、刺杀、半月、烈火的出刀效果在 Combat.attack）。 */
object Warrior:
  /** 刺杀剑术：被动，默认开着，放一下是开关。 */
  object LongHit extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      Combat.setSword(c.w, c.p, SwordArt.LongHit, !c.p.longHitOn)
      Effect.Instant

  /** 半月弯刀：开关。 */
  object WideHit extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      Combat.setSword(c.w, c.p, SwordArt.WideHit, !c.p.wideHitOn)
      Effect.Instant

  /** 烈火剑法：蓄力，下一刀生效（不进施法动作）。 */
  object FireSword extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect =
      import c.*
      if p.fireReady then Effect.Fail("烈火已经准备好了")
      else
        p.fireReady = true
        w.out.sound(p.cell, Sounds.fireHitReady)
        w.out.notice(p, "烈火剑法准备好了", Tone.System)
        Effect.Instant

  /** 野蛮冲撞：朝目标（没有就朝面向）冲。 */
  object Rush extends SkillHandler:
    def cast(c: SpellCtx)(using Rng): Effect = Effect.Acted(trained = mir.world.Rush(c.w, c.p, c.aimDir, c.lv).succeeded)
