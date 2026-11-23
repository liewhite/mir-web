package mir.protocol

import mir.core.*
import mir.model.*
import mir.rules.Stats
import zio.json.*

/** 协议里用到的「看到的东西」：服务端只发客户端画面和界面需要的，不发怪物的内部状态。 */

/** 一个对象长什么样。 */
enum Look:
  /** 玩家：名字、职业、性别、发型，武器和衣服（物品名，客户端查数据表取外观图号） */
  case Human(name: String, job: Job, sex: Sex, hair: Int, weapon: Option[ItemName], dress: Option[ItemName])
  /** 怪物（含守卫、宝宝）：种类、藏身状态；master 是宝宝的主人；mood 是被定住/发狂（名字变色） */
  case Monster(kind: MonsterName, cover: Cover, master: Option[ActorId], mood: Mood = Mood.Calm)
  /** NPC：merchant 是 game.json merchants 里的下标（客户端据此取名字、外观、商品） */
  case Npc(merchant: MerchantRef)

object Look:
  given JsonCodec[Look] = DeriveJsonCodec.gen

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

/** 对象进入视野时的全貌。 */
final case class ActorView(id: ActorId, look: Look, cell: Cell, dir: Dir, hp: Hp, maxHp: Hp, action: ActionView, dead: Boolean)

object ActorView:
  given JsonCodec[ActorView] = DeriveJsonCodec.gen

/** 地上的东西：一件物品或一堆金币。 */
enum GroundThing:
  case Item(name: ItemName)
  case Coins(amount: Gold)

object GroundThing:
  given JsonCodec[GroundThing] = DeriveJsonCodec.gen

final case class GroundView(id: GroundId, cell: Cell, thing: GroundThing)

object GroundView:
  given JsonCodec[GroundView] = DeriveJsonCodec.gen

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

/** 聊天：普通说话（附近的人看到）、喊话（全图，原版「!」开头）。 */
enum ChatKind:
  case Say, Shout

object ChatKind:
  given JsonCodec[ChatKind] = EnumCodec.byName(values, _.toString)

/** 选人界面的一个角色。 */
final case class CharSummary(id: CharId, name: String, job: Job, sex: Sex, level: Int)

object CharSummary:
  given JsonCodec[CharSummary] = DeriveJsonCodec.gen

/** 自己的数值面板：等级、经验、血魔、金币、负重、最终属性。 */
final case class SelfStats(
    level: Int,
    exp: Exp,
    /** 升下一级要的经验；满级为空 */
    expToNext: Option[Exp],
    hp: Hp,
    mp: Mp,
    gold: Gold,
    bagWeight: Int,
    wearWeight: Int,
    stats: Stats
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
