package mir.world

import mir.core.*
import mir.data.MapFlag
import mir.model.*
import mir.protocol.{Anchor, Tone}
import mir.rules.{Mining, Pace, Rng}

import scala.collection.mutable

/**
 * 挖矿（#1286，规则见 mir.rules.Mining）：矿区地图上每个矿壁格的矿脉（第一次挖时才定下，开服重来，原版也是开服时建）。
 * 玩家拿着鹤嘴锄朝挡路的格子攻击时，Combat.attack 交到这里挖一下；不是矿区的图照样挥锄头，挖不出东西（原版也是）。
 */
final class Quarry(w: World):
  private val veins = mutable.HashMap.empty[Cell, Mining.Vein]

  /** 碎石飞溅在锄头落下那一帧（原版 HumActor.pas：重击第 5 帧） */
  private val ChipDelayMs = Pace.HitAnimMs * 5 / 6
  private val StrikeStone = 91

  /** 这一下是挖矿：手上是还有持久的鹤嘴锄、朝着的格子是挡路的地形（墙、矿壁）。 */
  private[world] def facesRock(p: Player, dir: Dir): Boolean =
    val c = p.cell.step(dir)
    w.terrain.inside(c) && !w.terrain.walkable(c) &&
    p.worn.get(EquipSlot.Weapon).exists(wp => wp.dura > 0 && w.catalog.item(wp.name).exists(Mining.isPickaxe))

  /** 挖一下（挥锄头的动作、冷却由 Combat 管）。 */
  private[world] def dig(p: Player, dir: Dir): Unit =
    if w.mapDef.has(MapFlag.Mine) then
      given Rng    = w.rngOf
      val rock     = p.cell.step(dir)
      val vein     = veins.getOrElseUpdate(rock, Mining.Vein.fresh(w.now.value))
      val room     = w.catalog.item(Mining.Ore.Copper.item).exists(Inventory.hasRoom(p, _))
      val (left, r) = Mining.strike(vein, w.now.value, room)
      veins(rock) = left
      r match
        case Mining.Strike.Nothing => ()
        case Mining.Strike.Chip(ore) =>
          wearPickaxe(p)
          w.afterOn(p, ChipDelayMs) { me =>
            w.out.effect("effect", 8 * dir.ordinal, 3, 80, Anchor.OnCell(me.cell))
            w.out.sound(me.cell, StrikeStone)
          }
          if ore then give(p, Mining.ore(), Mining.purity())

  private def wearPickaxe(p: Player): Unit =
    given Rng = w.rngOf
    p.worn.get(EquipSlot.Weapon).foreach(wp => p.worn(EquipSlot.Weapon) = wp.copy(dura = math.max(0, wp.dura - Mining.wear())))

  /** 矿石进包裹：纯度存在持久里，持久上限是物品表的（原版 MakeMine）。 */
  private def give(p: Player, o: Mining.Ore, purity: Int): Unit =
    w.newItem(o.item).foreach { it =>
      if Inventory.add(w.catalog, p, it.copy(dura = purity)) then
        w.out.selfSound(p, Sounds.itemClick)
        w.out.notice(p, s"挖到 ${o.item.value}（纯度 ${Mining.shownPurity(purity)}）", Tone.Item)
    }
