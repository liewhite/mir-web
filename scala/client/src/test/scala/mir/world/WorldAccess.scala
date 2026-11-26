package mir.world

import mir.core.Cell
import mir.data.MonsterDef

/** 客户端测试要在世界里放怪（放怪是世界内部的操作，只对同包开放）。 */
object WorldAccess:
  def spawn(w: World, d: MonsterDef, at: Cell): Option[Monster] = w.spawner.spawnMonster(d, None, at, 0)

  /** 把怪变成某个玩家的宝宝（测试用，跳过诱惑之光）。 */
  def adopt(w: World, m: Monster, owner: mir.core.ActorId): Unit =
    m.master = Some(owner)
    w.player(owner).foreach(_.pets += m.id)

  /** 把怪的血设成 hp（做打不死的靶子）。 */
  def setHp(m: Monster, hp: Int): Unit = m.hp = hp

  /** 角色设置（如关掉「按住攻击」）。 */
  def settings(w: World, id: mir.core.ActorId, f: mir.model.CharSettings => mir.model.CharSettings): Unit =
    w.player(id).foreach(p => p.settings = f(p.settings))
