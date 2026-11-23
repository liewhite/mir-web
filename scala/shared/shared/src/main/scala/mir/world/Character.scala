package mir.world

import mir.core.*
import mir.data.{Catalog, Span}
import mir.model.*
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
    wideHitOn: Boolean
):
  /** 身上和仓库里全部物品（找最大编号、查重用）。 */
  def allItems: List[ItemInstance] = bag.flatten.toList ++ belt.flatten ++ worn.values ++ storage

object CharacterData:
  given JsonCodec[CharacterData] = DeriveJsonCodec.gen

  /** 新人出生点：比奇省（与 TS 版 main.ts START 一致） */
  val start: Place = Place(MapId("0"), Cell(333, 272))

  /**
   * 新建角色（TS 版 main.ts newPlayer）：300 金币，木剑、布衣，4 瓶小红 2 瓶小蓝（各叠一格）放快捷栏，满血满蓝。
   * 物品编号由 uids 分配。
   */
  def newbie(catalog: Catalog, name: String, job: Job, sex: Sex, uids: UidSource): CharacterData =
    def item(n: String): Option[ItemInstance] = catalog.item(ItemName(n)).map(d => ItemInstance.fresh(uids.next(), d))
    // 同名药叠成一格（#1159）
    val potions = List(item("金创药(小量)").map(_.copy(count = 4)), item("魔法药(小量)").map(_.copy(count = 2)))
    val worn = List(
      EquipSlot.Weapon -> item("木剑"),
      EquipSlot.Dress  -> item(if sex == Sex.Male then "布衣(男)" else "布衣(女)")
    ).collect { case (s, Some(it)) => s -> it }.toMap
    val stats = mir.rules.Rules.computeStats(job, 1, Nil, mir.rules.PassiveSkills())
    CharacterData(
      name = name, job = job, sex = sex, hair = 1, level = 1, exp = Exp.zero, gold = Gold(300),
      hp = Hp(stats.maxHp), mp = Mp(stats.maxMp), place = start, dir = Dir.Down, home = start,
      bag = Vector.fill(Capacity.bag)(None),
      belt = potions.padTo(Capacity.belt, None).take(Capacity.belt).toVector,
      worn = worn, storage = Nil, skills = Nil, settings = CharSettings(), longHitOn = true, wideHitOn = false
    )

/** 跟着主人换图的宝宝。 */
final case class PetData(kind: MonsterName, hp: Int, maxHp: Int, dc: Span, level: Int)

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
