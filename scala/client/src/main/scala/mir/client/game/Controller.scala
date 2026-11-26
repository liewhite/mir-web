package mir.client.game

import mir.core.*
import mir.protocol.{ActorView, GameCmd, GroundView, Look, Tone}
import mir.model.LearnedSkill
import mir.rules.{Pace, Skill, SkillAim}
import mir.terrain.{MeleeReach, Path, Reach}

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
  /** 沿算好的路走到某一格（点全景地图） */
  case GoTo(route: Travel.Route)
  /** 朝这个方向的矿壁一直挖（#1286，原版 g_boAutoDig：挖到做别的事为止） */
  case Dig(dir: Dir)

/**
 * 输入意图 → 游戏指令（照 TS 版 game/controller.ts，自动喝药、自动捡物、还手、烈火已挪到服务端 AutoPlay）。
 * 手机操作（用户 #1140）：摇杆走跑；点怪只锁定，攻击键/技能打锁定的目标；点 NPC 走过去对话；点地上物品走过去捡。
 */
final class Controller(client: GameClient):
  import Controller.*

  var intent: Intent = Intent.Idle

  // ---------------- 输入入口 ----------------

  def stick(dir: Dir, run: Boolean): Unit = intent = Intent.Stick(dir, run)

  def stickReleased(): Unit = intent match
    case Intent.Stick(_, _) => intent = Intent.Idle
    case _                  => ()

  /**
   * 点中一个对象：NPC 走过去对话；怪、宝宝、别的玩家都锁定（正在打怪时点了别的敌人就改打它）。
   * 锁定自己人是为了给它放治愈、加防（SkillAim.Friend），攻击键和攻击技能不会打它。
   */
  def tapActor(id: ActorId): Unit = client.actor(id).foreach { a =>
    a.look match
      case Look.Npc(_) => intent = Intent.Talk(id)
      case _: Look.Monster | _: Look.Human =>
        client.locked = Some(id)
        intent match
          case Intent.Fight(_) if client.isEnemy(a) => intent = Intent.Fight(id)
          case _                                    => ()
  }

  def tapGround(item: GroundView): Unit = intent = Intent.Fetch(item.id)

  def unlock(): Unit =
    intent match
      case Intent.Fight(t) if client.locked.contains(t) => intent = Intent.Idle
      case _                                            => ()
    client.locked = None

  /**
   * 攻击键：打锁定的怪（锁定的守卫也打）；在矿区拿着鹤嘴锄、身边有矿壁就挖矿（#1286）；
   * 都不是就锁身边最近的怪。
   */
  def attack(): Unit =
    val next = lockedInReach(client.canAttack).map(a => Intent.Fight(a.id))
      .orElse(client.rockToDig.map(Intent.Dig(_)))
      .orElse(nearest(client.isEnemy).map(a => Intent.Fight(a.id)))
    next match
      case Some(f @ Intent.Fight(t)) =>
        client.locked = Some(t)
        intent = f
      case Some(other) => intent = other
      case None        => client.ui.notice("附近没有怪", Tone.Bad)

  /** 放 F1–F8 上的技能。 */
  def castKey(key: Int): Unit =
    client.view.skills.find(_.key.contains(key)).foreach(s => castSkill(s.id))

  /**
   * 放技能。打敌人的：打锁定的怪，没有就打最近的（并锁定它），再没有就对着自己脚下（召唤、魔法盾）；
   * 给自己人的：锁定的自己人就放给它，否则放给自己，不改锁定。
   */
  def castSkill(id: SkillId): Unit =
    val target = Skill.of(id).fold(SkillAim.Enemy)(_.aim) match
      case SkillAim.Enemy =>
        val t = autoTarget()
        t.foreach(t => client.locked = Some(t))
        t
      case SkillAim.Friend => lockedInReach(client.isFriend).map(_.id)
    val cell = target.flatMap(client.actor).orElse(client.self).map(_.cell)
    cell.foreach(client.cast(id, target, _))
    // 持续施法（#1308）由服务端接着放：别再走过去近身打（普通攻击会把它停掉）
    if target.isDefined && sustains(id) then
      intent match
        case Intent.Fight(_) => intent = Intent.Idle
        case _               => ()

  /** 走到某一格（全景地图）：按地形算好整条路；走不到的地方走到离它最近处，一步都走不了就不动。 */
  def goTo(cell: Cell): Unit =
    intent = (for
      me    <- client.self
      md    <- client.map
      route <- Travel.plan(md.terrain.walkable, me.cell, cell)
    yield Intent.GoTo(route)).getOrElse(Intent.Idle)

  /** 这个技能放出去会不会转成持续施法（角色设置开着、是攻击技能） */
  private def sustains(id: SkillId): Boolean =
    client.view.settings.sustainCast && Skill.of(id).exists(mir.rules.AutoRules.Sustained)

  /** 能主动放的技能（不是被动），按学会的顺序。 */
  def activeSkills: List[LearnedSkill] = client.view.skills.filterNot(s => Skill.isPassive(s.id))

  def pickUpHere(): Unit = client.send(GameCmd.PickUp)

  /**
   * 锁定的目标还能打（含守卫）且在技能范围内就是它，否则范围内最近的怪（不会自己挑上守卫）；
   * 范围 = 原版魔法锁定范围 8 格，服务端同样判。
   */
  private def autoTarget(): Option[ActorId] =
    lockedInReach(client.canAttack).orElse(nearest(client.isEnemy)).map(_.id)

  /** 锁定的对象，要是符合 kind 且在技能范围内 */
  private def lockedInReach(kind: ActorView => Boolean): Option[ActorView] =
    client.locked.flatMap(client.actor).filter(a => kind(a) && inReach(a))

  /** 技能范围内符合 kind 的最近对象 */
  private def nearest(kind: ActorView => Boolean): Option[ActorView] =
    client.self.flatMap(me => client.actors.filter(a => kind(a) && inReach(a)).minByOption(_.cell.chebyshev(me.cell)))

  private def inReach(a: ActorView): Boolean =
    client.self.exists(me => a.cell.chebyshev(me.cell) <= mir.rules.SpellRules.CastRange)

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
        client.actor(t).filter(client.canAttack) match
          case None => intent = Intent.Idle
          // 贴身，或开着刺杀时隔一格直线，就地出刀；都不是才走到最近能出刀的格子（#1264）
          case Some(a) if MeleeReach.canStrike(reach, me.cell, a.cell, ground) =>
            if client.motion.ready(now) && client.actionDone(me, now) then
              client.attack(me.cell.dirTo(a.cell), Some(t))
              // 服务端「按住攻击」关着时只打一下
              if !client.view.settings.stickyAttack then intent = Intent.Idle
          case Some(a) => follow(me.cell, a.cell, MeleeReach.path(reach, client.passable, ground, me.cell, a.cell, PathNodes))
      // 被挤开、自动捡物走开了、换了武器：这个方向不再是矿壁就停
      case Intent.Dig(dir) if !client.rocksAround.contains(dir) => intent = Intent.Idle
      case Intent.Dig(dir) =>
        if client.motion.ready(now) && client.actionDone(me, now) then client.attack(dir, None)
      case Intent.GoTo(route) =>
        Travel.follow(route, me.cell) match
          case Travel.Step.Arrived => intent = Intent.Idle
          case Travel.Step.OffRoute => goTo(route.dest)
          case Travel.Step.Next(rest, toward) =>
            intent = Intent.GoTo(rest)
            approach(me.cell, toward, adjacent = false)
  }

  /** 自己近身能打多远：刺杀学了且开着就是贴身或隔一格直线。 */
  private def reach: Reach =
    Reach.of(client.view.sword.longHitOn, client.view.skills.exists(_.id == Skill.LongHit.id))

  /** 地形能走（刺杀隔的那格不能是墙） */
  private def ground: Cell => Boolean = c => client.map.exists(_.terrain.walkable(c))

  /** 朝目标走一步（寻路绕开障碍，远就跑）。 */
  private def approach(from: Cell, to: Cell, adjacent: Boolean): Unit =
    follow(from, to, Path.find(client.passable, from, to, PathNodes, adjacent))

  /** 沿算好的路走一步；离 to 还远且前两步同向就跑；没路就停。 */
  private def follow(from: Cell, to: Cell, path: Option[List[Dir]]): Unit =
    path match
      case Some(d1 :: d2 :: _) if d1 == d2 && from.chebyshev(to) > 3 => client.walk(d1, run = true): Unit
      case Some(d :: _)                                               => client.walk(d, run = false): Unit
      case _                                                          => intent = Intent.Idle

object Controller:
  /** 离 NPC 几格就能对话（服务端允许 Pace.NpcRange 格，走近一点更像原版） */
  val TalkRange: Int = math.min(3, Pace.NpcRange)
  val PathNodes   = 3000
