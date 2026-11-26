package mir.world

import mir.core.*
import mir.rules.{PetBody, Summoning, Ward}
import zio.json.*

/*
 * 停服快照（#1279，「无感重启」）：正常停服时把世界里不进角色存档的东西存下来，开服时原样接上。
 * 时刻都是服务端统一的时钟（墙上时间），停服的几秒照样算过去了。
 * 只存「东西」，不存对象编号：编号开服后重新发，所以快照里认人用角色名、认刷怪点用 SpawnKey。
 */

/**
 * 刷怪点：停服时 game.json 里的下标 + 刷哪种怪、在哪。开服时不直接认下标（别的图加了刷怪点会整体挪动，#1339），
 * 按 Spawner.matchSlots 认；本图把这个刷怪点删了或改了怪、位置就认不出，按新开图处理。
 */
final case class SpawnKey(index: Int, monster: MonsterName, at: Cell)

/** 刷怪点刷出来、还活着的一只野怪：哪种、位置、朝向、血。身上的毒等状态、归属（6 秒就过期）不留。 */
final case class FrozenMonster(spawn: SpawnKey, cell: Cell, dir: Dir, hp: Int)

/** 地上的一件东西：掉下的时刻（10 分钟后消失）和掉落保护照旧。 */
final case class FrozenGround(cell: Cell, loot: Loot, droppedAt: Millis, ward: Option[Ward])

/** 一个刷怪点下一次补怪的时刻。 */
final case class FrozenRefill(spawn: SpawnKey, at: Millis)

/** 一张图的快照：活着的野怪、地上的东西、各刷怪点的补怪时刻。NPC、守卫开图时照常放，不在这里。 */
final case class FrozenMap(monsters: List[FrozenMonster], ground: List[FrozenGround], refills: List[FrozenRefill])

object SpawnKey:
  given JsonCodec[SpawnKey] = DeriveJsonCodec.gen
object FrozenMonster:
  given JsonCodec[FrozenMonster] = DeriveJsonCodec.gen
object FrozenGround:
  import FrozenCodecs.given
  given JsonCodec[FrozenGround] = DeriveJsonCodec.gen
object FrozenRefill:
  given JsonCodec[FrozenRefill] = DeriveJsonCodec.gen
object FrozenMap:
  given JsonCodec[FrozenMap] = DeriveJsonCodec.gen

/** 快照里用到、别处没有 JSON 写法的类型。 */
object FrozenCodecs:
  given JsonCodec[Ward]         = DeriveJsonCodec.gen
  given JsonCodec[Loot]         = DeriveJsonCodec.gen
  given JsonCodec[PetBody]      = DeriveJsonCodec.gen
  given JsonCodec[Summoning]    = DeriveJsonCodec.gen
  given JsonCodec[PetData]      = DeriveJsonCodec.gen
  given JsonCodec[PotionCourse] = DeriveJsonCodec.gen
  given JsonCodec[Status]       = DeriveJsonCodec.gen
  given JsonCodec[Timed]        = DeriveJsonCodec.gen
  given JsonCodec[Carried]      = DeriveJsonCodec.gen
