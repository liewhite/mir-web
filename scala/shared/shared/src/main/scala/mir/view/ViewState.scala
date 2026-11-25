package mir.view

import mir.core.*
import mir.model.*
import mir.protocol.*

/**
 * 客户端的「视野副本」：把服务端发来的 ServerMsg 依次套上去得到的只读状态，渲染和界面都读它。
 * 纯函数：apply(msg) 返回新状态，不做任何 IO。不认识的对象编号（还没出现就来的动作）忽略。
 * 特效、声音、飘字、聊天这类一次性的事不进状态，由客户端在收到时直接播放。
 */
final case class ViewState(
    me: Option[ActorId] = None,
    map: Option[MapId] = None,
    actors: Map[ActorId, ActorView] = Map.empty,
    ground: Map[GroundId, GroundView] = Map.empty,
    fields: Map[FieldId, FieldView] = Map.empty,
    stats: Option[SelfStats] = None,
    bag: Vector[Option[ItemInstance]] = Vector.fill(Capacity.bag)(None),
    belt: Vector[Option[ItemInstance]] = Vector.fill(Capacity.belt)(None),
    worn: Map[EquipSlot, ItemInstance] = Map.empty,
    storage: List[ItemInstance] = Nil,
    skills: List[LearnedSkill] = Nil,
    /** 技能 → 可再用的时刻（服务端时钟） */
    cooldowns: Map[SkillId, Millis] = Map.empty,
    status: SelfStatus = SelfStatus(),
    sword: SwordState = SwordState(longHitOn = true, wideHitOn = false, fireReady = false),
    settings: CharSettings = CharSettings(),
    /** 组队状态（#1220；换图不清） */
    group: GroupView = GroupView(),
    /** 好友名单（#1221） */
    friends: List[FriendView] = Nil,
    /** 交易窗口（#1222；换图时清掉：人一走服务端就取消了） */
    deal: Option[DealView] = None,
    /** 服务端时钟与本地时钟之差（EnterMap / Pong 时更新由客户端算，这里只记服务端给的时刻） */
    serverNow: Option[Millis] = None
):
  def self: Option[ActorView] = me.flatMap(actors.get)

  def apply(msg: ServerMsg): ViewState = msg match
    case ServerMsg.EnterMap(you, map, _, _, now) =>
      copy(me = Some(you), map = Some(map), actors = Map.empty, ground = Map.empty, fields = Map.empty, deal = None, serverNow = Some(now))
    case ServerMsg.Appear(a)       => copy(actors = actors.updated(a.id, a))
    case ServerMsg.Disappear(id)   => copy(actors = actors - id)
    case ServerMsg.Act(id, cell, dir, action) =>
      update(id)(_.copy(cell = cell, dir = dir, action = action, dead = ViewState.isDeath(action.kind)))
    case ServerMsg.HpChanged(id, hp, maxHp) => update(id)(_.copy(hp = hp, maxHp = maxHp))
    case ServerMsg.LookChanged(id, look)    => update(id)(_.copy(look = look))
    case ServerMsg.AfflictionsChanged(id, af) => update(id)(_.copy(afflictions = af))
    case ServerMsg.AurasChanged(id, au)       => update(id)(_.copy(auras = au))
    case ServerMsg.GroundAppear(g)          => copy(ground = ground.updated(g.id, g))
    case ServerMsg.GroundDisappear(id)      => copy(ground = ground - id)
    case ServerMsg.FieldAppear(f)           => copy(fields = fields.updated(f.id, f))
    case ServerMsg.FieldDisappear(id)       => copy(fields = fields - id)
    case ServerMsg.Stats(s)                 => copy(stats = Some(s))
    case ServerMsg.Bag(b, t)                => copy(bag = b, belt = t)
    case ServerMsg.Equipment(w)             => copy(worn = w)
    case ServerMsg.Storage(items)           => copy(storage = items)
    case ServerMsg.Skills(s)                => copy(skills = s)
    case ServerMsg.Cooldowns(c)             => copy(cooldowns = c)
    case ServerMsg.Status(s)                => copy(status = s)
    case ServerMsg.Sword(s)                 => copy(sword = s)
    case ServerMsg.Settings(s)              => copy(settings = s)
    case ServerMsg.Group(g)                 => copy(group = g)
    case ServerMsg.Friends(f)               => copy(friends = f)
    case ServerMsg.Deal(d)                  => copy(deal = d)
    case ServerMsg.Pong(_, server)          => copy(serverNow = Some(server))
    // 一次性的事、会话消息：不改状态
    case ServerMsg.LoggedIn(_, _, _, _) | ServerMsg.CharList(_) | ServerMsg.Kicked(_) | ServerMsg.Effect(_) | ServerMsg.Sound(_, _) |
        ServerMsg.Damage(_, _, _) | ServerMsg.Miss(_) | ServerMsg.Heal(_, _) | ServerMsg.MoveOk | ServerMsg.MoveDenied(_, _) |
        ServerMsg.Refused(_) | ServerMsg.Notice(_, _) | ServerMsg.LevelUp(_) | ServerMsg.Died | ServerMsg.NpcOpened(_, _) |
        ServerMsg.Chat(_, _, _) | ServerMsg.Whisper(_, _, _) | ServerMsg.Announce(_) | ServerMsg.Inspected(_) =>
      this

  def applyAll(msgs: Iterable[ServerMsg]): ViewState = msgs.foldLeft(this)(_.apply(_))

  private def update(id: ActorId)(f: ActorView => ActorView): ViewState =
    actors.get(id).fold(this)(a => copy(actors = actors.updated(id, f(a))))

object ViewState:
  /** 倒下、躺着的动作意味着死了；其余动作（含僵尸复活的钻出）都是活着。 */
  def isDeath(k: ActKind): Boolean = k == ActKind.Die || k == ActKind.Dead
