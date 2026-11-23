package mir.client.ui

import mir.client.render.ViewOptions
import mir.model.{CharSettings, PickupKind}

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

  val all: List[SettingRow] = List(
    GameToggle("自动捡附近的物品（总开关）", _.autoPickup, (s, v) => s.copy(autoPickup = v)),
    GameChecks("捡：", PickupKind.values.toList.map(k =>
      SettingCheck(k.label, _.pickupKinds(k), (s, v) => s.copy(pickupKinds = if v then s.pickupKinds + k else s.pickupKinds - k)))),
    GamePercent("血量低于多少自动喝红药（%，0 关闭）", _.autoHpPercent, (s, v) => s.copy(autoHpPercent = v)),
    GamePercent("蓝量低于多少自动喝蓝药（%，0 关闭）", _.autoMpPercent, (s, v) => s.copy(autoMpPercent = v)),
    GameToggle("点一下怪就一直打到死", _.stickyAttack, (s, v) => s.copy(stickyAttack = v)),
    GameToggle("站着被打时自动还手", _.autoFightBack, (s, v) => s.copy(autoFightBack = v)),
    GameToggle("战士：烈火冷却好了自动开", _.autoFireSword, (s, v) => s.copy(autoFireSword = v)),
    LocalToggle("头顶显示血条", _.showHpBars, (o, v) => o.copy(showHpBars = v)),
    LocalToggle("地上物品显示名字", _.showItemNames, (o, v) => o.copy(showItemNames = v)),
    LocalToggle("怪物显示名字", _.showMonsterNames, (o, v) => o.copy(showMonsterNames = v)),
    LocalToggle("音效", _.soundOn, (o, v) => o.copy(soundOn = v)),
    LocalToggle("背景音乐", _.musicOn, (o, v) => o.copy(musicOn = v))
  )

  def stepPercent(v: Int, up: Boolean): Int =
    if up then math.min(PercentMax, v + PercentStep) else math.max(0, v - PercentStep)
