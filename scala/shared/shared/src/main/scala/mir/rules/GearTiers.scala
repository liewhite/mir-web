package mir.rules

import mir.core.ItemName
import mir.model.{CharSettings, GearTier, PickupFloor}

/**
 * 哪件装备算沃玛 / 祖玛 / 赤月装（#1301，自动捡装备的档位滑条和「出现好装备」提示用）。
 * 手写名单，不分职业，按 1.76 数据表里谁掉它分（game.json drops）：
 * - 沃玛：沃玛教主那批掉的首饰和武器；另把虹魔、魔血、记忆三套也放这档；
 * - 祖玛：祖玛教主那批（祖玛卫士 3、祖玛弓箭手 3、祖玛雕像 3、千年树妖）掉的首饰和武器，加上战神盔甲、恶魔长袍、幽灵战衣，
 *   血饮（用户 11-25 定）；
 * - 赤月：圣战、法神、天尊三套（双头血魔、双头金刚、赤月恶魔掉），屠龙、嗜魂法杖，暗之 boss 掉的六件衣服，
 *   牛魔王掉的龙牙、怒斩、逍遥扇（用户 11-25 定）。
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
    )
  ).toMap

  def of(name: ItemName): Option[GearTier] = all.get(name)

  /** 自动捡装备时这件过不过档位门槛（「全部」都过）。 */
  def passes(name: ItemName, floor: PickupFloor): Boolean =
    floor.tier.forall(f => of(name).exists(_.atLeast(f)))

  /** 地上出现这件要不要在聊天栏提示：达到设置里的档位（「全部」时按沃玛），返回它的档位。 */
  def worthAnnouncing(name: ItemName, s: CharSettings): Option[GearTier] =
    of(name).filter(_.atLeast(s.pickupTier.tier.getOrElse(GearTier.Woma)))
