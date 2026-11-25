package mir.protocol

import mir.core.*
import mir.data.{MapDef, Span}
import mir.model.*
import mir.rules.{PkStanding, SafeZone, Side, Stats}
import zio.json.*

/** 协议里用到的「看到的东西」：服务端只发客户端画面和界面需要的，不发怪物的内部状态。 */

/** 一个对象长什么样。 */
enum Look:
  /** 玩家：名字、职业、性别、发型，武器和衣服（物品名，客户端查数据表取外观图号），PK 身份（名字颜色，#1225） */
  case Human(
      name: String,
      job: Job,
      sex: Sex,
      hair: Int,
      weapon: Option[ItemName],
      dress: Option[ItemName],
      pk: PkStanding = PkStanding.Clean
  )
  /** 怪物（含守卫、宝宝）：种类、藏身状态；pet 是宝宝的主人和实际攻防（野怪没有）；mood 是被定住/发狂（名字变色） */
  case Monster(kind: MonsterName, cover: Cover, pet: Option[PetView], mood: Mood = Mood.Calm)
  /** NPC：merchant 是 game.json merchants 里的下标（客户端据此取名字、外观、商品） */
  case Npc(merchant: MerchantRef)

  /** 阵营（NPC 没有）；isGuard 认哪种怪是守卫（数据表 Catalog.isGuard）。 */
  def side(isGuard: MonsterName => Boolean): Option[Side] = this match
    case _: Human                           => Some(Side.Players)
    case m: Monster if m.master.isDefined   => Some(Side.Players)
    case m: Monster if isGuard(m.kind)      => Some(Side.Guards)
    case m: Monster if m.mood == Mood.Crazy => Some(Side.Crazy)
    case _: Monster                         => Some(Side.Wild)
    case _: Npc                             => None

  /** 宝宝的主人（野怪、玩家、NPC 没有）。 */
  def master: Option[ActorId] = this match
    case m: Monster => m.pet.map(_.master)
    case _          => None

  /** 挡不挡玩家走路：人物穿怪（#1240），怪（含宝宝、守卫）不挡，玩家和 NPC 挡。服务端 Placement.walkableFor 同一条。 */
  def blocksPlayers: Boolean = this match
    case _: Monster => false
    case _          => true

  /** 露在外面打得到（埋着的、石像打不到）。 */
  def exposed: Boolean = this match
    case m: Monster => m.cover == Cover.Open
    case _          => true

/**
 * 宝宝（召唤的或诱惑来的）：主人和此刻实际的攻击、防御、魔防（#1232）。召唤时按技能等级和道术放大过，
 * 和怪物表的原值不同，所以要服务端发；变了（如加了防御类法术）随外观变化一起更新。
 */
final case class PetView(master: ActorId, dc: Span, ac: Span, mac: Span)

object PetView:
  given JsonCodec[PetView] = DeriveJsonCodec.gen

object Look:
  given JsonCodec[Look] = DeriveJsonCodec.gen

  /**
   * 长这样的 a 会不会把长这样的 b 当目标：a 的阵营打 b 的阵营（有方向，Side.attacks）、b 露在外面。
   * 服务端结算和客户端选目标共用；死活、是不是同一个由调用方看（外观里没有）。
   */
  def hostile(a: Look, b: Look, isGuard: MonsterName => Boolean): Boolean =
    b.exposed && ((a.side(isGuard), b) match
      // 守卫见了红名就砍（#1225，原版 SuperGuard.Run / GuardUnit.IsProperTarget：PKLevel >= 2）
      case (Some(Side.Guards), h: Human) => h.pk == PkStanding.Red
      case (Some(x), _)                  => b.side(isGuard).exists(Side.attacks(x, _))
      case _                             => false)

  /**
   * PK（#1225）：编号 aId、长这样的 a 能不能打 b——a 是玩家，b 是别的玩家或别人的宝宝（自己的宝宝不打）。
   * 不分攻击模式（用户定）；队友打不着（#1274）不在这里看（外观里没有组队），由调用方另加：Hostility.allied / GameClient.teammate；双方都要在安全区外（原版 IsProtectTarget；打宝宝看宝宝站在哪，同原版 IsProperTarget）。
   * 服务端结算（Justice.duel）和客户端锁定攻击（GameClient.canAttack）共用。
   */
  def duel(map: MapDef, a: Look, aId: ActorId, aCell: Cell, b: Look, bCell: Cell): Boolean =
    val kinds = (a, b) match
      case (_: Human, _: Human)   => true
      case (_: Human, m: Monster) => m.master.exists(_ != aId)
      case _                      => false
    kinds && !SafeZone.covers(map, aCell) && !SafeZone.covers(map, bCell)

  /**
   * 长这样的 a 能不能亲手打长这样的 b：敌对的，或者是玩家打守卫（#1205，同原版：守卫能选中来打，挨了打会还手）。
   * 只管玩家亲手选的单个目标（锁定后攻击键、单体技能、普攻）；范围攻击、自动找怪、宝宝仍只认 hostile，不会顺手打到守卫。
   */
  def attackable(a: Look, b: Look, isGuard: MonsterName => Boolean): Boolean =
    hostile(a, b, isGuard) || (a.isInstanceOf[Human] && b.exposed && b.side(isGuard).contains(Side.Guards))

/** 一个动作：种类、开始时刻、持续多久；移动的起点；攻击的种类；施法的特效编号。 */
final case class ActionView(
    kind: ActKind,
    startedAt: Millis,
    duration: Int,
    from: Option[Cell] = None,
    hit: Option[HitStyle] = None,
    spellEffect: Option[Int] = None
)

object ActionView:
  given JsonCodec[ActionView] = DeriveJsonCodec.gen

/** 对象进入视野时的全貌；afflictions 是身上看得见的中毒、麻痹（锁定卡列出、身子按它染色），auras 是身上的光效（魔法盾）。 */
final case class ActorView(
    id: ActorId,
    look: Look,
    cell: Cell,
    dir: Dir,
    hp: Hp,
    maxHp: Hp,
    action: ActionView,
    dead: Boolean,
    afflictions: Set[Affliction] = Set.empty,
    auras: Set[Aura] = Set.empty
)

object ActorView:
  given JsonCodec[ActorView] = DeriveJsonCodec.gen

/**
 * 地上的东西：一件物品或一堆金币。物品带极品附加（#1191，有就是极品，名字换色）：
 * 客户端拿它和自己身上的比，比身上的强的名字另换一种颜色（#1200）。
 */
enum GroundThing:
  case Item(name: ItemName, bonus: Option[ItemBonus] = None)
  case Coins(amount: Gold)

object GroundThing:
  given JsonCodec[GroundThing] = DeriveJsonCodec.gen

final case class GroundView(id: GroundId, cell: Cell, thing: GroundThing)

object GroundView:
  given JsonCodec[GroundView] = DeriveJsonCodec.gen

/**
 * 查看别的玩家的装备（#1263，原版 SM_SENDUSERSTATE / TUserStateInfo）：点那一刻的快照——名字（按 PK 身份上色）、
 * 性别发型（画人形）、身上穿戴的各件（含极品附加、耐久），不含包裹。原版还有行会名，这里没有行会。
 */
final case class InspectView(name: String, sex: Sex, hair: Int, pk: PkStanding, worn: Map[EquipSlot, ItemInstance])

object InspectView:
  given JsonCodec[InspectView] = DeriveJsonCodec.gen

/** 地上持续的法术一格（火墙、困魔咒光圈）：进视野时发，没了发消失（原版 ClEvent）。 */
final case class FieldView(id: FieldId, cell: Cell, kind: FieldKind)

object FieldView:
  given JsonCodec[FieldView] = DeriveJsonCodec.gen

/** 特效放在哪：某一格，或跟着某个对象。 */
enum Anchor:
  case OnCell(cell: Cell)
  case OnActor(id: ActorId)

object Anchor:
  given JsonCodec[Anchor] = DeriveJsonCodec.gen

/**
 * 特效：图库 + 起始帧 + 帧数，放在格子或对象身上，可从 from 飞到 at（与 TS 版 VisualEffect 一致）。
 * dirStride：飞行特效每个方向的帧组间隔（如火球 10 帧一组）。
 */
final case class EffectView(
    lib: String,
    start: Int,
    frames: Int,
    frameTime: Int,
    at: Anchor,
    from: Option[Cell] = None,
    dir: Option[Dir] = None,
    dirStride: Option[Int] = None,
    blend: Boolean,
    startedAt: Millis,
    duration: Int
)

object EffectView:
  given JsonCodec[EffectView] = DeriveJsonCodec.gen

/** 系统提示的颜色：普通、好事、坏事、物品。 */
enum Tone:
  case System, Good, Bad, Item

object Tone:
  given JsonCodec[Tone] = EnumCodec.byName(values, _.toString)

/** 聊天：普通说话（附近的人看到）、喊话（全图，原版「!」开头）、组队（全队不管在哪张图，原版「!!」开头，#1220）。 */
enum ChatKind:
  case Say, Shout, Group

object ChatKind:
  given JsonCodec[ChatKind] = EnumCodec.byName(values, _.toString)

/**
 * 自己的组队状态（#1220，原版 SM_GROUPMODECHANGED + SM_GROUPMEMBERS）：允许别人拉我进组的开关、
 * 所在小组的成员名字（第一个是队长，按入队先后；没组队为空）。
 */
final case class GroupView(allowGroup: Boolean = false, members: List[String] = Nil):
  def leader: Option[String]          = members.headOption
  def isLeader(me: String): Boolean   = leader.contains(me)
  def has(name: String): Boolean      = members.contains(name)

object GroupView:
  given JsonCodec[GroupView] = DeriveJsonCodec.gen

/** 好友名单上的一个人（#1221）：名字、现在在不在线。 */
final case class FriendView(name: String, online: Boolean)

object FriendView:
  given JsonCodec[FriendView] = DeriveJsonCodec.gen

/** 交易栏一边（#1222）：放了哪些东西（数量是放进去的个数）、多少金币、确认了没有。 */
final case class DealOffer(items: List[ItemInstance] = Nil, gold: Gold = Gold.zero, confirmed: Boolean = false):
  def isEmpty: Boolean = items.isEmpty && gold == Gold.zero

object DealOffer:
  given JsonCodec[DealOffer] = DeriveJsonCodec.gen

/** 交易窗口：和谁交易、我这边、对方那边。 */
final case class DealView(partner: String, mine: DealOffer, theirs: DealOffer)

object DealView:
  given JsonCodec[DealView] = DeriveJsonCodec.gen

/** 一句私聊是别人发给我的，还是我发出去的（服务端确认送到后回给发的人）。 */
enum WhisperWay:
  case Received, Sent

object WhisperWay:
  given JsonCodec[WhisperWay] = EnumCodec.byName(values, _.toString)

/** 选人界面的一个角色。 */
final case class CharSummary(id: CharId, name: String, job: Job, sex: Sex, level: Int)

object CharSummary:
  given JsonCodec[CharSummary] = DeriveJsonCodec.gen

/** 自己的数值面板：等级、经验、血魔、金币、最终属性。 */
final case class SelfStats(
    level: Int,
    exp: Exp,
    /** 升下一级要的经验；满级为空 */
    expToNext: Option[Exp],
    hp: Hp,
    mp: Mp,
    gold: Gold,
    stats: Stats,
    /** PK 值（#1225，Pk 规则） */
    pkPoints: Int = 0
)

object SelfStats:
  given JsonCodec[SelfStats] = DeriveJsonCodec.gen

/** 自己身上的状态：中毒、魔法盾、隐身、麻痹、神圣战甲术（加防御）、幽灵盾（加魔防）的结束时刻（没有为空）。 */
final case class SelfStatus(
    poison: Option[PoisonKind] = None,
    poisonUntil: Option[Millis] = None,
    shieldUntil: Option[Millis] = None,
    invisibleUntil: Option[Millis] = None,
    paralyzedUntil: Option[Millis] = None,
    acUpUntil: Option[Millis] = None,
    macUpUntil: Option[Millis] = None
)

object SelfStatus:
  given JsonCodec[SelfStatus] = DeriveJsonCodec.gen

/** 战士剑术状态：刺杀、半月开关，烈火是否蓄好。 */
final case class SwordState(longHitOn: Boolean, wideHitOn: Boolean, fireReady: Boolean)

object SwordState:
  given JsonCodec[SwordState] = DeriveJsonCodec.gen

/** 可开关的剑术。 */
enum SwordArt:
  /** 刺杀剑术 */
  case LongHit
  /** 半月弯刀 */
  case WideHit

object SwordArt:
  given JsonCodec[SwordArt] = EnumCodec.byName(values, _.toString)
