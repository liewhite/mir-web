package mir.data

import mir.core.*

/** 按名字、编号查游戏数据的索引。查不到返回 None，由调用方决定是报错还是忽略。 */
final class Catalog(val data: GameData):
  val itemsByName: Map[ItemName, ItemDef]       = data.items.map(i => i.name -> i).toMap
  val itemsById: Map[Int, ItemDef]              = data.items.map(i => i.id -> i).toMap
  val monstersByName: Map[MonsterName, MonsterDef] = data.monsters.map(m => m.name -> m).toMap
  val skillsById: Map[SkillId, SkillDef]        = data.skills.map(s => s.id -> s).toMap
  val skillsByName: Map[String, SkillDef]       = data.skills.map(s => s.name -> s).toMap
  val mapsById: Map[MapId, MapDef]              = data.maps.map(m => m.id -> m).toMap
  private val merchantsVec: Vector[MerchantDef] = data.merchants.toVector
  private val teleportsVec: Vector[TeleportDef] = data.teleports.toVector
  val routesByMap: Map[MapId, List[RouteDef]]   = data.routes.groupBy(_.from)
  val spawnsByMap: Map[MapId, List[SpawnDef]]   = data.spawns.groupBy(_.map)
  val guardsByMap: Map[MapId, List[GuardDef]]   = data.guards.groupBy(_.map)
  val merchantsByMap: Map[MapId, List[MerchantDef]] = data.merchants.groupBy(_.map)

  def item(name: ItemName): Option[ItemDef]          = itemsByName.get(name)
  def monster(name: MonsterName): Option[MonsterDef] = monstersByName.get(name)
  def skill(id: SkillId): Option[SkillDef]           = skillsById.get(id)
  def map(id: MapId): Option[MapDef]                 = mapsById.get(id)
  def merchant(r: MerchantRef): Option[MerchantDef] = merchantsVec.lift(r.index)
  def teleport(r: TeleportRef): Option[TeleportDef] = teleportsVec.lift(r.index)
  def drops(name: MonsterName): List[DropDef]        = data.drops.getOrElse(name, Nil)

  /** 怪物的动作表；没有动作表名或表里没有时为 None。 */
  def actions(m: MonsterDef): Option[MonsterActionTable] = m.act.flatMap(data.monsterActions.get)

  /** 升到下一级要的经验；满级为 None。 */
  def expToNext(level: Int): Option[Exp] = data.exp.get(level).map(Exp(_))

  def displayName(m: MonsterName): MonsterName = data.displayNames.getOrElse(m, m)

object Catalog:
  def decode(json: String): Either[String, Catalog] = GameData.decode(json).map(Catalog(_))
