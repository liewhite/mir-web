package mir.world

/** 原版音效编号（Sound/SoundUtil.pas、Actor.pas SetSound）。这里只决定「什么事发什么声」，播放在客户端。照 TS 版 sounds.ts。 */
object Sounds:
  val walkGround: (Int, Int) = (1, 2)
  val runGround: (Int, Int)  = (3, 4)
  val walkCave: (Int, Int)   = (21, 22)
  val runCave: (Int, Int)    = (23, 24)
  val hitFist      = 57
  val money        = 106
  val eatDrug      = 107
  val spaceOut     = 109
  val spaceIn      = 110
  val itemClick    = 118
  val yedoMan      = 130
  val yedoWoman    = 131
  val longHit      = 132
  val wideHit      = 133
  val fireHitReady = 136
  val fireHit      = 137
  val manStruck    = 138
  val womStruck    = 139
  val manDie       = 144
  val womDie       = 145

  /** 武器挥动声：按武器外观（Shape），Actor.pas:2156；空手为 None。 */
  def weaponSwing(shape: Option[Int]): Int = shape match
    case None                                              => hitFist
    case Some(6 | 20)                                      => 50
    case Some(1)                                           => 51
    case Some(2 | 13 | 9 | 5 | 14 | 22)                    => 52
    case Some(4 | 17 | 10 | 15 | 16 | 23)                  => 53
    case Some(3 | 7 | 11)                                  => 54
    case Some(24)                                          => 55
    case Some(8 | 12 | 18 | 21)                            => 56
    case Some(_)                                           => hitFist

  /** 被武器打中的声音：Actor.pas:2215。 */
  def struckBody(shape: Option[Int]): Int = shape match
    case Some(6 | 1 | 2 | 4 | 5 | 9 | 10 | 13 | 14 | 15 | 16 | 17) => 70
    case Some(3 | 11)                                             => 71
    case Some(8 | 12 | 18)                                        => 72
    case _                                                        => 73

  /** 怪物的几种声音（200 + 外观×10 + 编号）。 */
  enum MonsterCry(val k: Int):
    case Appear extends MonsterCry(0)
    case Idle   extends MonsterCry(1)
    case Attack extends MonsterCry(2)
    case Weapon extends MonsterCry(3)
    case Struck extends MonsterCry(4)
    case Die    extends MonsterCry(5)

  def monster(appr: Int, cry: MonsterCry): Int = 200 + appr * 10 + cry.k

  /** 魔法的几段声音（10000 + 技能编号×10 + 编号）。 */
  enum SpellPhase(val k: Int):
    case Start extends SpellPhase(0)
    case Fly   extends SpellPhase(1)
    case Burst extends SpellPhase(2)

  def magic(skill: mir.core.SkillId, phase: SpellPhase): Int = 10000 + skill.value * 10 + phase.k
