package mir.world

import mir.core.{Millis, MonsterName}
import mir.data.{MissileFx, MonsterDef, MonsterLook}
import mir.rules.Rng

/**
 * 怪物行为的描述：按数据表的 Race（服务端怪物类型）决定怎么出招、会不会藏起来、能不能动、附加本领。
 * 出处：ref/Mir176 GameSrv WorldServer.MonGen.cs CreateMonster（Race → 类）与 M2Server/Monster/Monsters 目录下的各类；
 * 按外观（RaceImg）的画法和特效在 mir.data.MonsterLook。照 TS 版 monster_ai.ts 移植，执行在 MonsterAi。
 */

/** 出招方式。 */
enum Strike:
  /** 贴身物理攻击（AtMonster） */
  case Melee
  /** 贴身，伤害按 physical 比例分成物理和魔法（沃玛教主、祖玛教主 HitMagAttackTarget） */
  case Split(physical: Double)
  /** 贴身魔法攻击（火焰沃玛 MagCowMonster） */
  case Magic
  /** 贴身喷气：魔法伤害，有机会麻痹（洞蛆、楔蛾、粪虫 GasAttackMonster） */
  case Gas
  /** 朝前方 2 格喷毒：魔法伤害，有机会中绿毒（蜘蛛 SpitSpider） */
  case Spit
  /** 远程投掷（掷斧骷髅、弓箭手 DualAxeMonster） */
  case Missile(range: Int, fly: Option[MissileFx])
  /** 朝目标方向放一道 8 格电光，线上的都挨打；目标太近会后退（僵尸 LightingZombi） */
  case Lightning
  /** 打范围内所有敌人（赤月恶魔 BigHeart；触龙神 CentipedeKing 附带中毒、麻痹） */
  case Area(range: Int, poison: Boolean)
  /** 贴身自爆（爆裂蜘蛛 ExplosionSpider），活 60 秒也会自爆 */
  case Explode
  /** 不攻击，只放小怪（角蝇巢 BeeQueen、幻影蜘蛛 SpiderHouse）；below：小怪出在脚下一格 */
  case Spawn(child: MonsterName, below: Boolean)

/** 平时藏在哪。 */
enum Hiding:
  case Never
  /** 埋在地下，敌人走近（< comeOut 格）钻出来；目标离开 reach 格就钻回去（食人花 StickMonster） */
  case Burrow(comeOut: Int, reach: Int)
  /** 埋在地下，敌人到 3 格内钻出来，之后一直在外面（僵尸 DigOutZombi） */
  case DigOut
  /** 触龙神：敌人 < 4 格钻出，钻出 10 秒后附近没人就钻回去，钻出时回满血 */
  case Centipede
  /** 石像，敌人到 2 格内醒来；wakeAll 时附近 7 格的石像一起醒（祖玛雕像、祖玛教主） */
  case Statue(wakeAll: Boolean)

/**
 * 附加本领（Boss 等少数怪才有）。每种本领在一只怪身上的状态是对应的 ExtraState，Monster 只存这些状态；
 * 加一种本领：这里加一个分支、ExtraState 加它的状态、MonsterAi 的本领处理加一个分支。
 */
enum Extra:
  /** 死后几秒原地复活，血上限和经验减半（僵尸 ZilKinZombi） */
  case Revive
  /** 血每掉一档（20%）召一批祖玛怪（祖玛教主） */
  case SummonZuma
  /** 沃玛教主：被围住时瞬移到目标身后；血越少越狂暴（攻速、移速变快） */
  case CowKing

  /** 刚刷出来时的状态。 */
  def initial(using rng: Rng): ExtraState = this match
    // 1/3 的僵尸能复活 1~3 次（ZilKinZombi.Create）
    case Revive     => ExtraState.Revive(left = if rng.random(3) == 0 then rng.random(3) + 1 else 0, at = None, times = 0)
    case SummonZuma => ExtraState.SummonZuma(level = 5, lastCheck = Millis.zero)
    // 档位初值 0（CowKingMonster 构造 CrazyCount = 0）。TS 版和祖玛教主共用字段、初值误为 5，血刚好在第 5 档时第一次检查不狂暴
    case CowKing    => ExtraState.CowKing(level = 0, rage = Rage.Calm, rageAt = Millis.zero, lastCheck = Millis.zero)

/** 一项附加本领在某只怪身上的状态（不可变，处理完换新的）。 */
enum ExtraState:
  /** 还能复活几次、几时复活、已经复活过几次（每次经验减半） */
  case Revive(left: Int, at: Option[Millis], times: Int)
  /** 召唤档位（满血 5，血每掉 20% 召一批、档位减一）、上次检查时刻（每秒一次） */
  case SummonZuma(level: Int, lastCheck: Millis)
  /** 狂暴档位（按剩余血算，变了就准备狂暴）、狂暴阶段及开始时刻、上次检查时刻（每 30 秒一次） */
  case CowKing(level: Int, rage: Rage, rageAt: Millis, lastCheck: Millis)

  /** 打死给的经验倍数 */
  def expScale: Double = this match
    case Revive(_, _, times) => math.pow(0.5, times)
    case _                   => 1.0

/** 沃玛教主狂暴阶段：平静 → 准备（8 秒不出手）→ 狂暴（8 秒攻速、移速变快）→ 平静。 */
enum Rage:
  case Calm, Ready, Crazy

final case class Behavior(
    strike: Strike,
    hiding: Hiding = Hiding.Never,
    /** 原地不动（StickMode） */
    rooted: Boolean = false,
    /** 不主动打人（鸡、鹿、羊） */
    passive: Boolean = false,
    extras: List[Extra] = Nil
)

object Behavior:
  /** 祖玛教主召唤的小怪 */
  val zumaMinions: Vector[MonsterName] = Vector("祖玛卫士", "祖玛雕像", "祖玛弓箭手", "楔蛾").map(MonsterName(_))

  /** 数据表 Race → 行为（WorldServer.MonGen.cs CreateMonster）。没列出的按普通近战。 */
  def of(d: MonsterDef): Behavior = d.race match
    case 50 | 51 | 52 => Behavior(Strike.Melee, passive = true)
    case 82 | 119     => Behavior(Strike.Spit)
    case 85           => Behavior(Strike.Melee, hiding = Hiding.Burrow(4, 4), rooted = true)
    case 87 | 104 | 112 => Behavior(Strike.Missile(7, MonsterLook.of(d).missile))
    case 90 | 105 | 106 => Behavior(Strike.Gas)
    case 91            => Behavior(Strike.Magic)
    case 92            => Behavior(Strike.Split(0.5), extras = List(Extra.CowKing))
    case 94            => Behavior(Strike.Lightning)
    case 95            => Behavior(Strike.Melee, hiding = Hiding.DigOut)
    case 96            => Behavior(Strike.Melee, extras = List(Extra.Revive))
    case 101           => Behavior(Strike.Melee, hiding = Hiding.Statue(wakeAll = true))
    case 102           => Behavior(Strike.Split(0), hiding = Hiding.Statue(wakeAll = false), extras = List(Extra.SummonZuma))
    case 103           => Behavior(Strike.Spawn(MonsterName("蝙蝠"), below = false), rooted = true)
    case 107           => Behavior(Strike.Area(6, poison = true), hiding = Hiding.Centipede, rooted = true)
    case 115           => Behavior(Strike.Area(16, poison = false), rooted = true)
    case 116           => Behavior(Strike.Spawn(MonsterName("爆裂蜘蛛"), below = true), rooted = true)
    case 117           => Behavior(Strike.Explode)
    case _             => Behavior(Strike.Melee)

  /**
   * 守卫（不打玩家、玩家打不到，见到怪就打）：按 Race 认（OpenMir2 Race.cs：11 Supreguard、12 Petsupreguard、
   * 20 ArcherPolice、112 ArcherGuard）。TS 版按名字带「卫士」认，把沃玛卫士、祖玛卫士也当成了守卫。
   */
  def isGuard(d: MonsterDef): Boolean = Set(11, 12, 20, 112).contains(d.race)

  /** 动物：鸡（51）、鹿羊（52）、狼（53），原版 Animal = true，诱惑之光对它们无效（WorldServer.MonGen.cs）。 */
  def isAnimal(d: MonsterDef): Boolean = Set(51, 52, 53).contains(d.race)
