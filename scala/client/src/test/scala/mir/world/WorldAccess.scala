package mir.world

import mir.core.Cell
import mir.data.MonsterDef

/** 客户端测试要在世界里放怪（放怪是世界内部的操作，只对同包开放）。 */
object WorldAccess:
  def spawn(w: World, d: MonsterDef, at: Cell): Option[Monster] = w.spawnMonster(d, None, at, 0)
