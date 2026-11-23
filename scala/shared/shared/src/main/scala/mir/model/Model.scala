package mir.model

import mir.core.EnumCodec
import zio.json.*

/** 职业。code 是原版编号（数据表、存档里用）。 */
enum Job(val code: Int, val label: String):
  case Warrior extends Job(0, "战士")
  case Wizard  extends Job(1, "法师")
  case Taoist  extends Job(2, "道士")

object Job:
  /** 数据表、存档里是数字。 */
  val byCode: JsonCodec[Job] = EnumCodec.byCode(values, _.code)
  given JsonCodec[Job]       = EnumCodec.byName(values, _.toString)

/** 性别。code 是原版编号。 */
enum Sex(val code: Int, val label: String):
  case Male   extends Sex(0, "男")
  case Female extends Sex(1, "女")

object Sex:
  val byCode: JsonCodec[Sex] = EnumCodec.byCode(values, _.code)
  given JsonCodec[Sex]       = EnumCodec.byName(values, _.toString)

/** 装备位。key 与 TS 版存档的字段名一致（迁移旧存档用）。 */
enum EquipSlot(val key: String, val label: String):
  case Weapon    extends EquipSlot("weapon", "武器")
  case Dress     extends EquipSlot("dress", "衣服")
  case Helmet    extends EquipSlot("helmet", "头盔")
  case Necklace  extends EquipSlot("necklace", "项链")
  case BraceletL extends EquipSlot("braceletL", "左手镯")
  case BraceletR extends EquipSlot("braceletR", "右手镯")
  case RingL     extends EquipSlot("ringL", "左戒指")
  case RingR     extends EquipSlot("ringR", "右戒指")

object EquipSlot:
  given JsonCodec[EquipSlot] = EnumCodec.byName(values, _.key)
  private val field          = EnumCodec.fieldByName(values, _.key)
  given JsonFieldEncoder[EquipSlot] = field._1
  given JsonFieldDecoder[EquipSlot] = field._2

/** 正在做的动作；客户端按它取帧和插值位置。 */
enum ActKind:
  case Stand, Walk, Run, Attack, Heavy, Spell, Struck, Die, Dead
  /** 怪物从地下钻出、石像醒来、僵尸复活 */
  case DigUp
  /** 钻回地下 */
  case DigDown

object ActKind:
  given JsonCodec[ActKind] = EnumCodec.byName(values, _.toString)

/** 普通攻击的种类，决定人物动作和刀光：普通、攻杀、刺杀、半月、烈火。 */
enum HitStyle:
  case Normal, Power, Long, Wide, Fire

object HitStyle:
  given JsonCodec[HitStyle] = EnumCodec.byName(values, _.toString)

/** 怪物藏身状态：在外面；埋在地下（看不见、打不到）；石像（打不到）。 */
enum Cover:
  case Open, Buried, Stone

object Cover:
  given JsonCodec[Cover] = EnumCodec.byName(values, _.toString)

/** 中毒：绿毒掉血、红毒降防。 */
enum PoisonKind:
  case Green, Red

object PoisonKind:
  given JsonCodec[PoisonKind] = EnumCodec.byName(values, _.toString)
