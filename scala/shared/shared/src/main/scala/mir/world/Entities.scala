package mir.world

import mir.core.*
import mir.data.{MerchantDef, MonsterDef, Span}
import mir.model.*
import mir.protocol.{ActionView, GroundThing, GroundView, Look}
import mir.rules.Stats

import scala.collection.mutable

/**
 * 世界里的对象：玩家、怪物、NPC。只有状态；改状态的规则在 World、Combat、MonsterAi、PlayerOps 里。
 * 位置、朝向、动作只由 World 改（它负责占位和通知旁人），这里的 var 对 world 包以外不开放写。
 */

/** 中毒：绿毒每 2 秒掉 power 血，红毒降防（只记状态）。by：下毒的对象（经验归属）。 */
final case class Poison(kind: PoisonKind, until: Millis, power: Int, nextTick: Millis, by: ActorId)

sealed abstract class Actor(val id: ActorId, initCell: Cell, initDir: Dir, now: Millis):
  private[world] var cell: Cell           = initCell
  private[world] var dir: Dir             = initDir
  private[world] var hp: Int              = 1
  private[world] var action: ActionView   = ActionView(ActKind.Stand, now, 0)
  /** 下一次能接受新动作的时刻 */
  private[world] var busyUntil: Millis    = Millis.zero
  private[world] var dead: Boolean        = false
  private[world] var poison: Option[Poison] = None
  /** 魔法盾、隐身、麻痹（原版 STONE 状态）的结束时刻 */
  private[world] var shieldUntil: Millis    = Millis.zero
  private[world] var shieldLevel: Int       = 0
  private[world] var invisibleUntil: Millis = Millis.zero
  private[world] var paralyzedUntil: Millis = Millis.zero
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

  def maxHp: Int   = stats.maxHp
  def ac: Span     = stats.ac
  def mac: Span    = stats.mac
  def agility: Int = stats.agility
  def look: Look   = Look.Human(charName, job, sex, hair, worn.get(EquipSlot.Weapon).map(_.name), worn.get(EquipSlot.Dress).map(_.name))

  def skill(id: SkillId): Option[LearnedSkill] = skills.find(_.id == id)

/** 玩家在服务端执行的自动意图。 */
enum Intent:
  case Idle
  /** 打这个目标直到它死（once：只打一下） */
  case Hunt(target: ActorId, once: Boolean)
  /** 走过去捡这件东西 */
  case Fetch(item: GroundId)

/** 怪物（含守卫、宝宝）。 */
final class Monster(id: ActorId, val defn: MonsterDef, val behavior: Behavior, val spawn: Option[Int], cell: Cell, dir: Dir, now: Millis)
    extends Actor(id, cell, dir, now):
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
  /** 攻击、走路间隔（毫秒）：平时取数据表，沃玛教主狂暴时变快 */
  private[world] var attackInterval: Int = defn.attackSpeed
  private[world] var walkInterval: Int   = defn.walkSpeed
  private[world] val minions: mutable.ArrayBuffer[ActorId] = mutable.ArrayBuffer.empty
  /** 还能复活几次、几时复活 */
  private[world] var revivesLeft: Int = 0
  private[world] var reviveAt: Option[Millis] = None
  /** 打死给的经验倍数（复活一次减半） */
  private[world] var expScale: Double = 1.0
  /** 祖玛教主召唤档位、沃玛教主狂暴 */
  private[world] var danger: Int  = 5
  private[world] var rage: Rage   = Rage.Calm
  private[world] var rageAt: Millis = Millis.zero
  private[world] var extraTick: Millis = Millis.zero
  hp = defn.hp

  def maxHp: Int   = maxHpNow
  def ac: Span     = defn.ac
  def mac: Span    = defn.mac
  def agility: Int = defn.speed
  def hit: Int     = defn.hit
  def isGuard: Boolean = Behavior.isGuard(defn)
  def look: Look   = Look.Monster(defn.name, cover, master)

enum Rage:
  case Calm, Ready, Crazy

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
    case Item(it)  => GroundThing.Item(it.name)
    case Coins(g)  => GroundThing.Coins(g)

final case class GroundItem(id: GroundId, cell: Cell, loot: Loot, droppedAt: Millis):
  def view: GroundView = GroundView(id, cell, loot.thing)
