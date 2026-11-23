package mir.client.game

import mir.core.*
import mir.protocol.{GameCmd, GroundView, Look, Tone}
import mir.model.LearnedSkill
import mir.world.{Combat, Path, PlayerOps, World}

/** 玩家想做的事：输入只改意图，每帧在自己空闲时推进一步。 */
enum Intent:
  case Idle
  /** 摇杆推着：朝这个方向一直走（跑） */
  case Stick(dir: Dir, run: Boolean)
  /** 走过去和 NPC 说话 */
  case Talk(npc: ActorId)
  /** 走过去捡地上的东西 */
  case Fetch(item: GroundId)
  /** 走近锁定的怪打它（服务端按「按住攻击」设置决定打一下还是打到死） */
  case Fight(target: ActorId)
  /** 走到某一格（点小地图） */
  case GoTo(cell: Cell)

/**
 * 输入意图 → 游戏指令（照 TS 版 game/controller.ts，自动喝药、自动捡物、还手、烈火已挪到服务端 AutoPlay）。
 * 手机操作（用户 #1140）：摇杆走跑；点怪只锁定，攻击键/技能打锁定的目标；点 NPC 走过去对话；点地上物品走过去捡。
 */
final class Controller(client: GameClient):
  import Controller.*

  var intent: Intent = Intent.Idle
  /** 下一次可以发攻击的时刻（服务端时钟；攻击动画没完别再发） */
  private var nextAttack = Millis.zero

  // ---------------- 输入入口 ----------------

  def stick(dir: Dir, run: Boolean): Unit = intent = Intent.Stick(dir, run)

  def stickReleased(): Unit = intent match
    case Intent.Stick(_, _) => intent = Intent.Idle
    case _                  => ()

  /** 点中一个对象：NPC 走过去对话，怪锁定（正在打别的就改打它），别的玩家不理。 */
  def tapActor(id: ActorId): Unit = client.actor(id).foreach { a =>
    a.look match
      case Look.Npc(_) => intent = Intent.Talk(id)
      case _: Look.Monster =>
        client.locked = Some(id)
        intent match
          case Intent.Fight(_) if client.isEnemy(a) => intent = Intent.Fight(id)
          case _                                    => ()
      case Look.Human(_, _, _, _, _, _) => ()
  }

  def tapGround(item: GroundView): Unit = intent = Intent.Fetch(item.id)

  def unlock(): Unit =
    intent match
      case Intent.Fight(t) if client.locked.contains(t) => intent = Intent.Idle
      case _                                            => ()
    client.locked = None

  /** 攻击键：打锁定的怪；没锁定就锁身边最近的。 */
  def attack(): Unit = autoTarget() match
    case Some(t) =>
      client.locked = Some(t)
      intent = Intent.Fight(t)
    case None => client.ui.notice("附近没有怪", Tone.Bad)

  /** 放 F1–F8 上的技能。 */
  def castKey(key: Int): Unit =
    client.view.skills.find(_.key.contains(key)).foreach(s => castSkill(s.id))

  /** 放技能：打锁定的怪，没有就打最近的，再没有就对着自己脚下（治愈、召唤、魔法盾）。 */
  def castSkill(id: SkillId): Unit =
    val target = autoTarget()
    target.foreach(t => client.locked = Some(t))
    val cell = target.flatMap(client.actor).orElse(client.self).map(_.cell)
    cell.foreach(c => client.send(GameCmd.Cast(id, target, c)))

  /** 走到某一格（点小地图）。 */
  def goTo(cell: Cell): Unit = intent = Intent.GoTo(cell)

  /** 能主动放的技能（不是被动），按学会的顺序。 */
  def activeSkills: List[LearnedSkill] = client.view.skills.filterNot(s => Combat.isPassive(s.id))

  def pickUpHere(): Unit = client.send(GameCmd.PickUp)

  /** 锁定的目标还能打且在技能范围内就是它，否则范围内最近的怪（范围 = 原版魔法锁定范围 8 格，服务端同样判）。 */
  private def autoTarget(): Option[ActorId] =
    client.self.flatMap { me =>
      val range  = mir.rules.SpellRules.CastRange
      val locked = client.locked.flatMap(client.actor).filter(a => client.isEnemy(a) && a.cell.chebyshev(me.cell) <= range)
      locked.orElse(
        client.actors.filter(a => client.isEnemy(a) && a.cell.chebyshev(me.cell) <= range).minByOption(_.cell.chebyshev(me.cell))
      ).map(_.id)
    }

  // ---------------- 每帧 ----------------

  def update(): Unit = client.self.filter(!_.dead).foreach { me =>
    val now = client.serverNow
    intent match
      case Intent.Idle             => ()
      case Intent.Stick(dir, run)  => client.walk(dir, run): Unit
      case Intent.Talk(npc) =>
        client.actor(npc) match
          case None => intent = Intent.Idle
          case Some(n) if n.cell.chebyshev(me.cell) <= TalkRange =>
            intent = Intent.Idle
            client.send(GameCmd.TalkTo(npc))
          case Some(n) => approach(me.cell, n.cell, adjacent = true)
      case Intent.Fetch(id) =>
        client.view.ground.get(id) match
          case None => intent = Intent.Idle
          case Some(g) if g.cell == me.cell =>
            intent = Intent.Idle
            client.send(GameCmd.PickUp)
          case Some(g) => approach(me.cell, g.cell, adjacent = false)
      case Intent.Fight(t) =>
        client.actor(t).filter(client.isEnemy) match
          case None => intent = Intent.Idle
          case Some(a) if a.cell.chebyshev(me.cell) == 1 =>
            if now >= nextAttack && client.motion.ready(now) && client.actionDone(me, now) then
              client.send(GameCmd.Attack(me.cell.dirTo(a.cell), Some(t)))
              nextAttack = now + World.HitAnimMs + AttackGapMs
              // 服务端「按住攻击」关着时只打一下
              if !client.view.settings.stickyAttack then intent = Intent.Idle
          case Some(a) => approach(me.cell, a.cell, adjacent = true)
      case Intent.GoTo(cell) =>
        if me.cell == cell then intent = Intent.Idle else approach(me.cell, cell, adjacent = false)
  }

  /** 朝目标走一步（寻路绕开障碍，远就跑）。 */
  private def approach(from: Cell, to: Cell, adjacent: Boolean): Unit =
    Path.find(client.passable, from, to, PathNodes, adjacent) match
      case Some(d1 :: d2 :: _) if d1 == d2 && from.chebyshev(to) > 3 => client.walk(d1, run = true): Unit
      case Some(d :: _)                                               => client.walk(d, run = false): Unit
      case _                                                          => intent = Intent.Idle

object Controller:
  /** 离 NPC 几格就能对话（服务端允许 PlayerOps.NpcRange 格，走近一点更像原版） */
  val TalkRange: Int = math.min(3, PlayerOps.NpcRange)
  /** 两次攻击之间在动画之外多等的毫秒（服务端还会按攻速再拒） */
  val AttackGapMs = 90L
  val PathNodes   = 3000
