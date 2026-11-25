package mir.world

import mir.core.*
import mir.data.Catalog
import mir.model.*
import mir.rules.{Charges, PetBody, Summoning}
import zio.json.*

/** 某张地图上的某一格。 */
final case class Place(map: MapId, cell: Cell)

object Place:
  given JsonCodec[Place] = DeriveJsonCodec.gen

/**
 * 角色存档：下线、换图时从世界里取出的全部持久内容（服务端存数据库，进图时交给世界）。
 * 中毒、魔法盾、冷却这些只在线时有效的状态不存。
 */
final case class CharacterData(
    name: String,
    job: Job,
    sex: Sex,
    hair: Int,
    level: Int,
    exp: Exp,
    gold: Gold,
    hp: Hp,
    mp: Mp,
    place: Place,
    dir: Dir,
    /** 回城点（回城卷、死后复活） */
    home: Place,
    bag: Vector[Option[ItemInstance]],
    belt: Vector[Option[ItemInstance]],
    worn: Map[EquipSlot, ItemInstance],
    storage: List[ItemInstance],
    skills: List[LearnedSkill],
    settings: CharSettings,
    longHitOn: Boolean,
    wideHitOn: Boolean,
    /** 社交偏好（#1220）：世界只原样带着（进图时的值），在线时的当前值归服务端 mir.social，存档时由服务端填上 */
    social: SocialPrefs = SocialPrefs(),
    /** PK 值（#1225；旧存档没有这个字段算 0） */
    pkPoints: Int = 0
):
  /** 身上和仓库里全部物品（找最大编号、查重用）。 */
  def allItems: List[ItemInstance] = bag.flatten.toList ++ belt.flatten ++ worn.values ++ storage

object CharacterData:
  given JsonCodec[CharacterData] = DeriveJsonCodec.gen

  /** 新人出生点：比奇省（与 TS 版 main.ts START 一致） */
  val start: Place = Place(MapId("0"), Cell(333, 272))

  /**
   * 新建角色：直接 StarterKit.level 级（经验从 0 起），穿 StarterKit 那身基础装（#1310），300 金币，
   * 4 瓶小红 2 瓶小蓝（各叠一格）放快捷栏，满血满蓝。物品编号由 uids 分配。
   */
  def newbie(catalog: Catalog, name: String, job: Job, sex: Sex, uids: UidSource): CharacterData =
    def item(n: String): Option[ItemInstance] = catalog.item(ItemName(n)).map(d => Charges.fresh(uids.next(), d))
    // 同名药叠成一格（#1159）
    val potions = List(item("金创药(小量)").map(_.copy(count = 4)), item("魔法药(小量)").map(_.copy(count = 2)))
    val worn = StarterKit.outfit(job, sex).toList.flatMap((s, n) => item(n.value).map(s -> _)).toMap
    val effects = worn.values.flatMap(it => catalog.item(it.name).map(mir.rules.ItemEffects.of(_, it)))
    val stats   = mir.rules.Rules.computeStats(job, StarterKit.level, effects, mir.rules.PassiveSkills())
    CharacterData(
      name = name, job = job, sex = sex, hair = 1, level = StarterKit.level, exp = Exp.zero, gold = Gold(300),
      hp = Hp(stats.maxHp), mp = Mp(stats.maxMp), place = start, dir = Dir.Down, home = start,
      bag = Vector.fill(Capacity.bag)(None),
      belt = potions.padTo(Capacity.belt, None).take(Capacity.belt).toVector,
      worn = worn, storage = Nil, skills = Nil, settings = CharSettings(), longHitOn = true, wideHitOn = false
    )

/**
 * 新角色的起点（用户 11-25 #1310 定：初始 10 级、穿一身基础装）。
 * 装备都是 1.76 数据表里 10 级能穿（只看等级、不看属性）的普通货，按职业挑：
 * 战士重攻防，法师戒指加魔法，道士戒指加道术；衣服按性别。
 */
object StarterKit:
  val level = 10

  def outfit(job: Job, sex: Sex): Map[EquipSlot, ItemName] =
    val (weapon, bracelet, ring) = job match
      case Job.Warrior => ("铁剑", "大手镯", "牛角戒指")
      case Job.Wizard  => ("乌木剑", "钢手镯", "六角戒指")
      case Job.Taoist  => ("乌木剑", "钢手镯", "玻璃戒指")
    val dress = sex match
      case Sex.Male   => "布衣(男)"
      case Sex.Female => "布衣(女)"
    Map(
      EquipSlot.Weapon    -> weapon,
      EquipSlot.Dress     -> dress,
      EquipSlot.Helmet    -> "青铜头盔",
      EquipSlot.Necklace  -> "金项链",
      EquipSlot.BraceletL -> bracelet,
      EquipSlot.BraceletR -> bracelet,
      EquipSlot.RingL     -> ring,
      EquipSlot.RingR     -> ring
    ).view.mapValues(ItemName(_)).toMap

/** 跟着主人换图的宝宝：此刻的形态、血、身板、宝宝等级，召唤的还有召唤加成（变身时重算身板用）。 */
final case class PetData(kind: MonsterName, hp: Int, body: PetBody, level: Int, summoning: Option[Summoning] = None)

/** 物品实例编号的来源：全服唯一（服务端按数据库里最大编号往上发）。 */
trait UidSource:
  def next(): ItemUid

object UidSource:
  /** 从 start 往上数（测试和单进程服务端用）。 */
  def counter(start: Long): UidSource = new UidSource:
    private var n = start
    def next(): ItemUid =
      val v = n
      n += 1
      ItemUid(v)
