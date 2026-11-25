package mir.client.ui

import mir.client.render.ViewOptions
import mir.model.{CharSettings, PickupKind}
import mir.rules.{SpellRules, SwordRules}

/** 设置窗的一页 */
enum SettingTab(val label: String):
  case General extends SettingTab("通用")
  case Warrior extends SettingTab("战士")
  case Wizard  extends SettingTab("法师")
  case Taoist  extends SettingTab("道士")

/**
 * 设置窗的一行（便利功能，原版靠外挂的做成开关，用户 #1137）。分两类，存的地方不同：
 * - Game：挂在角色上、服务端执行（自动喝药、自动捡物……），改了发 UpdateSettings；
 * - Local：只影响本机画面和声音（血条、名字、音效），存浏览器 localStorage。
 */
enum SettingRow(val label: String):
  case GameToggle(text: String, get: CharSettings => Boolean, set: (CharSettings, Boolean) => CharSettings) extends SettingRow(text)
  case GamePercent(text: String, get: CharSettings => Int, set: (CharSettings, Int) => CharSettings) extends SettingRow(text)
  /** 一行里几个小勾选（自动捡哪几类） */
  case GameChecks(text: String, boxes: List[SettingCheck]) extends SettingRow(text)
  case LocalToggle(text: String, get: ViewOptions => Boolean, set: (ViewOptions, Boolean) => ViewOptions) extends SettingRow(text)

/** 一个小勾选：叫什么、怎么读、怎么改。 */
final case class SettingCheck(label: String, get: CharSettings => Boolean, set: (CharSettings, Boolean) => CharSettings)

object SettingRow:
  /** 百分比一次调多少、最大多少 */
  val PercentStep = 5
  val PercentMax  = 95

  /** 设置窗的分页：通用一页，职业自动技能各一页 */
  val tabs: List[(SettingTab, List[SettingRow])] = List(
    SettingTab.General -> List(
      GameToggle("自动捡附近的物品（总开关）", _.autoPickup, (s, v) => s.copy(autoPickup = v)),
      GameChecks("捡：", PickupKind.values.toList.map(k =>
        SettingCheck(k.label, _.pickupKinds(k), (s, v) => s.copy(pickupKinds = if v then s.pickupKinds + k else s.pickupKinds - k)))),
      GamePercent("血量低于多少自动喝红药（%，0 关闭）", _.autoHpPercent, (s, v) => s.copy(autoHpPercent = v)),
      GamePercent("蓝量低于多少自动喝蓝药（%，0 关闭）", _.autoMpPercent, (s, v) => s.copy(autoMpPercent = v)),
      GameToggle("点一下怪就一直打到死", _.stickyAttack, (s, v) => s.copy(stickyAttack = v)),
      GameToggle("站着被打时自动还手", _.autoFightBack, (s, v) => s.copy(autoFightBack = v)),
      LocalToggle("头顶显示血条", _.showHpBars, (o, v) => o.copy(showHpBars = v)),
      LocalToggle("地上物品显示名字", _.showItemNames, (o, v) => o.copy(showItemNames = v)),
      LocalToggle("比身上强的装备名字换色（粉紫）", _.upgradeHint, (o, v) => o.copy(upgradeHint = v)),
      LocalToggle("怪物显示名字", _.showMonsterNames, (o, v) => o.copy(showMonsterNames = v)),
      LocalToggle("音效", _.soundOn, (o, v) => o.copy(soundOn = v)),
      LocalToggle("背景音乐", _.musicOn, (o, v) => o.copy(musicOn = v))
    ),
    SettingTab.Warrior -> List(
      GameToggle("烈火冷却好了自动开（学会烈火剑法才生效）", _.autoFireSword, (s, v) => s.copy(autoFireSword = v)),
      GameToggle(
        s"身边有 ${SwordRules.SmartWideHitMin} 只以上怪就自动用半月（学会半月弯刀才生效，1 只时普通砍不耗蓝）",
        _.smartWideHit, (s, v) => s.copy(smartWideHit = v))
    ),
    SettingTab.Wizard -> List(
      GameToggle("魔法盾没开着就自动开（学会魔法盾才生效，蓝不够时等等再开）", _.autoMagicShield, (s, v) => s.copy(autoMagicShield = v))
    ),
    SettingTab.Taoist -> List(
      GameToggle(s"自动召唤骷髅（不满 ${SpellRules.SummonsEach} 只就召，要学会召唤骷髅、带护身符）", _.autoSummonSkeleton, (s, v) => s.copy(autoSummonSkeleton = v)),
      GameToggle(s"自动召唤神兽（不满 ${SpellRules.SummonsEach} 只就召，要学会召唤神兽、带护身符）", _.autoSummonShinsu, (s, v) => s.copy(autoSummonShinsu = v))
    )
  )

  /** 全部分页的行 */
  val all: List[SettingRow] = tabs.flatMap(_._2)

  def stepPercent(v: Int, up: Boolean): Int =
    if up then math.min(PercentMax, v + PercentStep) else math.max(0, v - PercentStep)
