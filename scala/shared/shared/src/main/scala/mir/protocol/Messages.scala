package mir.protocol

import mir.core.*
import mir.model.*
import zio.json.*

/**
 * 客户端 → 服务端的指令。客户端只表达意图，一切结果由服务端判定（服务端权威）。
 * 清单见 #1148 21:19 评论；参考原版 Grobal2.pas 的 CM_* 消息。
 * 分三类：会话指令（登录、选人、对时）由服务端连接处理；游戏指令交给角色所在地图的世界（mir.world.World）；
 * 社交指令（组队等跨地图的事）交给服务端的 mir.social。
 * JSON 里三类一样是「分支名作外层键」，分支名不重复。
 */
type ClientCmd = SessionCmd | GameCmd | SocialCmd

/** 会话指令：还没进游戏也能发。 */
enum SessionCmd:
  case Login(account: String, password: String)
  case ListChars
  case CreateChar(name: String, job: Job, sex: Sex)
  case DeleteChar(id: CharId)
  case EnterGame(id: CharId)
  /** 对时：clientMs 是客户端发出时的本地时钟，服务端原样带回 */
  case Ping(clientMs: Long)

object SessionCmd:
  given JsonCodec[SessionCmd] = DeriveJsonCodec.gen

  /**
   * ClientCmd（三类的并）的编解码。放在这里是因为并类型的隐式作用域是各边的伴生对象，用的地方不用额外 import。
   */
  given clientCmd: JsonCodec[ClientCmd] =
    val encoder = new JsonEncoder[ClientCmd]:
      def unsafeEncode(a: ClientCmd, indent: Option[Int], out: zio.json.internal.Write): Unit = a match
        case s: SessionCmd => JsonEncoder[SessionCmd].unsafeEncode(s, indent, out)
        case g: GameCmd    => JsonEncoder[GameCmd].unsafeEncode(g, indent, out)
        case s: SocialCmd  => JsonEncoder[SocialCmd].unsafeEncode(s, indent, out)
    val decoder = JsonDecoder[SessionCmd].widen[ClientCmd]
      .orElse(JsonDecoder[GameCmd].widen[ClientCmd])
      .orElse(JsonDecoder[SocialCmd].widen[ClientCmd])
    JsonCodec(encoder, decoder)

/** 游戏指令：进了游戏后对世界的操作。 */
enum GameCmd:
  // ---- 移动（原版做法：带上起点格，服务端回 MoveOk / MoveDenied；回之前客户端不再发下一个动作） ----
  case Walk(from: Cell, dir: Dir)
  case Run(from: Cell, dir: Dir)
  case Turn(dir: Dir)

  // ---- 战斗 ----
  /** 普通攻击：服务端按剑术开关算成攻杀、刺杀、半月或烈火；target 是锁定的目标（按住攻击用） */
  case Attack(dir: Dir, target: Option[ActorId])
  /** 放技能：目标对象（没有为空）和目标格 */
  case Cast(skill: SkillId, target: Option[ActorId], cell: Cell)
  /** 开关刺杀、半月 */
  case SetSword(art: SwordArt, on: Boolean)

  // ---- 交互 ----
  /** 点 NPC：打开对话 */
  case TalkTo(npc: ActorId)
  /** 捡起脚下的东西 */
  case PickUp
  /** 死后复活回城 */
  case Revive
  /** 用传送菜单传送：dest 是 game.json teleports 里的下标 */
  case Teleport(dest: TeleportRef)

  // ---- 物品 ----
  /** 使用或吃（装备类等于穿上） */
  case UseItem(uid: ItemUid)
  /** 穿上；slot 指定左右手镯/戒指位，空则自动选 */
  case Equip(uid: ItemUid, slot: Option[EquipSlot])
  case Unequip(slot: EquipSlot)
  /** 在包裹、快捷栏之间挪位置（目标格有东西就互换） */
  case MoveItem(uid: ItemUid, to: ItemPlace)
  /** 扔到地上 */
  case DropItem(uid: ItemUid)
  /** 随时出售（#1178，不用找 NPC）：包裹或快捷栏里的一件换成金币，价钱同商店收购；count 同 Sell */
  case QuickSell(uid: ItemUid, count: Option[Int] = None)

  // ---- NPC ----
  case Buy(npc: ActorId, item: ItemName)
  /** 卖出；count 是一叠里卖几个，空为整叠（#1159） */
  case Sell(npc: ActorId, uid: ItemUid, count: Option[Int] = None)
  /** 存进仓库；count 同上 */
  case Deposit(npc: ActorId, uid: ItemUid, count: Option[Int] = None)
  /** 从仓库取；count 同上 */
  case Withdraw(npc: ActorId, uid: ItemUid, count: Option[Int] = None)

  // ---- 其他 ----
  /** 设技能快捷键：key 1-8 对应 F1-F8，空为取消 */
  case SetSkillKey(skill: SkillId, key: Option[Int])
  /** 改挂在角色上的便利功能（自动喝药、自动捡物等） */
  case UpdateSettings(settings: CharSettings)
  /** 说话：「!」开头是全图喊话 */
  case Say(text: String)

object GameCmd:
  given JsonCodec[GameCmd] = DeriveJsonCodec.gen

/**
 * 社交指令：牵涉别的在线玩家、不管在哪张图的事，由服务端的 mir.social 处理（不进某张图的世界）。
 * 人按角色名字指（全服唯一）。组队照原版 CM_GROUPMODE / CM_CREATEGROUP / CM_ADDGROUPMEMBER / CM_DELGROUPMEMBER（#1220）。
 */
enum SocialCmd:
  /** 允许别人拉我进组；队员关掉就是退出小组（队长关不掉，要「删除」自己解散全队） */
  case AllowGroup(on: Boolean)
  /** 和另一个人建一个小组（自己当队长） */
  case CreateGroup(name: String)
  /** 队长拉人进组 */
  case AddMember(name: String)
  /** 队长把人踢出组；删的是自己就解散全队 */
  case RemoveMember(name: String)
  /** 队伍聊天（原版「!!」开头）：发给全队 */
  case GroupSay(text: String)
  /** 私聊（#1221，原版「/名字 内容」）：对方不在线时回一句提示 */
  case Whisper(to: String, text: String)
  /** 加好友（#1221）：名字是已有的角色就行，不用在线、不用对方同意 */
  case AddFriend(name: String)
  case RemoveFriend(name: String)

object SocialCmd:
  given JsonCodec[SocialCmd] = DeriveJsonCodec.gen

/**
 * 聊天框里打的一行字发成什么指令（原版 M2Server TPlayObject.ProcessUserLineMsg）：
 * 「/名字 内容」私聊，「!!」开头是队伍聊天，其余交给世界（「!」开头全图喊话由世界认）。
 */
object Speech:
  val GroupPrefix   = "!!"
  val WhisperPrefix = "/"

  def command(text: String): ClientCmd =
    if text.startsWith(WhisperPrefix) then
      val (to, rest) = text.drop(WhisperPrefix.length).trim.span(!_.isWhitespace)
      SocialCmd.Whisper(to, rest.trim)
    else if text.startsWith(GroupPrefix) then SocialCmd.GroupSay(text.drop(GroupPrefix.length).trim)
    else GameCmd.Say(text)

  /** 私聊某人时聊天框里预填的字 */
  def whisperTo(name: String): String = s"$WhisperPrefix$name "

/**
 * 服务端 → 客户端的事件。视野类消息只发给看得见的人；「自己」类消息变了才发。
 * 参考原版 Grobal2.pas 的 SM_* 消息。
 */
enum ServerMsg:
  // ---- 登录、选人 ----
  case LoggedIn(account: String)
  case CharList(chars: List[CharSummary])
  /** 被踢下线（同一角色在别处登录等） */
  case Kicked(reason: String)

  // ---- 进游戏、换图 ----
  /** 进入一张地图：我的对象编号、地图、位置、朝向、服务器此刻的时钟 */
  case EnterMap(you: ActorId, map: MapId, cell: Cell, dir: Dir, now: Millis)

  // ---- 视野 ----
  case Appear(actor: ActorView)
  case Disappear(id: ActorId)
  /** 对象开始做一个动作（走、跑、攻击、施法、被打、死亡、钻出钻回……），cell 是动作结束时所在格 */
  case Act(id: ActorId, cell: Cell, dir: Dir, action: ActionView)
  case HpChanged(id: ActorId, hp: Hp, maxHp: Hp)
  /** 外观变了（换武器衣服、怪物钻出/钻回、石像醒来） */
  case LookChanged(id: ActorId, look: Look)
  /** 身上看得见的中毒、麻痹变了 */
  case AfflictionsChanged(id: ActorId, afflictions: Set[Affliction])

  // ---- 地上的东西 ----
  case GroundAppear(item: GroundView)
  case GroundDisappear(id: GroundId)

  // ---- 地上持续的法术（火墙、困魔咒） ----
  case FieldAppear(field: FieldView)
  case FieldDisappear(id: FieldId)

  // ---- 特效、声音、飘字（只发给附近的人） ----
  case Effect(effect: EffectView)
  case Sound(id: Int, at: Option[Cell])
  case Damage(target: ActorId, amount: Int, source: Option[ActorId])
  case Miss(target: ActorId)
  case Heal(target: ActorId, amount: Int)

  // ---- 我自己（变了才发） ----
  case Stats(stats: SelfStats)
  /** 包裹 40 格、快捷栏 6 格（空格为 None） */
  case Bag(bag: Vector[Option[ItemInstance]], belt: Vector[Option[ItemInstance]])
  case Equipment(worn: Map[EquipSlot, ItemInstance])
  case Storage(items: List[ItemInstance])
  case Skills(skills: List[LearnedSkill])
  /** 技能冷却：技能 → 可再用的时刻（服务端时钟；界面画技能键上的冷却遮罩） */
  case Cooldowns(ready: Map[SkillId, Millis])
  case Status(status: SelfStatus)
  case Sword(state: SwordState)
  case Settings(settings: CharSettings)

  // ---- 动作结果 ----
  /** 这一步可以（客户端可以发下一个动作） */
  case MoveOk
  /** 这一步不行：拉回到 cell（原版 1 秒内不能再走） */
  case MoveDenied(cell: Cell, dir: Dir)
  /** 操作没成功的原因，如「金币不够」「需要等级 22」 */
  case Refused(reason: String)

  // ---- 个人消息 ----
  case Notice(text: String, tone: Tone)
  case LevelUp(level: Int)
  case Died
  /** 打开 NPC 对话（商店、仓库）：npc 是对象编号，merchant 是 game.json merchants 下标 */
  case NpcOpened(npc: ActorId, merchant: MerchantRef)

  // ---- 聊天、社交 ----
  case Chat(from: String, text: String, kind: ChatKind)
  /** 自己的组队状态变了（进游戏时也发一次，换图不变） */
  case Group(group: GroupView)
  /** 私聊（#1221）：peer 是对方（收到时是发的人，发出时是收的人） */
  case Whisper(peer: String, text: String, way: WhisperWay)
  /** 好友名单和各自在线与否（进游戏、增删、好友上下线时发整份） */
  case Friends(friends: List[FriendView])

  /** 对时回应：带回客户端的 clientMs 和服务器此刻的时钟 */
  case Pong(clientMs: Long, server: Millis)

object ServerMsg:
  given JsonCodec[ServerMsg] = DeriveJsonCodec.gen
