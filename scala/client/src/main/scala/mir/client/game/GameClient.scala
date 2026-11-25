package mir.client.game

import mir.client.assets.MapData
import mir.client.net.{LinkEvent, ServerClock, Transport}
import mir.client.render.*
import mir.core.*
import mir.data.Catalog
import mir.model.{ActKind, ItemBonus}
import mir.rules.Upgrade
import mir.protocol.*
import mir.view.ViewState
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.concurrent.Future

/** 客户端核心要的外部能力（换掉就能在测试里跑）。 */
final case class ClientEnv(
    /** 本地单调时钟（毫秒） */
    localNow: () => Double,
    loadMap: MapId => Future[MapData],
    /** 播音效：编号、声源格（None 是自己身上）、自己所在格 */
    sound: (Int, Option[Cell], Option[Cell]) => Unit,
    /** 服务端发来它的构建提交：判断页面过没过时，过时就刷新页面（#1242）；测试里默认同一版 */
    serverBuild: String => Freshness = _ => Freshness.Current
)

/**
 * 客户端核心：收服务端消息维护视野副本（ViewState）、自己走路本地先走（SelfMotion）、一次性画面（Transients）、
 * 对时；发指令。不碰 DOM：画面由 Renderer 读 scene，界面读 view 并收 UiEvents。
 */
final class GameClient(catalog: Catalog, env: ClientEnv):
  /** 界面：建好界面后接上（界面也要读客户端，所以不能在构造时给） */
  var ui: UiEvents = UiEvents.Ignore
  private var transport = Option.empty[Transport]
  val clock             = ServerClock()

  var view: ViewState    = ViewState()
  var motion: SelfMotion = SelfMotion()
  var fx: Transients     = Transients()
  /** 当前地图的格子数据（进图后下载好才有） */
  var map: Option[MapData] = None
  /** 锁定的目标（点怪锁定，攻击键、技能打它） */
  var locked: Option[ActorId] = None
  var options: ViewOptions    = ViewOptions()
  /** 选好的角色：断线重连后自动再进 */
  private var playing = Option.empty[CharId]

  def connect(t: Transport): Unit = transport = Some(t)

  /** 正在玩的角色（刷新页面前记下，刷新后接着进） */
  def current: Option[CharId] = playing

  /** 刷新页面前在玩这个角色：当作断线重连，连上就直接进游戏，不回选人（在 connect 前调） */
  def resume(id: CharId): Unit = playing = Some(id)
  /** 最近发出的指令（给 window.__mir 测试看） */
  var recent: Vector[ClientCmd] = Vector.empty

  def send(cmd: ClientCmd): Unit =
    recent = (recent :+ cmd).takeRight(GameClient.RecentKeep)
    transport.foreach(_.send(cmd))

  def serverNow: Millis = clock.now(env.localNow())

  def enterGame(id: CharId): Unit =
    playing = Some(id)
    send(SessionCmd.EnterGame(id))

  def ping(): Unit = send(SessionCmd.Ping(math.round(env.localNow())))

  // ---------------- 收 ----------------

  def receive(e: LinkEvent): Unit = e match
    case LinkEvent.Up =>
      clock.reset()
      ui.link(None)
      ping()
      playing.foreach(id => send(SessionCmd.EnterGame(id)))
    case LinkEvent.Down(why) =>
      motion = SelfMotion()
      ui.link(Some(why))
    case LinkEvent.Received(m) => onMessage(m)

  private def onMessage(m: ServerMsg): Unit =
    val before = view
    view = view(m)
    val local = env.localNow()
    m match
      case ServerMsg.EnterMap(_, mapId, _, _, now) =>
        clock.roughly(now, local)
        motion = SelfMotion()
        fx = fx.cleared
        locked = None
        if !map.exists(_.id == mapId) then
          map = None
          env.loadMap(mapId).foreach { md =>
            if view.map.contains(mapId) then
              map = Some(md)
              ui.entered(mapId)
          }
        else ui.entered(mapId)
      case ServerMsg.MoveOk                 => motion = motion.ok
      case ServerMsg.MoveDenied(_, _)       => motion = motion.denied(serverNow)
      case ServerMsg.Disappear(id)          => if locked.contains(id) then locked = None
      case ServerMsg.Effect(e)              => fx = fx.effect(e)
      case ServerMsg.Sound(id, at)          => env.sound(id, at, view.self.map(_.cell))
      case ServerMsg.Damage(t, n, _)        => fx = fx.float(Floater(t, s"-$n", if view.me.contains(t) then FloatTone.HurtMe else FloatTone.HurtOther, local))
      case ServerMsg.Miss(t)                => fx = fx.float(Floater(t, "MISS", FloatTone.Miss, local))
      case ServerMsg.Heal(t, n)             => if n > 0 then fx = fx.float(Floater(t, s"+$n", FloatTone.Heal, local))
      case ServerMsg.Chat(from, text, kind) =>
        // 队伍聊天照原版只进聊天框（系统消息），不在头上冒字
        if kind != ChatKind.Group then speaker(from).foreach(id => fx = fx.say(Bubble(id, text, local)))
        ui.chat(from, text, kind)
      // 私聊照原版只进聊天框，不在头上冒字
      case ServerMsg.Whisper(peer, text, way) => ui.whisper(peer, text, way)
      case ServerMsg.Announce(text)         => ui.announce(text)
      case ServerMsg.Notice(text, tone)     => ui.notice(text, tone)
      case ServerMsg.Refused(why)           => ui.notice(why, Tone.Bad)
      case ServerMsg.LevelUp(lv)            => ui.levelUp(lv)
      case ServerMsg.Died                   => ui.died()
      case ServerMsg.NpcOpened(npc, ref)    => ui.npcOpened(npc, ref)
      case ServerMsg.Inspected(v)           => ui.inspected(v)
      case ServerMsg.Deal(Some(d)) if before.deal.isEmpty => ui.dealOpened(d.partner)
      case ServerMsg.Kicked(why)            =>
        playing = None
        ui.kicked(why)
      // 重连时服务端也会再发一次：已经在玩就直接回游戏（Up 时已发 EnterGame），不回选人
      case ServerMsg.LoggedIn(account, build) =>
        env.serverBuild(build) match
          case Freshness.Reload  => () // 页面马上刷新，不再进选人
          case Freshness.Current => if playing.isEmpty then ui.loggedIn(account)
          case Freshness.Stuck   =>
            ui.notice("游戏已更新，请手动刷新页面", Tone.Bad)
            if playing.isEmpty then ui.loggedIn(account)
      case ServerMsg.CharList(chars)        => ui.charList(chars)
      case ServerMsg.Pong(sent, server)     => clock.pong(sent.toDouble, server, local)
      case ServerMsg.Act(id, _, _, _) if locked.contains(id) && view.actors.get(id).exists(_.dead) => locked = None
      case _                                => ()
    // 死了就不再本地先走
    if before.self.exists(!_.dead) && view.self.exists(_.dead) then motion = SelfMotion()

  /** 说话的人：名字对得上的玩家。 */
  private def speaker(name: String): Option[ActorId] =
    view.actors.values.find(_.look match
      case h: Look.Human => h.name == name
      case _             => false
    ).map(_.id)

  // ---------------- 查 ----------------

  /** 画出来的自己（本地先走）。 */
  def self: Option[ActorView] = view.self.map(motion.shown(_, serverNow))

  /** 画面上的全部对象（自己换成本地先走的样子）。 */
  def actors: Iterable[ActorView] =
    val now = serverNow
    view.actors.values.map(a => if view.me.contains(a.id) then motion.shown(a, now) else a)

  /** 自己作为穿戴者（比装备好坏用，#1200）；还没进游戏时为空。 */
  def wearer: Option[Upgrade.Wearer] =
    for
      a <- view.self
      h <- a.look match
        case h: Look.Human => Some(h)
        case _             => None
    yield Upgrade.Wearer(h.job, h.sex, view.worn)

  /** 一件物品换下身上同部位那件后主属性提升多少（不是提升为空，#1200）。 */
  def upgradeOf(name: ItemName, bonus: Option[ItemBonus]): Option[Upgrade.Gain] =
    for
      w <- wearer
      d <- catalog.item(name)
      g <- Upgrade.gain(w, d, bonus, catalog.item)
    yield g

  /** 地上比身上强的装备（设置里关了就不算）。 */
  private def groundUpgrades: Set[GroundId] =
    if !options.upgradeHint then Set.empty
    else
      view.ground.values.collect {
        case GroundView(id, _, GroundThing.Item(n, b)) if upgradeOf(n, b).isDefined => id
      }.toSet

  def actor(id: ActorId): Option[ActorView] = if view.me.contains(id) then self else view.actors.get(id)

  /** 能打的：活着、和自己敌对、露在外面（野怪、发狂的怪；不是守卫、宝宝），和服务端同一条规则（Look.hostile）。 */
  def isEnemy(a: ActorView): Boolean =
    !a.dead && self.exists(me => me.id != a.id && Look.hostile(me.look, a.look, catalog.isGuard))

  /**
   * 锁定了能亲手打的：敌人，加上守卫（#1205，同原版；服务端同一条规则 Look.attackable），
   * 加上安全区外的别的玩家和别人的宝宝（PK，#1225，服务端 Justice.duel 同一条 Look.duel + SafeZone）。
   * 自动找怪仍只找 isEnemy，不会自己挑上人；队友和队友的宝宝不能打（#1274，服务端 Hostility.allied），点了只锁定。
   */
  def canAttack(a: ActorView): Boolean =
    !a.dead && !teammate(a) && self.exists { me =>
      me.id != a.id && (Look.attackable(me.look, a.look, catalog.isGuard) ||
        view.map.flatMap(catalog.map).exists(Look.duel(_, me.look, me.id, me.cell, a.look, a.cell)))
    }

  /** 同队的（#1274）：队友本人，或主人是队友的宝宝（主人要在视野里才认得出名字；认不出就当不是，服务端照样挡下）。 */
  def teammate(a: ActorView): Boolean =
    val owner = a.look.master.fold(Option(a))(actor)
    owner.map(_.look).exists {
      case h: Look.Human => view.group.members.contains(h.name)
      case _             => false
    }

  /** 自己人（治疗、加防的对象）：活着的别的玩家和宝宝（所有玩家都算；和服务端 SpellCtx.isFriend 同一口径）。 */
  def isFriend(a: ActorView): Boolean =
    !a.dead && self.exists(_.id != a.id) && a.look.side(catalog.isGuard).contains(mir.rules.Side.Players)

  /**
   * 此刻哪些格子能走进去：地形能走、没有别的玩家或 NPC 站着——人物穿怪，怪（含宝宝）不挡路（#1240，
   * 和服务端 Placement.walkableFor 同一条规则；按看得到的算，服务端最终判定）。
   * 先把站人的格子收成集合，寻路时反复查也不慢。
   */
  def passable: Cell => Boolean =
    val taken = actors.iterator.filter(a => !a.dead && !view.me.contains(a.id) && a.look.blocksPlayers).map(_.cell).toSet
    c => map.exists(_.terrain.walkable(c)) && !taken.contains(c)

  def scene(local: Double): Option[Scene] =
    for
      md <- map
      me <- view.me
    yield
      val now = clock.now(local)
      fx = fx.prune(now, local)
      Scene(
        md, me, actors, view.ground.values, view.fields.values, fx, locked,
        myShield = view.status.shieldUntil.exists(_ > now),
        meInvisible = view.status.invisibleUntil.exists(_ > now),
        server = now, local = local, options = options, upgrades = groundUpgrades
      )

  // ---------------- 发：走路 ----------------

  /**
   * 朝 dir 走一步（run 且两格都空就跑）；正前方走不了试左右偏一点。走成了返回 true。
   * 没准备好（上一步没回音、还在动作里）返回 false，什么也不发。
   */
  def walk(dir: Dir, run: Boolean): Boolean =
    val now = serverNow
    self.filter(s => !s.dead && map.isDefined && motion.ready(now) && actionDone(s, now)) match
      case None => false
      case Some(s) =>
        val tries = (if run then List(dir -> true) else Nil) ++ List(0, 1, -1, 2, -2).map(k => dir.turn(k) -> false)
        val free  = passable
        tries.find((d, r) => (1 to (if r then 2 else 1)).forall(k => free(s.cell.step(d, k)))) match
          case Some((d, r)) =>
            val (m, cmd) = motion.step(s, d, r, now)
            motion = m
            send(cmd)
            true
          case None =>
            if s.dir != dir then send(GameCmd.Turn(dir))
            false

  /** 自己在做的攻击、施法等动作完了没（服务端在动作里不让走）。 */
  def actionDone(s: ActorView, now: Millis): Boolean =
    s.action.kind == ActKind.Walk || s.action.kind == ActKind.Run || Frames.finished(s.action, now)

object GameClient:
  val RecentKeep = 20
