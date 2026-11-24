package mir.data

import mir.core.*
import mir.model.Job
import zio.json.*
import zio.json.ast.Json

/**
 * 游戏数据（public/assets/data/game.json，tools/build_data.py 从 1.76 数据表编译）解码成的不可变定义。
 * 字段名、含义与 TS 版 game/src/data/defs.ts 一致；JVM 和 JS 共用这一份解码。
 */

/** 一个闭区间数值，数据表里写成 [下限, 上限]（攻击、防御等）。 */
final case class Span(lo: Int, hi: Int):
  def +(o: Span): Span = Span(lo + o.lo, hi + o.hi)

object Span:
  val zero: Span = Span(0, 0)
  given JsonCodec[Span] = JsonCodec[(Int, Int)].transform({ case (a, b) => Span(a, b) }, s => (s.lo, s.hi))

/** 地图属性（数据表 MapInfo 的标记）。 */
enum MapFlag:
  case Day, Dark, Fight, NoRecall, NoRandomMove, NoPositionMove, NeedHole, Mine
  /** 断线重连回到别处：括号里是回到的地图（数字是原版的城编号） */
  case NoReconnect(back: String)

object MapFlag:
  private val Reconnect = """NORECONNECT\((.*)\)""".r

  def parse(token: String): Either[String, MapFlag] = token match
    case "DAY"            => Right(Day)
    case "DARK"           => Right(Dark)
    case "FIGHT"          => Right(Fight)
    case "NORECALL"       => Right(NoRecall)
    case "NORANDOMMOVE"   => Right(NoRandomMove)
    case "NOPOSITIONMOVE" => Right(NoPositionMove)
    case "NEEDHOLE"       => Right(NeedHole)
    case "MINE"           => Right(Mine)
    case Reconnect(back)  => Right(NoReconnect(back))
    case other            => Left(s"未知地图标记：$other")

  def render(f: MapFlag): String = f match
    case NoReconnect(back) => s"NORECONNECT($back)"
    case other             => other.toString.toUpperCase

  /** 数据表里一格字符串，空白分隔多个标记。 */
  val setCodec: JsonCodec[Set[MapFlag]] = JsonCodec.string.transformOrFail(
    s =>
      s.split("\\s+").iterator.filter(_.nonEmpty).foldLeft[Either[String, Set[MapFlag]]](Right(Set.empty)) { (acc, t) =>
        acc.flatMap(set => parse(t).map(set + _))
      },
    _.toList.map(render).sorted.mkString(" ")
  )

final case class MapDef(
    id: MapId,
    /** 素材名：assets/map/<asset>.bin、assets/ground/<asset>/；内容相同的地图共用一份 */
    asset: String,
    title: String,
    flags: Set[MapFlag],
    width: Int,
    height: Int
):
  def has(f: MapFlag): Boolean = flags.contains(f)

object MapDef:
  private given JsonCodec[Set[MapFlag]] = MapFlag.setCodec
  given JsonCodec[MapDef]               = DeriveJsonCodec.gen

/** 地图之间的通道：踩到 from 图的 (x, y) 就到 to 图的 (tx, ty)。 */
final case class RouteDef(from: MapId, x: Int, y: Int, to: MapId, tx: Int, ty: Int):
  def at: Cell     = Cell(x, y)
  def target: Cell = Cell(tx, ty)

object RouteDef:
  given JsonCodec[RouteDef] = DeriveJsonCodec.gen

/** 刷怪点：在 (x, y) 半径 radius 内保持 count 只，死了 minutes 分钟后补。 */
final case class SpawnDef(map: MapId, x: Int, y: Int, monster: MonsterName, radius: Int, count: Int, minutes: Int):
  def at: Cell = Cell(x, y)

object SpawnDef:
  given JsonCodec[SpawnDef] = DeriveJsonCodec.gen

/** 守卫：固定站位的怪物（卫士、弓箭手）。 */
final case class GuardDef(monster: MonsterName, map: MapId, x: Int, y: Int, direction: Dir):
  def at: Cell = Cell(x, y)

object GuardDef:
  given JsonCodec[GuardDef] = DeriveJsonCodec.gen

/** 商人做什么：卖东西、仓库、武器升级。 */
enum MerchantService:
  case Shop, Storage, WeaponUpgrade

object MerchantService:
  given JsonCodec[MerchantService] = EnumCodec.byName(
    values,
    {
      case Shop          => "shop"
      case Storage       => "storage"
      case WeaponUpgrade => "weapon-upgrade"
    }
  )

/** 商品：名字、库存、补货间隔。 */
final case class GoodsDef(name: ItemName, stock: Int, minutes: Int)

object GoodsDef:
  given JsonCodec[GoodsDef] = DeriveJsonCodec.gen

final case class MerchantDef(
    map: MapId,
    x: Int,
    y: Int,
    name: String,
    appearance: Int,
    castle: Int,
    service: MerchantService,
    /** 收购哪些种类（StdMode）的物品 */
    @jsonField("item_modes") itemModes: List[Int],
    goods: List[GoodsDef]
):
  def at: Cell = Cell(x, y)

object MerchantDef:
  given JsonCodec[MerchantDef] = DeriveJsonCodec.gen

/** 物品。字段沿用 1.76 StdItems 的含义：mode=StdMode, shape=Shape, look=图标号。具体用途见 mir.rules.ItemKind。 */
final case class ItemDef(
    id: Int,
    name: ItemName,
    mode: Int,
    shape: Int,
    weight: Int,
    ani: Int,
    source: Int,
    look: Int,
    dura: Int,
    ac: Span,
    mac: Span,
    dc: Span,
    mc: Span,
    sc: Span,
    /** 穿戴要求看什么：0 等级、1 攻击上限、2 魔法上限、3 道术上限 */
    need: Int,
    needLevel: Int,
    price: Int
)

object ItemDef:
  given JsonCodec[ItemDef] = DeriveJsonCodec.gen

final case class MonsterDef(
    name: MonsterName,
    race: Int,
    raceImg: Int,
    appr: Int,
    lib: String,
    offset: Int,
    /** 动作表名（MA9..MA47），见 GameData.monsterActions */
    act: Option[String],
    level: Int,
    undead: Int,
    exp: Int,
    hp: Int,
    mp: Int,
    ac: Span,
    mac: Span,
    dc: Span,
    mc: Int,
    sc: Int,
    speed: Int,
    hit: Int,
    walkSpeed: Int,
    walkStep: Int,
    walkWait: Int,
    attackSpeed: Int,
    coolEye: Int,
    viewRange: Int
):
  /**
   * 守卫（见到怪就打，平时不打玩家；玩家亲手选中能打它，打了它会还手，见 Look.attackable）：按 Race 认（OpenMir2 Race.cs：11 Supreguard、12 Petsupreguard、
   * 20 ArcherPolice、112 ArcherGuard）。TS 版按名字带「卫士」认，把沃玛卫士、祖玛卫士也当成了守卫。
   */
  def isGuard: Boolean = guard.isDefined

  /** 是守卫的话是哪一种（决定主动打谁）。 */
  def guard: Option[GuardKind] = GuardKind.ofRace(race)

object MonsterDef:
  given JsonCodec[MonsterDef] = DeriveJsonCodec.gen

/**
 * 守卫种类，原版按 Race 分，决定主动去打谁（被打了记仇还手各种一样，见 Hostility）。
 * 宝宝按它原本那种怪的 Race 算（诱惑来的鸡鹿 Race ≤ 52）。
 */
enum GuardKind:
  /** 卫士、带刀护卫（Race 11，ObjGuard.pas TSuperGuard：AttackPet=True）：Race ≥ 80 的怪都打，宝宝也打。 */
  case Blade
  /** Race 12（TPetSuperGuard：AttackPet=False）：Race ≥ 80 的野怪打，宝宝只打正把它当目标的。现有数据里没有。 */
  case PetSparing
  /** 弓箭守卫、弓箭手（Race 112 ArcherGuard、20 ArcherPolice）：不打宝宝；野怪沿用旧规则（鸡鹿羊以外都打）。 */
  case Archer

  /** 主动去打这只野怪（race 是它的 Race）。原版 SuperGuard.Run 只认 Race ≥ 80（RC_MONSTER），狼、练功师也不打。 */
  def huntsWild(race: Int): Boolean = this match
    case Blade | PetSparing => race >= GuardKind.MonsterRace
    case Archer             => race > GuardKind.AnimalMaxRace

  /** 主动去打这只宝宝：race 是它的 Race，targetsMe 是它正把这个守卫当目标。 */
  def huntsPet(race: Int, targetsMe: Boolean): Boolean = this match
    case Blade      => race >= GuardKind.MonsterRace
    case PetSparing => race >= GuardKind.MonsterRace && targetsMe
    case Archer     => false

object GuardKind:
  /** 原版 RC_MONSTER：Race 从这里起才算「怪物」 */
  val MonsterRace = 80
  /** 鸡、鹿、羊这类不主动打人的动物最大的 Race（原版 GuardUnit.CanPassiveAttack） */
  val AnimalMaxRace = 52

  def ofRace(race: Int): Option[GuardKind] = race match
    case 11       => Some(Blade)
    case 12       => Some(PetSparing)
    case 20 | 112 => Some(Archer)
    case _        => None

/** 爆率一行：numerator/denominator 的概率掉 amount 个（金币是数额）。 */
final case class DropDef(item: ItemName, numerator: Int, denominator: Int, amount: Int):
  def isGold: Boolean = item == DropDef.GoldName

object DropDef:
  val GoldName: ItemName = ItemName("金币")
  given JsonCodec[DropDef] = DeriveJsonCodec.gen

/** 技能三个等级各自的数值（升级要的人物等级、要的熟练度）。 */
final case class SkillTiers(lv1: Int, lv2: Int, lv3: Int):
  /** level 0..2：学到 level+1 级要的数值。 */
  def apply(level: Int): Int = level match
    case 0 => lv1
    case 1 => lv2
    case _ => lv3

object SkillTiers:
  given JsonCodec[SkillTiers] = JsonCodec[(Int, Int, Int)].transform(
    { case (a, b, c) => SkillTiers(a, b, c) },
    t => (t.lv1, t.lv2, t.lv3)
  )

final case class SkillDef(
    id: SkillId,
    name: String,
    job: Job,
    effectType: Int,
    effect: Int,
    spell: Int,
    power: Span,
    defSpell: Int,
    defPower: Span,
    needLevel: SkillTiers,
    train: SkillTiers,
    /** 毫秒 */
    delay: Int
)

object SkillDef:
  private given JsonCodec[Job] = Job.byCode
  given JsonCodec[SkillDef]    = DeriveJsonCodec.gen

/** 原版动作表一项：起始帧、帧数、每方向跳过帧、每帧毫秒、移动用 tick。 */
final case class ActionInfo(start: Int, frames: Int, skip: Int, frameTime: Int, useTick: Int)

object ActionInfo:
  given JsonCodec[ActionInfo] = JsonCodec[(Int, Int, Int, Int, Int)].transform(
    { case (a, b, c, d, e) => ActionInfo(a, b, c, d, e) },
    i => (i.start, i.frames, i.skip, i.frameTime, i.useTick)
  )

final case class MonsterActionTable(
    @jsonField("ActStand") stand: ActionInfo,
    @jsonField("ActWalk") walk: ActionInfo,
    @jsonField("ActAttack") attack: ActionInfo,
    @jsonField("ActCritical") critical: ActionInfo,
    @jsonField("ActStruck") struck: ActionInfo,
    @jsonField("ActDie") die: ActionInfo,
    @jsonField("ActDeath") death: ActionInfo
)

object MonsterActionTable:
  given JsonCodec[MonsterActionTable] = DeriveJsonCodec.gen

/** 传送菜单的一个目的地：group 是分组（城市、各地区）。 */
final case class TeleportDef(group: String, name: String, map: MapId, x: Int, y: Int):
  def at: Cell = Cell(x, y)

object TeleportDef:
  given JsonCodec[TeleportDef] = DeriveJsonCodec.gen

final case class GameData(
    maps: List[MapDef],
    routes: List[RouteDef],
    spawns: List[SpawnDef],
    guards: List[GuardDef],
    merchants: List[MerchantDef],
    /** 地图 → 小地图图号 */
    minimaps: Map[MapId, Int],
    items: List[ItemDef],
    monsters: List[MonsterDef],
    drops: Map[MonsterName, List[DropDef]],
    /** 怪物内部名 → 显示名（僵尸10 显示成僵尸1） */
    displayNames: Map[MonsterName, MonsterName],
    skills: List[SkillDef],
    /** 等级 → 升到下一级要的经验 */
    exp: Map[Int, Long],
    monsterActions: Map[String, MonsterActionTable],
    teleports: List[TeleportDef]
)

object GameData:
  given JsonCodec[GameData] = DeriveJsonCodec.gen

  def decode(json: String): Either[String, GameData] = json.fromJson[GameData]

  /** 先解析成 JSON 树再解码（给已经拿到 Json 的调用方）。 */
  def decode(json: Json): Either[String, GameData] = json.as[GameData]
