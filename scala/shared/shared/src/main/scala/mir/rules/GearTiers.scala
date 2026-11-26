package mir.rules

import mir.core.ItemName
import mir.data.{ItemDef, Span}
import mir.model.{CharSettings, GearTier, Job, PickupFloor}

/**
 * 哪件装备算沃玛 / 祖玛 / 赤月装（#1301，自动捡装备的档位滑条和「出现好装备」提示用）。
 * 手写名单，不分职业，按 1.76 数据表里谁掉它分（game.json drops）：
 * - 沃玛：沃玛教主那批掉的首饰和武器；另把虹魔、魔血、记忆三套也放这档；
 * - 祖玛：祖玛教主那批（祖玛卫士 3、祖玛弓箭手 3、祖玛雕像 3、千年树妖）掉的首饰和武器，加上战神盔甲、恶魔长袍、幽灵战衣，
 *   血饮（用户 11-25 定）；
 * - 赤月：圣战、法神、天尊三套（双头血魔、双头金刚、赤月恶魔掉），屠龙、嗜魂法杖，暗之 boss 掉的六件衣服，
 *   牛魔王掉的龙牙、怒斩、逍遥扇（用户 11-25 定）。
 * - 雷霆（#1312，1.80）：雷霆、烈焰、光芒三套（衣服、项链、护腕、戒指、腰带、靴子）；
 * - 终极（#1312，1.80）：开天、镇天、玄天，凤天魔甲、凰天魔衣，战神、圣魔、真魂三套首饰。
 *   1.80 的普通腰带靴子（布鞋…钢铁腰带）不入档。
 * 注意数据表和常见说法不同处：三眼手镯是沃玛教主掉的、心灵手镯是祖玛那批掉的，龙纹剑也是祖玛那批掉的，都照数据表。
 * 名单写在 DESIGN.md「装备档位」一节；改了两边一起改，测试核对名字都在数据表里。
 */
object GearTiers:
  private def names(tier: GearTier, ns: String*): Seq[(ItemName, GearTier)] = ns.map(n => ItemName(n) -> tier)

  val all: Map[ItemName, GearTier] = (
    names(
      GearTier.Woma,
      "炼狱", "魔杖", "银蛇",
      "幽灵项链", "龙之戒指", "幽灵手套",
      "生命项链", "红宝石戒指", "思贝儿手镯",
      "天珠项链", "铂金戒指", "三眼手镯",
      "虹魔项链", "虹魔手镯", "虹魔戒指", "魔血项链", "魔血手镯", "魔血戒指",
      "记忆头盔", "记忆项链", "记忆手镯", "记忆戒指"
    ) ++ names(
      GearTier.Zuma,
      "裁决之杖", "骨玉权杖", "无极棍", "龙纹剑", "血饮",
      "绿色项链", "骑士手镯", "力量戒指",
      "恶魔铃铛", "龙之手镯", "紫碧螺",
      "灵魂项链", "心灵手镯", "泰坦戒指",
      "阎罗手套", "黑铁头盔",
      "战神盔甲(男)", "战神盔甲(女)", "恶魔长袍(男)", "恶魔长袍(女)", "幽灵战衣(男)", "幽灵战衣(女)"
    ) ++ names(
      GearTier.Redmoon,
      "屠龙", "嗜魂法杖", "龙牙", "怒斩", "逍遥扇",
      "圣战头盔", "圣战项链", "圣战手镯", "圣战戒指",
      "法神头盔", "法神项链", "法神手镯", "法神戒指",
      "天尊头盔", "天尊项链", "天尊手镯", "天尊戒指",
      "天魔神甲", "圣战宝甲", "法神披风", "霓裳羽衣", "天尊道袍", "天师长袍"
    ) ++ names(
      GearTier.Thunder,
      "雷霆战甲(男)", "雷霆战甲(女)", "雷霆项链", "雷霆护腕", "雷霆战戒", "雷霆腰带", "雷霆战靴",
      "烈焰魔衣(男)", "烈焰魔衣(女)", "烈焰项链", "烈焰护腕", "烈焰魔戒", "烈焰腰带", "烈焰魔靴",
      "光芒道袍(男)", "光芒道袍(女)", "光芒项链", "光芒护腕", "光芒道戒", "光芒腰带", "光芒道靴"
    ) ++ names(
      GearTier.Ultimate,
      "开天", "镇天", "玄天", "凤天魔甲", "凰天魔衣",
      "战神项链", "战神手镯", "战神戒指",
      "圣魔项链", "圣魔手镯", "圣魔戒指",
      "真魂项链", "真魂手镯", "真魂戒指"
    )
  ).toMap

  def of(name: ItemName): Option[GearTier] = all.get(name)

  /** 自动捡装备时这件过不过档位门槛（「全部」都过）。 */
  def passes(name: ItemName, floor: PickupFloor): Boolean =
    floor.tier.forall(f => of(name).exists(_.atLeast(f)))

  /**
   * 设了档位（不是「全部」）时自动捡这件：过档位门槛，且不是别的职业的装备（用户 11-26 定，留言板 #26：
   * 道士设沃玛档在幻境捡了一包战神盔甲、恶魔长袍）。「全部」照旧什么装备都捡。
   */
  def picks(d: ItemDef, floor: PickupFloor, job: Job): Boolean =
    passes(d.name, floor) && (floor.tier.isEmpty || jobOf(d).forall(_ == job))

  /**
   * 装备归哪个职业（用户 11-26 定，留言板 #26），None 是谁都用得上：
   * - 穿戴要求是攻击/魔法/道术的，归战士/法师/道士（战神盔甲、恶魔长袍、生命项链）；
   * - 只要求等级的看加的属性（下限或上限有一个不为 0 就算加，和物品说明一致）：加道术不加魔法算道士（银蛇、龙纹剑、魔血戒指），
   *   加魔法不加道术算法师（魔杖、魔血项链），只加攻击算战士（天魔神甲、屠龙）；
   * - 魔法道术都加（凤天魔甲）或都不加（幽灵手套、记忆头盔）的谁都算。
   */
  def jobOf(d: ItemDef): Option[Job] =
    def adds(s: Span): Boolean = s.lo > 0 || s.hi > 0
    Requirement.of(d) match
      case Requirement.Dc(_) => Some(Job.Warrior)
      case Requirement.Mc(_) => Some(Job.Wizard)
      case Requirement.Sc(_) => Some(Job.Taoist)
      case Requirement.Level(_) =>
        (adds(d.dc), adds(d.mc), adds(d.sc)) match
          case (_, false, true)     => Some(Job.Taoist)
          case (_, true, false)     => Some(Job.Wizard)
          case (true, false, false) => Some(Job.Warrior)
          case _                    => None

  /** 地上出现这件要不要在聊天栏提示：达到设置里的档位（「全部」时按沃玛），返回它的档位。 */
  def worthAnnouncing(name: ItemName, s: CharSettings): Option[GearTier] =
    of(name).filter(_.atLeast(s.pickupTier.tier.getOrElse(GearTier.Woma)))
