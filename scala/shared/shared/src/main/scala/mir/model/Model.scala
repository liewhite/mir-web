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
  /** 腰带、靴子：1.76 没有，照 1.80 加（#1311）；旧存档里没有这两个键，读进来就是空着 */
  case Belt      extends EquipSlot("belt", "腰带")
  case Boots     extends EquipSlot("boots", "靴子")

object EquipSlot:
  given JsonCodec[EquipSlot] = EnumCodec.byName(values, _.key)
  private val field          = EnumCodec.fieldByName(values, _.key)
  given JsonFieldEncoder[EquipSlot] = field._1
  given JsonFieldDecoder[EquipSlot] = field._2

/** 正在做的动作；客户端按它取帧和插值位置。 */
enum ActKind:
  case Stand, Walk, Run, Attack, Heavy, Spell, Struck, Die, Dead
  /** 野蛮冲撞往前冲（from 为空时是撞不动、原地冲一下，原版 SM_RUSH / SM_RUSHKUNG） */
  case Rush
  /** 被推得倒退（脸朝推的人，原版 SM_BACKSTEP） */
  case Backstep
  /** 怪物从地下钻出、石像醒来、僵尸复活 */
  case DigUp
  /** 钻回地下 */
  case DigDown

  /** 从一格挪到另一格的动作（有 from 时画面按时间插值位置） */
  def moving: Boolean = this match
    case Walk | Run | Rush | Backstep => true
    case _                            => false

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

/** 怪物被法术弄成的状态（名字变色）：平常；被定住（诱惑之光、困魔咒，不动不打，挨打解除）；发狂（诱惑之光惹怒，见谁打谁）。 */
enum Mood:
  case Calm, Seized, Crazy

object Mood:
  given JsonCodec[Mood] = EnumCodec.byName(values, _.toString)

/** 地上持续的法术：火墙（踩上掉血）、困魔咒的光圈（只是样子）。 */
enum FieldKind:
  case Fire, HolyCurtain

object FieldKind:
  given JsonCodec[FieldKind] = EnumCodec.byName(values, _.toString)

/** 中毒：绿毒掉血、红毒降防。 */
enum PoisonKind:
  case Green, Red

object PoisonKind:
  given JsonCodec[PoisonKind] = EnumCodec.byName(values, _.toString)

/**
 * 身上状态把角色染成的颜色（原版客户端 Actor.pas GetDrawEffectValue）：绿毒染绿、红毒染红、麻痹染灰。
 * 几种同时有时只显示一种，按原版后判的盖前面的：麻痹 > 红毒 > 绿毒。
 */
enum BodyTint:
  case Green, Red, Gray

object BodyTint:
  given JsonCodec[BodyTint] = EnumCodec.byName(values, _.toString)

/** 身上看得见的异常状态：锁定信息卡列出来，身子的染色也由它推出（#1212）。 */
enum Affliction:
  case GreenPoison, RedPoison, Paralyzed

object Affliction:
  given JsonCodec[Affliction] = EnumCodec.byName(values, _.toString)

  /** 染成的颜色：几种同时有时只显示一种，麻痹 > 红毒 > 绿毒（见 BodyTint）。 */
  def tint(as: Set[Affliction]): Option[BodyTint] =
    if as(Paralyzed) then Some(BodyTint.Gray)
    else if as(RedPoison) then Some(BodyTint.Red)
    else Option.when(as(GreenPoison))(BodyTint.Green)

/**
 * 身上看得见的增益光效：周围所有人都画（原版人物状态位 STATE_BUBBLEDEFENCEUP 广播给周围，
 * HumActor.pas 对任何开着盾的人都画盾，#1297）。
 */
enum Aura:
  case MagicShield

object Aura:
  given JsonCodec[Aura] = EnumCodec.byName(values, _.toString)
