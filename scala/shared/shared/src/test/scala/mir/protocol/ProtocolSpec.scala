package mir.protocol

import mir.core.*
import mir.data.Span
import mir.model.*
import mir.rules.Stats
import zio.json.*
import zio.test.*

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

object ProtocolSpec extends ZIOSpecDefault:
  /** 一个 enum 全部分支的名字（编译期从 Mirror 取，新加分支不用改这里）。 */
  private inline def caseNames[T](using m: Mirror.SumOf[T]): Set[String] =
    constValueTuple[m.MirroredElemLabels].toList.map(_.toString).toSet

  private val c1   = Cell(10, 20)
  private val me   = ActorId(7)
  private val pot  = ItemInstance(ItemUid(42L), ItemName("金创药(小量)"), 1, 1, 5)
  private val t0   = Millis(123456789L)
  private val act  = ActionView(ActKind.Walk, t0, 600, from = Some(Cell(9, 20)))
  private val sp   = Span(1, 3)
  private val stat = Stats(19, 15, sp, sp, sp, sp, sp, 5, 15, 900, 0, 0, 0, 0, 0)

  val clientSamples: List[ClientCmd] = List(
    SessionCmd.Login("liewhite", "密码"),
    SessionCmd.ListChars,
    SessionCmd.CreateChar("小明", Job.Taoist, Sex.Female),
    SessionCmd.DeleteChar(CharId(3L)),
    SessionCmd.EnterGame(CharId(3L)),
    SessionCmd.Reloading,
    SessionCmd.Loaded(Device.Phone, LoadStage.Connected(LoginPath.Typed(3200L, 25000L, 1500L, register = true), 3400L)),
    SessionCmd.Loaded(Device.Phone, LoadStage.Connected(LoginPath.Remembered(200L), 3400L)),
    SessionCmd.Loaded(Device.Desktop, LoadStage.Playable(Some(20000L), 5100L, 40200L)),
    SessionCmd.Loaded(Device.Desktop, LoadStage.Playable(None, 5100L, 40200L)),
    GameCmd.Walk(c1, Dir.UpLeft),
    GameCmd.Run(c1, Dir.Down),
    GameCmd.Turn(Dir.Right),
    GameCmd.Attack(Dir.Up, Some(ActorId(100))),
    GameCmd.Attack(Dir.Up, None),
    GameCmd.Cast(SkillId(1), Some(ActorId(101)), c1),
    GameCmd.SetSword(SwordArt.WideHit, true),
    GameCmd.TalkTo(ActorId(5)),
    GameCmd.PickUp,
    GameCmd.Revive,
    GameCmd.Teleport(TeleportRef(3)),
    GameCmd.UseItem(ItemUid(42L)),
    GameCmd.Equip(ItemUid(43L), Some(EquipSlot.RingR)),
    GameCmd.Unequip(EquipSlot.Weapon),
    GameCmd.MoveItem(ItemUid(42L), ItemPlace.Belt(2)),
    GameCmd.MoveItem(ItemUid(42L), ItemPlace.Worn(EquipSlot.BraceletL)),
    GameCmd.DropItem(ItemUid(42L)),
    GameCmd.QuickSell(ItemUid(42L), Some(2)),
    GameCmd.Buy(ActorId(5), ItemName("木剑")),
    GameCmd.Sell(ActorId(5), ItemUid(44L)),
    GameCmd.Sell(ActorId(5), ItemUid(44L), Some(3)),
    GameCmd.Deposit(ActorId(6), ItemUid(44L)),
    GameCmd.Withdraw(ActorId(6), ItemUid(44L)),
    GameCmd.RefineWeapon(ActorId(6), ItemUid(45L), Set(ItemUid(46L), ItemUid(47L))),
    GameCmd.SetSkillKey(SkillId(2), Some(3)),
    GameCmd.SetSkillKey(SkillId(2), None),
    GameCmd.UpdateSettings(CharSettings(autoHpPercent = 55, autoPickup = false)),
    GameCmd.UpdateSettings(CharSettings(pickupKinds = Set(PickupKind.Gold, PickupKind.Equip))),
    GameCmd.Say("!大家好"),
    GameCmd.DealTry(ActorId(8)),
    GameCmd.DealPut(ItemUid(42L), Some(2)),
    GameCmd.DealPut(ItemUid(42L)),
    GameCmd.DealTake(ItemUid(42L)),
    GameCmd.DealGold(Gold(500)),
    GameCmd.DealConfirm,
    GameCmd.DealCancel,
    GameCmd.Inspect(ActorId(8)),
    SocialCmd.AllowGroup(true),
    SocialCmd.CreateGroup("小红"),
    SocialCmd.AddMember("小刚"),
    SocialCmd.RemoveMember("小刚"),
    SocialCmd.GroupSay("集合"),
    SocialCmd.Whisper("小红", "在吗"),
    SocialCmd.AddFriend("小红"),
    SocialCmd.RemoveFriend("小红"),
    SessionCmd.Ping(1700000000000L)
  )

  val serverSamples: List[ServerMsg] = List(
    ServerMsg.LoggedIn("liewhite", "12af80f", "1-0123456789abcdef", "a1b2c3"),
    ServerMsg.CharList(List(CharSummary(CharId(1L), "小明", Job.Warrior, Sex.Male, 22))),
    ServerMsg.Kicked("账号在别处登录"),
    ServerMsg.EnterMap(me, MapId("0"), c1, Dir.Down, t0),
    ServerMsg.Appear(ActorView(me, Look.Human("小明", Job.Warrior, Sex.Male, 1, Some(ItemName("木剑")), None), c1, Dir.Down, Hp(19), Hp(19), act, false)),
    ServerMsg.Appear(ActorView(ActorId(100), Look.Monster(MonsterName("食人花"), Cover.Buried, None), c1, Dir.Left, Hp(50), Hp(50), act, false)),
    ServerMsg.Appear(ActorView(ActorId(5), Look.Npc(MerchantRef(12)), c1, Dir.Down, Hp(1), Hp(1), act, false)),
    ServerMsg.Appear(ActorView(ActorId(102), Look.Monster(MonsterName("蛤蟆"), Cover.Open, None), c1, Dir.Left, Hp(9), Hp(50), act, false, Set(Affliction.RedPoison, Affliction.GreenPoison))),
    ServerMsg.AfflictionsChanged(ActorId(102), Set(Affliction.Paralyzed)),
    ServerMsg.AfflictionsChanged(ActorId(102), Set.empty),
    ServerMsg.Appear(ActorView(ActorId(103), Look.Human("小红", Job.Wizard, Sex.Female, 1, None, None), c1, Dir.Down, Hp(19), Hp(19), act, false, auras = Set(Aura.MagicShield))),
    ServerMsg.AurasChanged(ActorId(103), Set.empty),
    ServerMsg.Disappear(ActorId(100)),
    ServerMsg.Act(me, c1, Dir.Right, ActionView(ActKind.Attack, t0, 510, hit = Some(HitStyle.Fire))),
    ServerMsg.Act(me, c1, Dir.Right, ActionView(ActKind.Spell, t0, 600, spellEffect = Some(1))),
    ServerMsg.HpChanged(ActorId(100), Hp(3), Hp(50)),
    ServerMsg.LookChanged(ActorId(100), Look.Monster(MonsterName("食人花"), Cover.Open, None)),
    ServerMsg.GroundAppear(GroundView(GroundId(9), c1, GroundThing.Item(ItemName("木剑")))),
    ServerMsg.GroundAppear(GroundView(GroundId(10), c1, GroundThing.Coins(Gold(77)))),
    ServerMsg.GroundDisappear(GroundId(9)),
    ServerMsg.FieldAppear(FieldView(FieldId(4), c1, FieldKind.Fire)),
    ServerMsg.FieldAppear(FieldView(FieldId(5), c1, FieldKind.HolyCurtain)),
    ServerMsg.FieldDisappear(FieldId(4)),
    ServerMsg.LookChanged(ActorId(101), Look.Monster(MonsterName("骷髅"), Cover.Open, None, Mood.Seized)),
    ServerMsg.LookChanged(ActorId(103), Look.Monster(MonsterName("变异骷髅"), Cover.Open, Some(PetView(me, Span(23, 43), Span(11, 11), Span(14, 14))))),
    ServerMsg.Effect(EffectView("magic", 100, 10, 80, Anchor.OnActor(me), from = Some(c1), dir = Some(Dir.Up), dirStride = Some(10), blend = true, startedAt = t0, duration = 800)),
    ServerMsg.Effect(EffectView("magic2", 0, 5, 100, Anchor.OnCell(c1), blend = false, startedAt = t0, duration = 500)),
    ServerMsg.Sound(10, Some(c1)),
    ServerMsg.Sound(11, None),
    ServerMsg.Damage(ActorId(100), 12, Some(me)),
    ServerMsg.Miss(ActorId(100)),
    ServerMsg.Heal(me, 20),
    ServerMsg.Stats(SelfStats(1, Exp(50L), Exp(100L), Hp(19), Mp(15), Gold(1000), stat)),
    ServerMsg.Bag(Vector(Some(pot), None), Vector(None, Some(pot))),
    ServerMsg.Equipment(Map(EquipSlot.Weapon -> pot.copy(uid = ItemUid(1L)), EquipSlot.RingL -> pot)),
    ServerMsg.Storage(List(pot)),
    ServerMsg.Skills(List(LearnedSkill(SkillId(1), 2, 300, Some(1)), LearnedSkill(SkillId(3), 0, 0, None))),
    ServerMsg.Cooldowns(Map(SkillId(1) -> t0, SkillId(26) -> (t0 + 10000))),
    ServerMsg.Status(SelfStatus(poison = Some(PoisonKind.Green), poisonUntil = Some(t0), shieldUntil = Some(t0 + 1000))),
    ServerMsg.Status(SelfStatus(acUpUntil = Some(t0 + 5000), macUpUntil = Some(t0 + 6000))),
    ServerMsg.Sword(SwordState(longHitOn = true, wideHitOn = false, fireReady = true)),
    ServerMsg.Settings(CharSettings()),
    ServerMsg.MoveOk,
    ServerMsg.MoveDenied(c1, Dir.Left),
    ServerMsg.Refused("金币不够"),
    ServerMsg.Notice("获得经验 50", Tone.Good),
    ServerMsg.LevelUp(2),
    ServerMsg.Died,
    ServerMsg.NpcOpened(ActorId(5), MerchantRef(12)),
    ServerMsg.Deal(Some(DealView("小红", DealOffer(List(pot.copy(count = 3)), Gold(100), confirmed = true), DealOffer()))),
    ServerMsg.Deal(None),
    ServerMsg.Inspected(InspectView("小红", Sex.Female, 1, mir.rules.PkStanding.Yellow, Map(EquipSlot.RingL -> pot))),
    ServerMsg.Chat("小明", "你好", ChatKind.Shout),
    ServerMsg.Chat("小明", "集合", ChatKind.Group),
    ServerMsg.Group(GroupView(allowGroup = true, members = List("小明", "小红"))),
    ServerMsg.Group(GroupView()),
    ServerMsg.Whisper("小红", "在吗", WhisperWay.Received),
    ServerMsg.Whisper("小红", "在", WhisperWay.Sent),
    ServerMsg.Friends(List(FriendView("小红", true), FriendView("小刚", false))),
    ServerMsg.Announce("5 分钟后重启更新"),
    ServerMsg.Pong(1700000000000L, t0)
  )

  private def roundTrip[A: JsonCodec](samples: List[A]) =
    samples.filter(s => s.toJson.fromJson[A] != Right(s))

  def spec = suite("通信协议")(
    test("客户端指令每种都往返一致") {
      assertTrue(roundTrip(clientSamples).isEmpty)
    },
    test("设备按浏览器标识分手机、平板、电脑（#1327）：iPad 冒充 Mac 时看触屏") {
      val iphone  = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"
      val ipad    = "Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
      val mac     = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15"
      val android = "Mozilla/5.0 (Linux; Android 14; 23127PN0CC) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
      val pad     = "Mozilla/5.0 (Linux; Android 13; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
      val win     = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
      assertTrue(
        Device.of(iphone, true) == Device.Phone, Device.of(android, true) == Device.Phone,
        Device.of(ipad, true) == Device.Tablet, Device.of(mac, true) == Device.Tablet, Device.of(pad, true) == Device.Tablet,
        Device.of(mac, false) == Device.Desktop, Device.of(win, false) == Device.Desktop
      )
    },
    test("服务端事件每种都往返一致") {
      assertTrue(roundTrip(serverSamples).isEmpty)
    },
    test("样例覆盖了全部种类（新加的消息必须补样例）") {
      val c = clientSamples.map(_.asInstanceOf[Product].productPrefix).toSet
      val s = serverSamples.map(_.asInstanceOf[Product].productPrefix).toSet
      assertTrue(
        (caseNames[SessionCmd] ++ caseNames[GameCmd] ++ caseNames[SocialCmd]) -- c == Set.empty[String],
        caseNames[ServerMsg] -- s == Set.empty[String],
        (caseNames[SessionCmd] intersect caseNames[GameCmd]) == Set.empty[String],
        ((caseNames[SessionCmd] ++ caseNames[GameCmd]) intersect caseNames[SocialCmd]) == Set.empty[String]
      )
    },
    test("JSON 形状：分支名作外层键，编号是裸数字，枚举是名字") {
      assertTrue(
        (GameCmd.Walk(Cell(1, 2), Dir.UpLeft): ClientCmd).toJson == """{"Walk":{"from":{"x":1,"y":2},"dir":7}}""",
        (GameCmd.PickUp: ClientCmd).toJson == """{"PickUp":{}}""",
        (ServerMsg.Damage(ActorId(3), 5, None): ServerMsg).toJson == """{"Damage":{"target":3,"amount":5}}""",
        (GameCmd.Unequip(EquipSlot.BraceletL): ClientCmd).toJson == """{"Unequip":{"slot":"braceletL"}}"""
      )
    },
    test("旧存档的设置（#1178 前，带已删掉的 pickupValuableOnly、没有 pickupKinds）照常读出，自动捡物全捡") {
      val old = """{"autoPickup":true,"pickupValuableOnly":true,"autoHpPercent":55}""".fromJson[CharSettings]
      assertTrue(old == Right(CharSettings(autoHpPercent = 55)), old.exists(_.pickupKinds == PickupKind.values.toSet))
    },
    test("#1301 前的旧设置（没有 pickupTier）一次性升级：打开自动捡、勾上装备、档位赤月；新存档原样读回") {
      val old      = """{"autoPickup":false,"pickupKinds":["Gold","Potion"],"autoHpPercent":55}""".fromJson[CharSettings]
      val upgraded = old.exists(s => s.autoPickup && s.pickupKinds == Set(PickupKind.Gold, PickupKind.Potion, PickupKind.Equip) &&
        s.pickupTier == PickupFloor.Redmoon && s.autoHpPercent == 55)
      val mine = CharSettings(autoPickup = false, pickupKinds = Set(PickupKind.Gold), pickupTier = PickupFloor.All)
      assertTrue(upgraded, mine.toJson.fromJson[CharSettings] == Right(mine), mine.toJson.contains("\"pickupTier\":\"All\""))
    },
    test("聊天框：「!!」开头是队伍聊天，「!」开头仍交给世界喊话（#1220）") {
      assertTrue(
        Speech.command("!!  集合") == SocialCmd.GroupSay("集合"),
        Speech.command("!大家好") == GameCmd.Say("!大家好"),
        Speech.command("你好") == GameCmd.Say("你好")
      )
    },
    test("聊天框：「/名字 内容」是私聊，名字到第一个空白为止（#1221）") {
      assertTrue(
        Speech.command("/小红 你好 呀") == SocialCmd.Whisper("小红", "你好 呀"),
        Speech.command("/ 小红  在吗 ") == SocialCmd.Whisper("小红", "在吗"),
        Speech.command("/小红") == SocialCmd.Whisper("小红", ""),
        Speech.command(Speech.whisperTo("小红") + "嗨") == SocialCmd.Whisper("小红", "嗨")
      )
    },
    test("旧存档没有社交偏好时按默认（不允许组队）") {
      assertTrue(
        "{}".fromJson[SocialPrefs] == Right(SocialPrefs()), !SocialPrefs().allowGroup,
        // #1220 的存档没有好友名单
        """{"allowGroup":true}""".fromJson[SocialPrefs] == Right(SocialPrefs(allowGroup = true, friends = Nil))
      )
    },
    test("登录消息只加字段（#1289）：#1289 之前的页面（只认 account、build）照样读得出新服务端的登录消息，发现版本变了去刷新") {
      val json = ServerMsg.loggedIn("liewhite", Release("abc1234", Release.wire, "d")).toJson
      assertTrue(json.fromJson[ProtocolSpec.OldServerMsg] == Right(ProtocolSpec.OldServerMsg.LoggedIn("liewhite", "abc1234")))
    },
    test("通讯格式指纹（#1289）：编译期按类名、字段名和类型、分支展开；改字段名或类型、加分支都会变") {
      val shape = WireShape.shape[ProtocolSpec.ShapeSample]
      assertTrue(
        shape.contains("Hit(target:mir.core.Ids$package$.ActorId,power:scala.Option[scala.Int],at:mir.core.Cell(x:scala.Int,y:scala.Int))"),
        shape.contains(",Quit}"),
        WireShape.shape[ProtocolSpec.ShapeSample] == shape,
        WireShape.digest[ProtocolSpec.ShapeSample] != WireShape.digest[ProtocolSpec.ShapeSample2],
        Release.wire.startsWith(s"${Release.WireRevision}-"),
        Release.wire.length > 10
      )
    },
    test("游戏数据摘要（#1289）：同一段文字一样，改一个字就不一样") {
      assertTrue(
        Release.dataPrint("""{"a":"沃玛"}""") == Release.dataPrint("""{"a":"沃玛"}"""),
        Release.dataPrint("""{"a":"沃玛"}""") != Release.dataPrint("""{"a":"祖玛"}"""),
        Release("x", "w", "d").compatible(Release("y", "w", "d")),
        !Release("x", "w", "d").compatible(Release("y", "w2", "d")),
        !Release("x", "w", "d").compatible(Release("y", "w", "d2"))
      )
    },
    test("坏消息解码失败而不是抛异常") {
      assertTrue(
        """{"Walk":{"from":{"x":1,"y":2},"dir":8}}""".fromJson[ClientCmd].isLeft,
        """{"Fly":{}}""".fromJson[ClientCmd].isLeft,
        """{"Unequip":{"slot":"tail"}}""".fromJson[ClientCmd].isLeft,
        "not json".fromJson[ServerMsg].isLeft
      )
    }
  )

  /** #1289 之前的登录消息长这样 */
  enum OldServerMsg:
    case LoggedIn(account: String, build: String)
  object OldServerMsg:
    given JsonCodec[OldServerMsg] = DeriveJsonCodec.gen

  /** 指纹测试用：一条带字段的分支、一条不带的 */
  enum ShapeSample:
    case Hit(target: ActorId, power: Option[Int], at: Cell)
    case Quit
  /** 同上，只改了一个字段的类型 */
  enum ShapeSample2:
    case Hit(target: ActorId, power: Option[Long], at: Cell)
    case Quit
