package mir.world

import mir.core.*
import mir.data.{MerchantDef, MonsterDef, MonsterLook, Span}
import mir.model.*
import mir.protocol.{ActionView, FieldView, GroundThing, GroundView, Look}
import mir.rules.{Skill, Stats}

import scala.collection.mutable

/**
 * 世界里的对象：玩家、怪物、NPC。只有状态；改状态的规则在 World 的各部分和 Combat、MonsterAi、PlayerOps 里。
 * 格子、朝向、动作只由 Placement 改（它负责占位），血量、蓝量、血上限只由 Vitals 改（OwnershipSpec 按源码查）；
 * 这里的 var 对 world 包以外不开放写。
 */

/** 喝下的一瓶普通药还没回完的部分：每跳回多少、还剩几跳、下一跳的时刻。 */
final case class PotionCourse(hpPerTick: Int, mpPerTick: Int, ticksLeft: Int, next: Millis):
  /** 跳完这一下之后（没有了是 None） */
  def advanced: Option[PotionCourse] =
    Option.when(ticksLeft > 1)(copy(ticksLeft = ticksLeft - 1, next = next + mir.rules.PotionRules.TickMs.toLong))

/** 加防御上限的法术效果（神圣战甲术、幽灵盾）加到一个范围上。 */
private def raised(span: Span, amount: Option[Int]): Span =
  amount.fold(span)(n => Span(span.lo, math.min(255, span.hi + n)))

sealed abstract class Actor(val id: ActorId, initCell: Cell, initDir: Dir, now: Millis):
  private[world] var cell: Cell           = initCell
  private[world] var dir: Dir             = initDir
  private[world] var hp: Int              = 1
  private[world] var action: ActionView   = ActionView(ActKind.Stand, now, 0)
  /** 下一次能接受新动作的时刻 */
  private[world] var busyUntil: Millis    = Millis.zero
  private[world] var dead: Boolean        = false
  /** 身上的限时状态：中毒、魔法盾、隐身、麻痹、战甲、幽灵盾、定住、发狂 */
  private[world] val statuses: Statuses   = Statuses()
  /** 最近打过我的对象（还手、经验归属） */
  private[world] var lastHitBy: Option[ActorId] = None

  def maxHp: Int
  def look: Look
  def ac: Span
  def mac: Span
  def agility: Int

  def currentCell: Cell    = cell
  def currentDir: Dir      = dir
  def currentHp: Int       = hp
  def isDead: Boolean      = dead
  def currentAction: ActionView = action

  /** 加上神圣战甲术 / 幽灵盾之后的防御、魔防 */
  private[world] def buffedAc(base: Span): Span  = raised(base, statuses.find { case Status.ArmorUp(n) => n })
  private[world] def buffedMac(base: Span): Span = raised(base, statuses.find { case Status.MagicArmorUp(n) => n })

/** 玩家：角色存档里的全部内容 + 在线时才有的战斗状态。 */
final class Player(id: ActorId, val charName: String, val job: Job, val sex: Sex, val hair: Int, cell: Cell, dir: Dir, now: Millis)
    extends Actor(id, cell, dir, now):
  private[world] var level: Int  = 1
  private[world] var exp: Exp    = Exp.zero
  private[world] var gold: Gold  = Gold.zero
  private[world] var mp: Int     = 0
  private[world] val bag: Array[Option[ItemInstance]]  = Array.fill(Capacity.bag)(None)
  private[world] val belt: Array[Option[ItemInstance]] = Array.fill(Capacity.belt)(None)
  private[world] val worn: mutable.Map[EquipSlot, ItemInstance] = mutable.Map.empty
  private[world] val storage: mutable.ArrayBuffer[ItemInstance] = mutable.ArrayBuffer.empty
  private[world] val skills: mutable.ArrayBuffer[LearnedSkill]  = mutable.ArrayBuffer.empty
  private[world] var settings: CharSettings = CharSettings()
  private[world] var stats: Stats = scala.compiletime.uninitialized
  private[world] var home: Place  = Place(MapId("0"), cell)
  // 战士剑术：攻杀计数、烈火是否蓄好、刺杀/半月开关
  private[world] var powerHitReady = false
  private[world] var powerHitCount = 0
  private[world] var powerHitPoint = 0
  private[world] var fireReady     = false
  private[world] var longHitOn     = true
  private[world] var wideHitOn     = false
  /** 技能冷却：技能 → 可再用的时刻 */
  private[world] val cooldown: mutable.Map[SkillId, Millis] = mutable.Map.empty
  /** 宝宝 */
  private[world] val pets: mutable.ArrayBuffer[ActorId] = mutable.ArrayBuffer.empty
  private[world] var lastRegen: Millis = now
  // 自动功能（AutoPlay）
  private[world] var intent: Intent      = Intent.Idle
  /** 玩家亲手走动后的一小段时间里不自动走动（免得和摇杆抢） */
  private[world] var manualUntil: Millis = Millis.zero
  private[world] var lastPotion: Millis  = Millis.zero
  /** 还在回血回蓝的普通药（可以叠着喝） */
  private[world] var potions: List[PotionCourse] = Nil

  def maxHp: Int   = stats.maxHp
  def ac: Span     = stats.ac
  def mac: Span    = stats.mac
  def agility: Int = stats.agility
  def look: Look   = Look.Human(charName, job, sex, hair, worn.get(EquipSlot.Weapon).map(_.name), worn.get(EquipSlot.Dress).map(_.name))

  def skill(id: SkillId): Option[LearnedSkill] = skills.find(_.id == id)
  def knows(s: Skill): Boolean                 = skill(s.id).isDefined
  /** 学会的等级，没学会为 0 */
  def skillLevel(s: Skill): Int                = skill(s.id).fold(0)(_.level)

/** 玩家在服务端执行的自动意图。 */
enum Intent:
  case Idle
  /** 打这个目标直到它死（once：只打一下） */
  case Hunt(target: ActorId, once: Boolean)
  /** 走过去捡这件东西 */
  case Fetch(item: GroundId)

/** 怪物（含守卫、宝宝）。 */
final class Monster(id: ActorId, val defn: MonsterDef, val behavior: Behavior, initSpawn: Option[Int], cell: Cell, dir: Dir, now: Millis)
    extends Actor(id, cell, dir, now):
  /** 刷怪点（被诱惑收服后不再算它的，刷怪点会补一只） */
  private[world] var spawn: Option[Int] = initSpawn
  private[world] var maxHpNow: Int  = defn.hp
  private[world] var dc: Span       = defn.dc
  private[world] var target: Option[ActorId] = None
  private[world] var nextThink: Millis  = now
  private[world] var lastAttack: Millis = Millis.zero
  /** 召唤物或被诱惑的怪：主人 */
  private[world] var master: Option[ActorId] = None
  private[world] var petLevel: Int = 0
  /** 尸体消失时刻 */
  private[world] var removeAt: Millis = Millis.zero
  private[world] var cover: Cover   = Cover.Open
  private[world] var coverAt: Millis = Millis.zero
  private[world] var bornAt: Millis  = now
  /** 攻击、走路间隔（毫秒）：平时取数据表；宝宝按等级变快、本领（如沃玛教主狂暴）也会改 */
  private[world] var attackInterval: Int = defn.attackSpeed
  private[world] var walkInterval: Int   = defn.walkSpeed
  /** 自己召出来的小怪（放小怪的出招和召唤本领共用，用来限数量） */
  private[world] val minions: mutable.ArrayBuffer[ActorId] = mutable.ArrayBuffer.empty
  /** 附加本领各自的状态（顺序同 behavior.extras，刷出来时由 MonsterAi.init 设好） */
  private[world] var extras: Vector[ExtraState] = Vector.empty
  hp = defn.hp

  /** 打死给的经验倍数（僵尸复活一次减半） */
  def expScale: Double = extras.map(_.expScale).product

  def maxHp: Int   = maxHpNow
  def ac: Span     = buffedAc(defn.ac)
  def mac: Span    = buffedMac(defn.mac)
  def agility: Int = defn.speed
  def hit: Int     = defn.hit
  def isGuard: Boolean = defn.isGuard
  /** 按外观的画法和特效 */
  val style: MonsterLook = MonsterLook.of(defn)
  /** 此刻的状态（定住优先；宝宝不会发狂），也是名字颜色 */
  def mood: Mood =
    if statuses.has(StatusKind.Seized) then Mood.Seized
    else if statuses.has(StatusKind.Crazy) && master.isEmpty then Mood.Crazy
    else Mood.Calm
  def look: Look   = Look.Monster(defn.name, cover, master, mood)

/** NPC：商人、仓库。 */
final class Npc(id: ActorId, val ref: MerchantRef, val defn: MerchantDef, now: Millis)
    extends Actor(id, defn.at, Dir.Down, now):
  def maxHp: Int   = 1
  def ac: Span     = Span.zero
  def mac: Span    = Span.zero
  def agility: Int = 0
  def look: Look   = Look.Npc(ref)

/** 地上的东西：一件物品（带实例，捡起来还是它）或一堆金币。 */
enum Loot:
  case Item(item: ItemInstance)
  case Coins(amount: Gold)

  def thing: GroundThing = this match
    case Item(it)  => GroundThing.Item(it.name, it.bonus)
    case Coins(g)  => GroundThing.Coins(g)

/** 地上持续法术的作用：火墙（谁放的、每跳威力，按魔防减）；困魔咒光圈（只是样子，困住怪靠怪身上的 Seized 状态）。 */
enum FieldEffect:
  case Fire(owner: ActorId, damage: Int)
  case HolyCurtain

  def kind: FieldKind = this match
    case Fire(_, _)  => FieldKind.Fire
    case HolyCurtain => FieldKind.HolyCurtain

/** 地上持续法术的一格：到 until 消失；火墙在 nextBurn 烧下一次。 */
final case class Field(id: FieldId, cell: Cell, effect: FieldEffect, until: Millis, nextBurn: Millis):
  def view: FieldView = FieldView(id, cell, effect.kind)

final case class GroundItem(id: GroundId, cell: Cell, loot: Loot, droppedAt: Millis):
  def view: GroundView = GroundView(id, cell, loot.thing)
