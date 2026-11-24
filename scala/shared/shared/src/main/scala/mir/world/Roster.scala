package mir.world

import mir.core.*
import mir.model.*
import mir.protocol.*

/** 玩家进出本图：上线进图（从存档建人、带上宝宝和状态）、下线、存档快照、传送和换图。 */
final class Roster(w: World):

  /**
   * 玩家进图：位置用存档里的格子（被占就找附近空格）。返回他在本图的对象编号。
   * 数据表里已不存在的物品、技能丢掉（旧存档不会把游戏弄坏）。
   */
  def join(data: CharacterData, carried: Carried = Carried()): ActorId =
    val cell = w.freeCellNear(data.place.cell).getOrElse(data.place.cell)
    val p    = Player(w.newActorId(), data.name, data.job, data.sex, data.hair, cell, data.dir, w.now)
    def valid(it: ItemInstance): Option[ItemInstance] = w.catalog.item(it.name).map(_ => it)
    p.level = data.level
    p.exp = data.exp
    p.gold = data.gold
    p.home = data.home
    data.bag.take(Capacity.bag).zipWithIndex.foreach((it, i) => p.bag(i) = it.flatMap(valid))
    data.belt.take(Capacity.belt).zipWithIndex.foreach((it, i) => p.belt(i) = it.flatMap(valid))
    data.worn.foreach((s, it) => valid(it).foreach(p.worn(s) = _))
    p.storage ++= data.storage.flatMap(valid)
    Inventory.normalize(w.catalog, p) // 旧存档补数量、同名可叠的并成一叠（#1159）
    p.skills ++= data.skills.filter(s => w.catalog.skill(s.id).isDefined)
    p.settings = data.settings
    p.longHitOn = data.longHitOn
    p.wideHitOn = data.wideHitOn
    p.social = data.social
    p.pkPoints = math.max(0, data.pkPoints)
    carried.statuses.foreach(t => p.statuses.put(t.status, t.until))
    p.statuses.until(StatusKind.Paralyzed).foreach(p.busyUntil = _)
    p.potions = carried.potions
    carried.pkDecayAt.foreach(p.pkDecayAt = _)
    w.vitals.restore(p, data.hp, data.mp)
    w.enter(p)
    w.out.tell(p, ServerMsg.EnterMap(p.id, w.mapId, p.cell, p.dir, w.now))
    w.out.sound(p.cell, Sounds.spaceIn)
    carried.pets.foreach { pd =>
      w.catalog.monster(pd.kind).flatMap(w.spawner.spawnMonster(_, None, p.cell, 1)).foreach { m =>
        m.master = Some(p.id)
        m.petLevel = pd.level
        m.summoning = pd.summoning
        w.vitals.shape(m, pd.body, Some(pd.hp))
        m.cover = Cover.Open
        p.pets += m.id
      }
    }
    p.id

  /** 玩家下线：取出存档，人和宝宝从图上消失。 */
  def leave(id: ActorId): Option[CharacterData] = w.player(id).map { p =>
    val data = snapshot(p)
    w.remove(p)
    p.pets.flatMap(w.actor).foreach(w.remove)
    data
  }

  /** 在线玩家的存档（定时保存用）。 */
  def snapshot(id: ActorId): Option[CharacterData] = w.player(id).map(snapshot)

  private[world] def snapshot(p: Player): CharacterData =
    CharacterData(
      name = p.charName, job = p.job, sex = p.sex, hair = p.hair, level = p.level, exp = p.exp, gold = p.gold,
      hp = Hp(p.hp), mp = Mp(p.mp), place = Place(w.mapId, p.cell), dir = p.dir, home = p.home,
      bag = p.bag.toVector, belt = p.belt.toVector, worn = p.worn.toMap, storage = p.storage.toList,
      skills = p.skills.toList, settings = p.settings, longHitOn = p.longHitOn, wideHitOn = p.wideHitOn,
      social = p.social, pkPoints = p.pkPoints
    )

  /** 把玩家送到某处：同图直接挪过去（像重新进图），别的图交给服务端。 */
  private[world] def transfer(p: Player, to: Place): Unit =
    p.intent = Intent.Idle
    if to.map == w.mapId then
      val c = w.freeCellNear(to.cell).getOrElse(p.cell)
      w.grid.teleportActor(p, c)
      p.arrivedAt = w.now
      p.pets.flatMap(w.actor).foreach(pet => w.freeCellNear(c).foreach(w.grid.teleportActor(pet, _)))
      w.resetView(p)
      w.out.tell(p, ServerMsg.EnterMap(p.id, w.mapId, p.cell, p.dir, w.now))
      w.out.sound(p.cell, Sounds.spaceIn)
    else
      val pets = p.pets.flatMap(w.actor).collect {
        case m: Monster if !m.dead => PetData(m.defn.name, m.hp, m.body, m.petLevel, m.summoning)
      }.toList
      w.out.sound(p.cell, Sounds.spaceOut)
      // 下毒的人留在这张图：编号到了别的图不算数
      val statuses = p.statuses.all.map {
        case Timed(q: Status.Poisoned, until) => Timed(q.copy(by = None), until)
        case t                                 => t
      }
      w.out.depart(Departure(p.id, snapshot(p).copy(place = to), Carried(pets, statuses, p.potions, Some(p.pkDecayAt))))
      w.remove(p)
      p.pets.flatMap(w.actor).foreach(w.remove)
