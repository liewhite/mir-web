package mir.model

import mir.core.*
import mir.data.ItemDef
import zio.json.*
import zio.json.ast.Json

/**
 * 一件具体的物品。dura：当前持久（药品、卷轴为 1；符和毒是剩余次数×100）。
 * count：一格里叠了几个（#1159）——可叠的消耗品（ItemKind.stacks）同名并成一叠，其他永远是 1；
 * 存档里没有这个字段时按 1。
 */
final case class ItemInstance(uid: ItemUid, name: ItemName, dura: Int, duraMax: Int, count: Int = 1, bonus: Option[ItemBonus] = None):
  /** 带极品附加属性（#1191；只加了持久的不算，原版也不算）。 */
  def special: Boolean = bonus.isDefined

object ItemInstance:
  given JsonCodec[ItemInstance] = DeriveJsonCodec.gen

  def fresh(uid: ItemUid, d: ItemDef): ItemInstance = ItemInstance(uid, d.name, d.dura, d.dura)

/**
 * 极品附加属性（#1191，原版 UserItem.Desc 按含义拆开）。怪物爆出的装备照原版随机生成（规则见 mir.rules.RandomUpgrade），
 * 加在物品表数值的上限上，怎么起作用仍由 ItemEffects 按 StdMode 解读。全为 0 的不存（ItemInstance.bonus 为 None）。
 * 字段名进存档，改名要兼顾旧存档。
 */
enum ItemBonus:
  /**
   * 武器：攻击/魔法/道术上限、准确、攻击速度（可为负：原版 2/3 的攻速附加是减速）、强度（砍怪时少磨持久），
   * 以及祝福油加的幸运、被诅咒的诅咒（原版 btValue[3]、btValue[4]，见 BlessOil）。
   */
  case Weapon(dc: Int = 0, mc: Int = 0, sc: Int = 0, hit: Int = 0, attackSpeed: Int = 0, strength: Int = 0, luck: Int = 0, curse: Int = 0)
  /**
   * 衣服、头盔、项链、戒指、手镯：加在物品表 AC、MAC、DC、MC、SC 的上限上。
   * AC、MAC 两栏的含义随部位变（衣服头盔是防御魔防，有的项链是准确敏捷、有的是幸运等），由 ItemEffects 解读。
   */
  case Gear(ac: Int = 0, mac: Int = 0, dc: Int = 0, mc: Int = 0, sc: Int = 0)

object ItemBonus:
  given JsonCodec[ItemBonus] = DeriveJsonCodec.gen

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
    /** 自动捡附近的物品（总开关） */
    autoPickup: Boolean = true,
    /** 自动捡哪几类（#1178；存档里没有这个字段时全捡） */
    pickupKinds: Set[PickupKind] = PickupKind.values.toSet,
    /**
     * 自动捡装备的最低档位（#1301，设置里的滑条；用户定默认赤月）：只捡这一档及以上的装备（档位见 mir.rules.GearTiers），
     * 别的种类不受影响。也是「出现好装备」聊天提示的门槛（「全部」时按沃玛提示）。
     * 存档里没有这个字段的是 #1301 之前的旧存档，读的时候一次性升级，见 CharSettings.legacy。
     */
    pickupTier: PickupFloor = PickupFloor.Redmoon,
    /** 血量低于这个百分比自动喝红（0 关闭） */
    autoHpPercent: Int = 40,
    /** 蓝量低于这个百分比自动喝蓝（0 关闭） */
    autoMpPercent: Int = 20,
    /** 点一下怪就一直打到它死 */
    stickyAttack: Boolean = true,
    /** 被怪打时自动还手 */
    autoFightBack: Boolean = true,
    /** 自动打怪（#1307，默认关）：闲着时自己去打附近的野怪（不抢别人正在打的） */
    autoHunt: Boolean = false,
    /** 自动随机（#1350，默认关，要同时开着自动打怪）：附近没怪可打就用随机传送卷飞走，飞到有怪的地方就停下来打 */
    autoRandom: Boolean = false,
    /**
     * 自动打怪先捡东西（#1354，默认关 = #1337 的先打怪）：开着时闲下来先捡附近该捡的再找下一只怪；被打还手仍然最先。
     * 只在自动捡、自动打怪都开着时有区别。
     */
    pickupFirst: Boolean = false,
    /** 持续施法（#1308，默认关）：对怪放了攻击技能就接着一直放到它死；开着自动打怪时也用技能打（法师、道士） */
    sustainCast: Boolean = false,
    /** 战士：烈火好了就自动开 */
    autoFireSword: Boolean = true,
    /** 战士：半月关着时，身边有 2 只以上怪就这一刀自动用半月弯刀（#1273） */
    smartWideHit: Boolean = true,
    /** 战士：自动站到隔一格的位置用刺杀 */
    autoLongHitPosition: Boolean = false,
    /** 法师：魔法盾没开着（或到期了）就自动开 */
    autoMagicShield: Boolean = false,
    /** 道士：骷髅不满 SpellRules.SummonsEach 只就自动召唤（要学会召唤骷髅、带护身符） */
    autoSummonSkeleton: Boolean = false,
    /** 道士：神兽不满 SpellRules.SummonsEach 只就自动召唤 */
    autoSummonShinsu: Boolean = false,
    /** 道士（#1307）：自己、队友、宝宝血低了自动放治愈术 */
    autoHeal: Boolean = false,
    /** 道士（#1307）：神圣战甲术效果没了就自动补 */
    autoHolyArmor: Boolean = false,
    /** 道士（#1307）：幽灵盾效果没了就自动补 */
    autoSoulShield: Boolean = false,
    /** 道士（#1307）：安全区外隐身没了就自动补（出手后等一会儿） */
    autoInvisible: Boolean = false,
    /** 道士（#1308）：打怪时目标缺绿毒或红毒就自动放施毒术补上 */
    autoPoison: Boolean = false
)

object CharSettings:
  private val derived: JsonCodec[CharSettings] = DeriveJsonCodec.gen

  /**
   * #1301 之前的旧存档（没有 pickupTier 字段）读进来时的一次性升级（用户 11-25 定：更新后所有玩家默认打开自动捡装备、
   * 档位拉到赤月，玩家自己能关）：打开自动捡总开关、勾上「装备」，档位取默认的赤月。存回去就带上字段，不会再升。
   */
  def legacy(s: CharSettings): CharSettings =
    s.copy(autoPickup = true, pickupKinds = s.pickupKinds + PickupKind.Equip, pickupTier = PickupFloor.Redmoon)

  private val TierField = "pickupTier"

  given JsonCodec[CharSettings] = JsonCodec(
    derived.encoder,
    JsonDecoder[Json.Obj].mapOrFail { o =>
      derived.decoder.fromJsonAST(o).map(s => if o.contains(TierField) then s else legacy(s))
    }
  )

/**
 * 角色的社交偏好（#1220 起）：归服务端的 mir.social 管（跨地图），进存档但世界不碰它——
 * 服务端存档时把社交那边的当前值填进 CharacterData。存档里没有这个字段时按默认。
 */
final case class SocialPrefs(
    /** 允许别人拉我进组（原版 m_boAllowGroup，默认关） */
    allowGroup: Boolean = false,
    /** 好友名单（#1221，角色名，按加入先后；单向，不用对方同意） */
    friends: List[String] = Nil
)

object SocialPrefs:
  given JsonCodec[SocialPrefs] = DeriveJsonCodec.gen

/**
 * 自动捡物的分类（#1178，设置里逐类勾选）。物品归哪类由 mir.rules.ItemKind.pickupKind 按 StdMode 定。
 * 名字进存档（CharSettings.pickupKinds），改名要兼顾旧存档。
 */
enum PickupKind(val label: String):
  case Gold     extends PickupKind("金币")
  case Potion   extends PickupKind("药品")
  case Equip    extends PickupKind("装备")
  case Book     extends PickupKind("书")
  case Material extends PickupKind("肉矿石")
  case Amulet   extends PickupKind("符毒")
  case Scroll   extends PickupKind("卷轴")
  case Other    extends PickupKind("其他")

object PickupKind:
  given JsonCodec[PickupKind] = EnumCodec.byName(values, _.toString)

/**
 * 装备档位（#1301）：玩家口中的「沃玛装 / 祖玛装 / 赤月装」，#1312 加 1.80 的雷霆系列和终极系列，按出处从低到高（声明顺序就是高低）。
 * 哪件归哪档见 mir.rules.GearTiers。名字进存档（CharSettings.pickupTier），改名要兼顾旧存档。
 */
enum GearTier(val label: String):
  case Woma    extends GearTier("沃玛")
  case Zuma    extends GearTier("祖玛")
  case Redmoon extends GearTier("赤月")
  case Thunder extends GearTier("雷霆")
  case Ultimate extends GearTier("终极")

  def atLeast(floor: GearTier): Boolean = ordinal >= floor.ordinal

/**
 * 自动捡装备滑条的一挡（#1301）：最左「全部」，往右沃玛、祖玛、赤月、雷霆、终极（该档及以上）。
 * 名字进存档（CharSettings.pickupTier），改名要兼顾旧存档。
 */
enum PickupFloor(val label: String, val tier: Option[GearTier]):
  case All     extends PickupFloor("全部", None)
  case Woma    extends PickupFloor(GearTier.Woma.label, Some(GearTier.Woma))
  case Zuma    extends PickupFloor(GearTier.Zuma.label, Some(GearTier.Zuma))
  case Redmoon extends PickupFloor(GearTier.Redmoon.label, Some(GearTier.Redmoon))
  case Thunder extends PickupFloor(GearTier.Thunder.label, Some(GearTier.Thunder))
  case Ultimate extends PickupFloor(GearTier.Ultimate.label, Some(GearTier.Ultimate))

object PickupFloor:
  given JsonCodec[PickupFloor] = EnumCodec.byName(values, _.toString)
