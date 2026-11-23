package mir.model

import mir.core.*
import mir.data.ItemDef
import zio.json.*

/** 一件具体的物品。dura：当前持久（药品、卷轴为 1；符和毒是剩余次数×100）。 */
final case class ItemInstance(uid: ItemUid, name: ItemName, dura: Int, duraMax: Int)

object ItemInstance:
  given JsonCodec[ItemInstance] = DeriveJsonCodec.gen

  def fresh(uid: ItemUid, d: ItemDef): ItemInstance = ItemInstance(uid, d.name, d.dura, d.dura)

/** 包裹 40 格（原版 8x5）、快捷栏 6 格。 */
object Capacity:
  val bag: Int  = 40
  val belt: Int = 6

/** 玩家身上放物品的位置：包裹第几格、快捷栏第几格、装备位。 */
enum ItemPlace:
  case Bag(index: Int)
  case Belt(index: Int)
  case Worn(slot: EquipSlot)

object ItemPlace:
  given JsonCodec[ItemPlace] = DeriveJsonCodec.gen

/** 学会的技能：等级 0..3、熟练度、快捷键（1-8 对应 F1-F8，None 未设）。 */
final case class LearnedSkill(id: SkillId, level: Int, train: Int, key: Option[Int])

object LearnedSkill:
  given JsonCodec[LearnedSkill] = DeriveJsonCodec.gen

/**
 * 挂在角色上、由服务端执行的便利功能（原版要靠外挂，用户 #1137 要求做成开关）。
 * 只影响画面和声音的开关（血条、名字、音效）留在客户端本地，不在这里。
 */
final case class CharSettings(
    /** 自动捡附近的物品 */
    autoPickup: Boolean = true,
    /** 只捡装备、金币和书（不捡药和杂物） */
    pickupValuableOnly: Boolean = false,
    /** 血量低于这个百分比自动喝红（0 关闭） */
    autoHpPercent: Int = 40,
    /** 蓝量低于这个百分比自动喝蓝（0 关闭） */
    autoMpPercent: Int = 20,
    /** 点一下怪就一直打到它死 */
    stickyAttack: Boolean = true,
    /** 被怪打时自动还手 */
    autoFightBack: Boolean = true,
    /** 战士：烈火好了就自动开 */
    autoFireSword: Boolean = true,
    /** 战士：自动站到隔一格的位置用刺杀 */
    autoLongHitPosition: Boolean = false
)

object CharSettings:
  given JsonCodec[CharSettings] = DeriveJsonCodec.gen
