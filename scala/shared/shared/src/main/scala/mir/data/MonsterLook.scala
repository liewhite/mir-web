package mir.data

import mir.core.Dir

/** 飞行物特效：图库、起始帧、16 方向之间的帧间隔（飞斧 10，箭 1）、帧数。 */
final case class MissileFx(lib: String, base: Int, stride: Int, frames: Int)

/**
 * 攻击时身上的特效（原版 TGasKuDeGi.LoadSurface 等）：图库、起始帧（方向间隔 10）；
 * dir16：按 16 方向朝目标；extra：比攻击动作多放几帧；frames：固定帧数（不跟攻击动作走）；
 * atFrame：攻击动作放到第几帧（从 0 数）才出现（原版 RunFrameAction）；frameTime：特效自己的每帧毫秒（不跟攻击动作走）。
 */
final case class AttackFx(
    lib: String,
    base: Int,
    dir16: Boolean = false,
    extra: Int = 0,
    frames: Option[Int] = None,
    atFrame: Int = 0,
    frameTime: Option[Int] = None
)

/** 攻击放哪套帧。 */
enum AttackAnim:
  /** ActAttack */
  case Normal
  /** ActCritical（触龙神） */
  case Critical

/**
 * 按外观编号（数据表 RaceImg）的特殊画法和特效：原版客户端按 RaceImg 选不同的类（TKillingHerb、TSculptureMon、
 * TGasKuDeGi 等，见 ref/mir2delphi Actor.pas / AxeMon.pas）。服务端出招、钻地动画时长和客户端画帧都查这一张表，
 * RaceImg 的数字只出现在这里。
 */
final case class MonsterLook(
    /** 食人花家族（TKillingHerb）：站立不分方向，钻出用走路帧、钻回用 ActDeath */
    herb: Boolean = false,
    /** 只有一个朝向，不转身（TKillingHerb、TBeeQueen 家族） */
    fixedDir: Boolean = false,
    /** ActDeath 只有一个方向（变异骷髅、祖玛雕像、祖玛教主） */
    deathNoDir: Boolean = false,
    attackAnim: AttackAnim = AttackAnim.Normal,
    /** 石像状态时朝哪（祖玛教主坐像朝左下） */
    statueDir: Option[Dir] = None,
    attackFx: Option[AttackFx] = None,
    /** 远程攻击飞出去的东西（飞斧、刺、箭）；没有的远程怪只放攻击动作 */
    missile: Option[MissileFx] = None,
    /** 钻下去倒放 ActCritical（大神兽变回小神兽，TSkeletonOma SM_DIGDOWN race 55）；否则正放 ActDeath */
    digDownReversed: Boolean = false
):
  /** 攻击时放的那套帧 */
  def attackAction(t: MonsterActionTable): ActionInfo = attackAnim match
    case AttackAnim.Normal   => t.attack
    case AttackAnim.Critical => t.critical

object MonsterLook:
  private val Plain = MonsterLook()

  private val table: Map[Int, MonsterLook] = Map(
    13 -> MonsterLook(herb = true), // 食人花
    15 -> MonsterLook(missile = Some(MissileFx("mon3", 447, 10, 3))), // 掷斧骷髅：飞斧
    16 -> MonsterLook(attackFx = Some(AttackFx("mon3", 1444))), // 洞蛆毒气
    20 -> MonsterLook(attackFx = Some(AttackFx("mon4", 1800, extra = 1))), // 火焰沃玛
    21 -> MonsterLook(attackFx = Some(AttackFx("mon4", 1900))), // 沃玛教主
    22 -> MonsterLook(missile = Some(MissileFx("mon3", 2967, 10, 3))), // 刺
    23 -> MonsterLook(deathNoDir = true), // 变异骷髅
    33 -> MonsterLook(herb = true, fixedDir = true, attackAnim = AttackAnim.Critical), // 触龙神
    34 -> MonsterLook(herb = true, fixedDir = true),
    35 -> MonsterLook(herb = true, fixedDir = true),
    40 -> MonsterLook(attackFx = Some(AttackFx("mon5", 350, dir16 = true))), // 僵尸电光
    43 -> MonsterLook(fixedDir = true), // 角蝇巢（TBeeQueen）
    45 -> MonsterLook(missile = Some(MissileFx("effect", 272, 1, 1))), // 弓箭手：箭
    48 -> MonsterLook(deathNoDir = true), // 祖玛雕像
    49 -> MonsterLook(deathNoDir = true, statueDir = Some(Dir.DownLeft), attackFx = Some(AttackFx("mon7", 1680, frames = Some(9)))), // 祖玛教主
    52 -> MonsterLook(attackFx = Some(AttackFx("mon4", 3590))), // 楔蛾
    53 -> MonsterLook(attackFx = Some(AttackFx("mon3", 3590))), // 粪虫
    // 大神兽（TWarriorElfMonster）：攻击第 5 帧在脚下喷火 Mon18 820 + 10×方向 + 1 起 5 帧、每帧 100ms（WARRIORELFFIREBASE）
    55 -> MonsterLook(attackFx = Some(AttackFx("mon18", 821, frames = Some(5), atFrame = 5, frameTime = Some(100))), digDownReversed = true)
  )

  def of(raceImg: Int): MonsterLook = table.getOrElse(raceImg, Plain)
  def of(d: MonsterDef): MonsterLook = of(d.raceImg)
