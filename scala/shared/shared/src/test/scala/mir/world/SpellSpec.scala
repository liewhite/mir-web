package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.{Durability, PetBody, PoisonDose, Rng, SpellRules, Summoning, SwordRules}
import mir.terrain.Terrain
import mir.rules.SpellRules.{TameOutcome, TameTarget}
import mir.testkit.TestFiles
import mir.view.ViewState
import zio.test.*

/** 法师、道士技能的实际效果（#1163）：公式按 MagicManager.cs，世界里放出来看结果。 */
object SpellSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private val testMap = MapDef(MapId("T"), "T", "测试", Set.empty, 60, 60)

  private final class Sim(val w: World):
    var view = ViewState()
    var notices = Vector.empty[String]
    /** 收到的特效（图库、起始帧） */
    var effects = Vector.empty[(String, Int)]
    var me: Option[ActorId] = None

    def flush(): Unit =
      val out = w.flush()
      me.foreach { id =>
        val got = out.messages.getOrElse(id, Vector.empty)
        view = view.applyAll(got)
        notices ++= got.collect { case ServerMsg.Notice(t, _) => t; case ServerMsg.Refused(t) => t }
        effects ++= got.collect { case ServerMsg.Effect(e) => (e.lib, e.start) }
      }

    def run(ms: Int): Unit = (0 until ms by 50).foreach { _ =>
      w.tick(w.now + 50)
      flush()
    }

    def cast(p: Player, skill: Int, target: Option[Actor], cell: Cell): Unit =
      w.handle(p.id, GameCmd.Cast(SkillId(skill), target.map(_.id), cell))
      flush()

  private def sim(seed: Long = 1): Sim =
    Sim(World(catalog, testMap, Terrain.open(testMap.width, testMap.height), Rng.seeded(seed), UidSource.counter(1_000_000), Millis(0)))

  /** 放一个高等级、血蓝很多、学会了这些技能（3 级）的角色。 */
  private def caster(s: Sim, job: Job, skills: List[Int], at: Cell = Cell(30, 30), level: Int = 40, charm: Boolean = false): Player =
    val d  = CharacterData.newbie(catalog, "甲", job, Sex.Male, UidSource.counter(5000))
    val id = s.w.join(d.copy(place = Place(s.w.mapId, at), level = level))
    val p  = s.w.player(id).get
    p.skills ++= skills.map(k => LearnedSkill(SkillId(k), 3, 0, None))
    if charm then p.worn(EquipSlot.BraceletL) = charges(77L, "护身符", 1000)
    s.w.vitals.recalc(p)
    p.stats = p.stats.copy(maxHp = 100_000, maxMp = 100_000)
    p.hp = 100_000
    p.mp = 100_000
    s.me = Some(id)
    s.flush()
    p

  /** 按次叠的护身符/药粉（#1213）：数量就是剩余次数 */
  private def charges(uid: Long, name: String, n: Int): ItemInstance =
    ItemInstance(ItemUid(uid), ItemName(name), Durability.PerUse, Durability.PerUse, count = n)

  private def put(s: Sim, name: String, at: Cell): Monster =
    s.w.spawner.spawnMonster(catalog.monster(MonsterName(name)).getOrElse(throw new AssertionError(name)), None, at, 0).get

  /** 关掉自然回血、拿走药（测试治疗量用：血上限 10 万时自然回血一跳上千，血少了还会自动喝药） */
  private def noRegen(p: Player): Unit =
    p.lastRegen = Millis(Long.MaxValue / 2)
    p.bag.indices.foreach(p.bag(_) = None)
    p.belt.indices.foreach(p.belt(_) = None)

  /** 把怪钉住不动（测试用：远远地定住，免得乱跑） */
  private def pin(m: Monster): Unit = m.statuses.put(Status.Seized, Millis(Long.MaxValue / 2))

  /** 钉住的、10 万血打不死的半兽人 */
  private def sturdy(s: Sim, at: Cell): Monster =
    val m = put(s, "半兽人", at)
    pin(m)
    s.w.vitals.setMaxHp(m, 100_000)
    m.hp = 100_000
    m

  /** 左手镯位护身符剩几次 */
  private def charmsLeft(p: Player): Int = p.worn.get(EquipSlot.BraceletL).fold(0)(_.count)

  def spec = suite("技能效果（#1163）")(
    suite("公式")(
      test("诱惑之光：先按收服概率（×倍率）判；没收服再按原版定住 25 秒 / 发狂 / 没效果") {
        val t    = TameTarget(level = 5, maxHp = 20, undead = false)
        val miss = 999_999 // 第一次判收服不中
        assertTrue(
          SpellRules.tame(10, 3, t)(using Rng.sequence(0)) == TameOutcome.Tamed,
          SpellRules.tame(10, 3, t)(using Rng.sequence(miss, 0, 1)) == TameOutcome.Seized(25),
          SpellRules.tame(10, 3, t)(using Rng.sequence(miss, 0, 0, 0, 40, 0)) == TameOutcome.NoEffect,
          SpellRules.tame(10, 3, t)(using Rng.sequence(miss, 0, 0, 1, 7)) == TameOutcome.Crazy(17),
          SpellRules.tame(10, 0, t)(using Rng.sequence(miss, 1)) == TameOutcome.NoEffect
        )
      },
      test("诱惑之光收服概率：原版 30 级 3 级技能对蛤蟆约 5.4%，×10 倍约 54%；血多的怪更难") {
        val toad = SpellRules.tameChance(30, 3, TameTarget(12, 20, undead = false))
        val moth = SpellRules.tameChance(30, 3, TameTarget(32, 220, undead = false))
        assertTrue(math.abs(toad - 42.0 / 65 / 12 * 10) < 1e-9, moth < toad, SpellRules.tameChance(13, 0, TameTarget(12, 20, false)) > 0.05)
      },
      test("诱惑之光：一定不行的情况放之前就说明") {
        def b(t: TameTarget, pets: Int = 0) = SpellRules.tameBlocker(20, t, pets)
        assertTrue(
          b(TameTarget(10, 100, undead = true)).isDefined,
          b(TameTarget(23, 100, undead = false)).isDefined,
          b(TameTarget(22, 100, undead = false)).isEmpty,
          b(TameTarget(10, 100, undead = false), pets = 5).isDefined
        )
      },
      test("火墙持续秒数：GetPower(10) + 魔法随机值/2") {
        val d = catalog.skill(SkillId(22)).get
        assertTrue(
          SpellRules.fireWallSecs(d, 0, Span(0, 0))(using Rng.sequence(0)) == 3 + 3,
          SpellRules.fireWallSecs(d, 3, Span(10, 20))(using Rng.sequence(0)) == 10 + 3 + 5
        )
      },
      test("地狱雷光对非不死系只有十分之一") {
        assertTrue(SpellRules.thunderStorm(55, undead = true) == 55, SpellRules.thunderStorm(55, undead = false) == 5)
      }
    ),
    test("召唤骷髅、神兽同种各最多 3 只（#1176），第 4 只召唤失败并提示") {
      val s = sim()
      val p = caster(s, Job.Taoist, List(17, 30), charm = true)
      def count(n: MonsterName) = p.pets.flatMap(s.w.actor).count {
        case m: Monster => m.defn.name == n
        case _          => false
      }
      (1 to 4).foreach { _ => s.cast(p, 17, None, p.cell); s.run(3000) }
      val skeletons = count(SpellRules.SkeletonPet)
      (1 to 2).foreach { _ => s.cast(p, 30, None, p.cell); s.run(3000) }
      assertTrue(
        skeletons == SpellRules.SummonsEach,
        s.notices.exists(_.contains(s"最多 ${SpellRules.SummonsEach} 只")),
        count(SpellRules.SkeletonPet) == 3,
        count(SpellRules.ShinsuPet) == 2
      )
    },
    test("白虎（#1336）：道士 47 级召唤骷髅召出骷髅，48 级召出白虎；白虎数值、成长、节奏同大神兽，贴身物理攻击，和骷髅共用 3 只名额") {
      val s47 = sim()
      val p47 = caster(s47, Job.Taoist, List(17), level = 47, charm = true)
      s47.cast(p47, 17, None, p47.cell); s47.run(3000)
      val young = p47.pets.flatMap(s47.w.actor).collect { case m: Monster => m.defn.name }.toList

      val s = sim()
      val p = caster(s, Job.Taoist, List(17), level = 47, charm = true)
      (1 to 2).foreach { _ => s.cast(p, 17, None, p.cell); s.run(3000) }
      p.level = 48
      (1 to 2).foreach { _ => s.cast(p, 17, None, p.cell); s.run(3000) }
      val mine   = p.pets.flatMap(s.w.actor).collect { case m: Monster => m }.toList
      val tigers = mine.filter(_.defn.name == SpellRules.WhiteTigerPet)
      val tiger  = tigers.head
      val big    = catalog.monster(SpellRules.ShinsuWarrior).get
      val td     = catalog.monster(SpellRules.WhiteTigerPet).get
      assertTrue(
        young == List(SpellRules.SkeletonPet),
        SpellRules.skeletonSkillPet(47) == SpellRules.SkeletonPet,
        SpellRules.skeletonSkillPet(48) == SpellRules.WhiteTigerPet,
        mine.count(_.defn.name == SpellRules.SkeletonPet) == 2, // 48 级前召的骷髅留着
        tigers.size == 1,                                        // 骷髅 + 白虎一共 3 只
        s.notices.exists(_.contains(s"变异骷髅和白虎一共最多 ${SpellRules.SummonsEach} 只")),
        PetBody.of(td) == PetBody.of(big),                       // 数据表数值照大神兽抄（tools/data/baihu.json StatsFrom）
        td.hit == big.hit && td.speed == big.speed,
        SpellRules.growthOf(SpellRules.WhiteTigerPet) == SpellRules.growthOf(SpellRules.ShinsuPet),
        tiger.summoning.exists(how => tiger.body == how.body(big)),
        tiger.walkInterval == SpellRules.shinsuWalkMs(3),
        tiger.attackInterval == SpellRules.shinsuHitMs(3),
        tiger.behavior.strike == Strike.Melee,
        SpellRules.summonedAs(SpellRules.WhiteTigerPet).contains(SpellRules.SkeletonPet),
        SpellRules.catchUpMs(SpellRules.WhiteTigerPet).isEmpty
      )
    },
    test("召唤骷髅走路按原版 1200 - 等级×250（3 级 450 毫秒/格）；没仗打往主人身边赶时每格 300 毫秒，跟得上跑步（#1275）；出手间隔 3000 - 等级×600（3 级 1200 毫秒，#1276）") {
      val s = sim()
      val p = caster(s, Job.Taoist, List(17), charm = true)
      s.cast(p, 17, None, p.cell); s.run(3000)
      val m = p.pets.flatMap(s.w.actor).collectFirst { case m: Monster => m }.get
      val walk = m.walkInterval
      s.w.grid.teleportActor(p, Cell(p.cell.x + 12, p.cell.y))
      val from = m.cell.chebyshev(p.cell)
      s.run(2400) // 300 毫秒一格约走 8 格；照 450 毫秒一格只能走 5 格多
      val left = m.cell.chebyshev(p.cell)
      assertTrue(
        SpellRules.skeletonWalkMs(0) == 1200,
        walk == SpellRules.skeletonWalkMs(3),
        walk == 450,
        SpellRules.skeletonHitMs(0) == 3000,
        m.attackInterval == SpellRules.skeletonHitMs(3),
        m.attackInterval == 1200,
        SpellRules.catchUpMs(SpellRules.SkeletonPet).contains(300),
        SpellRules.catchUpMs(SpellRules.ShinsuPet).isEmpty,
        from - left >= 7
      )
    },
    test("道士自动召唤（设置道士页）：勾了骷髅和神兽就各补到 3 只；不勾不召") {
      val s = sim()
      val p = caster(s, Job.Taoist, List(17, 30), charm = true)
      def count(n: MonsterName) = p.pets.flatMap(s.w.actor).count {
        case m: Monster => SpellRules.summonedAs(m.defn.name).contains(n)
        case _          => false
      }
      s.run(10_000)
      val before = p.pets.size
      p.settings = p.settings.copy(autoSummonSkeleton = true, autoSummonShinsu = true)
      s.run(30_000)
      assertTrue(
        before == 0,
        count(SpellRules.SkeletonPet) == SpellRules.SummonsEach,
        count(SpellRules.ShinsuPet) == SpellRules.SummonsEach
      )
    },
    test("法师自动魔法盾：勾了、身上没盾就开；不勾不开") {
      val s = sim()
      val p = caster(s, Job.Wizard, List(31))
      s.run(5_000)
      val before = p.statuses.has(StatusKind.Shield)
      p.settings = p.settings.copy(autoMagicShield = true)
      s.run(5_000)
      assertTrue(!before, p.statuses.has(StatusKind.Shield))
    },
    suite("道士辅助开关、自动打怪（#1307）")(
      test("自动治愈术：勾了、血低于 70% 就给自己治（进待回治疗量）；血够或不勾不治") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(2))
        noRegen(p)
        p.hp = 50_000
        s.run(3000)
        val before = p.mending.isDefined || p.hp > 50_000
        p.settings = p.settings.copy(autoHeal = true)
        s.run(1500)
        val healing = p.mending.isDefined || p.hp > 50_000
        p.hp = 90_000
        s.run(1000) // 已放出去、还没落到身上的那一发
        p.mending = None
        val mp = p.mp
        s.run(3000)
        assertTrue(!before, healing, p.mp == mp, p.mending.isEmpty)
      },
      test("自动治愈术也治身边缺血的宝宝") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(2, 17), charm = true)
        s.cast(p, 17, None, p.cell); s.run(3000)
        val pet = p.pets.flatMap(s.w.actor).collectFirst { case m: Monster => m }.get
        pet.hp = 1
        p.settings = p.settings.copy(autoHeal = true)
        s.run(1500)
        assertTrue(pet.mending.isDefined || pet.hp > 1)
      },
      test("自动神圣战甲术、自动幽灵盾：勾了就补上，不勾不放") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(14, 15), charm = true)
        s.run(3000)
        val before = p.statuses.has(StatusKind.ArmorUp) || p.statuses.has(StatusKind.MagicArmorUp)
        p.settings = p.settings.copy(autoHolyArmor = true, autoSoulShield = true)
        s.run(4000)
        assertTrue(!before, p.statuses.has(StatusKind.ArmorUp), p.statuses.has(StatusKind.MagicArmorUp))
      },
      test("自动隐身：勾了就隐身；出手现身后停手 5 秒内不补，过了再补") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(18), charm = true)
        p.settings = p.settings.copy(autoInvisible = true)
        s.run(2000)
        val hidden = p.statuses.has(StatusKind.Invisible)
        Combat.attack(s.w, p, Dir.values.head): Unit
        s.run(3000)
        val waiting = !p.statuses.has(StatusKind.Invisible)
        s.run(3000)
        assertTrue(hidden, waiting, p.statuses.has(StatusKind.Invisible))
      },
      test("自动打怪：勾了就走过去把附近的怪打死；不勾不动；别人正在打的怪不抢") {
        val s = sim()
        val p = caster(s, Job.Warrior, Nil)
        val m = put(s, "鸡", Cell(p.cell.x + 4, p.cell.y))
        pin(m)
        s.run(3000)
        val idle = !m.dead && p.cell == Cell(30, 30)
        p.settings = p.settings.copy(autoHunt = true)
        s.run(10_000)
        val killed = m.dead
        // 别人的怪：归属给另一个在场的玩家
        val other = s.w.join(CharacterData.newbie(catalog, "乙", Job.Warrior, Sex.Male, UidSource.counter(9000)).copy(place = Place(s.w.mapId, Cell(10, 10))))
        val m2    = put(s, "鸡", Cell(p.cell.x + 3, p.cell.y + 3))
        pin(m2)
        s.w.vitals.setMaxHp(m2, 100_000)
        m2.hp = 100_000
        m2.claim = Some(mir.rules.Claim(other, s.w.now + 600_000))
        val hpBefore = m2.hp
        s.run(5000)
        assertTrue(idle, killed, m2.hp == hpBefore, !m2.dead)
      },
      test("自动打怪优先于捡东西：附近的怪都打死了才去捡，哪怕东西就在脚边（#1337）") {
        val s = sim()
        val p = caster(s, Job.Warrior, Nil)
        p.settings = p.settings.copy(autoHunt = true, autoPickup = true, pickupKinds = Set(PickupKind.Gold))
        s.w.dropAt(Cell(29, 30), Loot.Coins(Gold(77)))
        val chicks = List(put(s, "鸡", Cell(33, 30)), put(s, "鸡", Cell(30, 34)))
        chicks.foreach(pin)
        val g0 = p.gold
        // 一直跑到两只都死；期间只要有鸡活着，金币就该还在地上
        var pickedEarly = false
        var t = 0
        while t < 20_000 && chicks.exists(!_.dead) do
          s.run(50)
          t += 50
          if p.gold != g0 then pickedEarly = true
        val allDead = chicks.forall(_.dead)
        s.run(5000)
        assertTrue(allDead, !pickedEarly, p.gold.value >= g0.value + 77)
      },
      test("自动随机：附近没怪就用随机传送卷飞走，飞到有怪就停下打；不开自动打怪不飞（#1350）") {
        val s = sim()
        val p = caster(s, Job.Warrior, Nil)
        noRegen(p)
        (0 until 3).foreach(i => p.bag(i) = Some(ItemInstance(ItemUid(800L + i), ItemName("随机传送卷"), 1, 1)))
        def scrolls = p.bag.flatten.count(_.name == ItemName("随机传送卷"))
        p.settings = p.settings.copy(autoRandom = true)
        s.run(5000)
        val stayed = p.cell == Cell(30, 30) && scrolls == 3
        p.settings = p.settings.copy(autoHunt = true)
        s.run(2000) // 早已静够 3 秒，开了马上飞；落地后 3 秒内不会再飞
        val flew = p.cell != Cell(30, 30) && scrolls == 2
        // 落地处身边放一只打不死的怪：一直打它，不再飞
        val here = p.cell
        val m    = sturdy(s, Cell(if here.x > 30 then here.x - 2 else here.x + 2, here.y))
        s.run(8000)
        assertTrue(stayed, flew, scrolls == 2, p.intent.foe.contains(m.id), p.cell.chebyshev(m.cell) <= 1)
      },
      test("自动随机：只剩卷包就先拆开再飞；什么都没有就提示一次、过一会儿再试（#1350）") {
        val s = sim()
        val p = caster(s, Job.Warrior, Nil)
        noRegen(p)
        p.settings = p.settings.copy(autoHunt = true, autoRandom = true)
        s.run(5000)
        val told = s.notices.count(_.contains("包里没有随机传送卷"))
        s.run(5000)
        val toldOnce = s.notices.count(_.contains("包里没有随机传送卷")) == told
        p.bag(0) = Some(ItemInstance(ItemUid(900L), ItemName("随机传送卷包"), 1, 1))
        p.autoFlyPausedUntil = Millis.zero
        s.run(2000)
        val left = (p.belt.iterator ++ p.bag.iterator).flatten.find(_.name == ItemName("随机传送卷")).map(_.count)
        assertTrue(told == 1, toldOnce, p.cell != Cell(30, 30), left.contains(5))
      }
    ),
    suite("持续施法、自动施毒（#1308）")(
      test("持续施法：开着时手放一次灵魂火符就一直放（一直扣符、怪一直掉血）；关着只放一次") {
        def used(on: Boolean): (Int, Boolean) =
          val s = sim()
          val p = caster(s, Job.Taoist, List(13), charm = true)
          p.settings = p.settings.copy(sustainCast = on)
          val m = sturdy(s, Cell(34, 30))
          s.cast(p, 13, Some(m), m.cell)
          s.run(5000)
          (1000 - charmsLeft(p), m.hp < 100_000)
        val (on, hurtOn)   = used(true)
        val (off, hurtOff) = used(false)
        assertTrue(on >= 4, off == 1, hurtOn, hurtOff)
      },
      test("持续施法：打死就停；走一步就停") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(13), charm = true)
        p.settings = p.settings.copy(sustainCast = true, autoPickup = false)
        p.stats = p.stats.copy(sc = Span(30, 40))
        val chick = put(s, "鸡", Cell(34, 30))
        pin(chick)
        s.cast(p, 13, Some(chick), chick.cell)
        s.run(5000)
        val killedThenIdle = chick.dead && p.intent == Intent.Idle
        val m = sturdy(s, Cell(34, 32))
        s.cast(p, 13, Some(m), m.cell)
        s.run(2000)
        p.busyUntil = s.w.now // 当作施法动作刚做完，走得动
        s.w.handle(p.id, GameCmd.Walk(p.cell, Dir.Left)); s.flush()
        val left = charmsLeft(p)
        s.run(3000)
        assertTrue(killedThenIdle, p.intent == Intent.Idle, charmsLeft(p) == left)
      },
      test("持续施法：护身符用完就停，提示原因") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(13))
        p.worn(EquipSlot.BraceletL) = charges(77L, "护身符", 2)
        p.settings = p.settings.copy(sustainCast = true)
        val m = sturdy(s, Cell(34, 30))
        s.cast(p, 13, Some(m), m.cell)
        s.run(4000)
        assertTrue(p.worn.get(EquipSlot.BraceletL).isEmpty, p.intent == Intent.Idle, s.notices.exists(_.startsWith("持续施法停了")))
      },
      test("自动打怪 + 持续施法：道士站着用灵魂火符把附近的怪打死，不走过去") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(13), charm = true)
        p.stats = p.stats.copy(sc = Span(30, 40))
        p.settings = p.settings.copy(autoHunt = true, sustainCast = true, autoPickup = false)
        val m = put(s, "鸡", Cell(35, 30))
        pin(m)
        s.run(6000)
        assertTrue(m.dead, p.cell == Cell(30, 30), charmsLeft(p) < 1000)
      },
      test("自动施毒：打怪时目标缺哪种毒补哪种，两种都中了不再下；不勾不下") {
        def poisoned(on: Boolean): (List[StatusKind], Int, Int) =
          val s = sim()
          val p = caster(s, Job.Taoist, List(6, 13), charm = true)
          p.settings = p.settings.copy(autoPoison = on, sustainCast = true)
          val slots = p.bag.indices.filter(p.bag(_).isEmpty).take(2)
          p.bag(slots(0)) = Some(charges(81L, "灰色药粉(少量)", 10))
          p.bag(slots(1)) = Some(charges(82L, "黄色药粉(少量)", 10))
          val m = sturdy(s, Cell(34, 30))
          s.cast(p, 13, Some(m), m.cell)
          s.run(8000)
          (StatusKind.poisons.filter(m.statuses.has), 10 - p.bag(slots(0)).fold(0)(_.count), 10 - p.bag(slots(1)).fold(0)(_.count))
        val (kinds, green, red) = poisoned(true)
        val (none, g0, r0)      = poisoned(false)
        assertTrue(kinds == StatusKind.poisons, green == 1, red == 1, none.isEmpty, g0 == 0, r0 == 0)
      },
      test("施毒术挑毒：目标已中绿毒时用黄药粉补红毒（不按红绿交替再下绿毒）") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(6))
        val slots = p.bag.indices.filter(p.bag(_).isEmpty).take(2)
        p.bag(slots(0)) = Some(charges(81L, "灰色药粉(少量)", 10))
        p.bag(slots(1)) = Some(charges(82L, "黄色药粉(少量)", 10))
        p.lastPoison = Some(PoisonKind.Red)
        val m = sturdy(s, Cell(34, 30))
        s.w.conditions.poison(m, PoisonKind.Green, 60_000, 1, p.id)
        s.cast(p, 6, Some(m), m.cell)
        assertTrue(m.statuses.has(StatusKind.RedPoison), p.bag(slots(0)).map(_.count).contains(10), p.bag(slots(1)).map(_.count).contains(9))
      }
    ),
    suite("神兽照原版变身喷火（#1255）")(
      test("小神兽不打架；附近有怪就原地变成神兽1：同一只、主人和等级不变、身板按神兽1 底数重算、血按比例；变了身也算神兽的名额") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(30), charm = true)
        p.stats = p.stats.copy(sc = Span(5, 20))
        s.cast(p, 30, None, p.cell); s.run(3000)
        val m = p.pets.flatMap(s.w.actor).collectFirst { case m: Monster => m }.get
        val small = (m.defn.name, m.behavior.strike, m.walkInterval)
        m.hp = m.maxHp / 2
        val foe = put(s, "蛤蟆", Cell(p.cell.x + 4, p.cell.y))
        pin(foe)
        foe.maxHpNow = 100_000
        foe.hp = 100_000
        s.run(500)
        val big  = catalog.monster(SpellRules.ShinsuWarrior).get
        val want = Summoning(3, 20).body(big)
        val seen = s.view.actors.get(m.id).map(_.look).collect { case l: Look.Monster => l.kind }
        val half = m.hp.toDouble / m.maxHp
        (1 to 3).foreach { _ => s.cast(p, 30, None, p.cell); s.run(1500) }
        val shinsu = p.pets.flatMap(s.w.actor).count {
          case q: Monster => SpellRules.summonedAs(q.defn.name).contains(SpellRules.ShinsuPet)
          case _          => false
        }
        assertTrue(
          small == (SpellRules.ShinsuPet, Strike.Hold, SpellRules.shinsuWalkMs(3)),
          m.defn.name == SpellRules.ShinsuWarrior,
          s.w.actor(m.id).contains(m),
          m.master.contains(p.id),
          m.petLevel == 3,
          m.body == want,
          math.abs(half - 0.5) < 0.03, // 半血变身还是半血（允许这期间自然回一跳）
          m.attackInterval == SpellRules.shinsuHitMs(3),
          seen.contains(SpellRules.ShinsuWarrior),
          shinsu == SpellRules.SummonsEach,
          s.notices.exists(_.contains(s"最多 ${SpellRules.SummonsEach} 只"))
        )
      },
      test("神兽1 朝目标方向喷火：身前两格都挨打、不中毒，旁边的不挨；放 Mon18 喷火特效") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(30), charm = true)
        s.cast(p, 30, None, p.cell); s.run(3000)
        val m = p.pets.flatMap(s.w.actor).collectFirst { case m: Monster => m }.get
        s.w.grid.teleportActor(m, Cell(20, 20))
        def foe(c: Cell) =
          val f = put(s, "蛤蟆", c)
          pin(f)
          f.maxHpNow = 100_000
          f.hp = 100_000
          f
        val near  = foe(Cell(21, 20))
        val far   = foe(Cell(22, 20))
        val aside = foe(Cell(20, 22))
        s.run(3000)
        assertTrue(
          m.defn.name == SpellRules.ShinsuWarrior,
          near.hp < 100_000,
          far.hp < 100_000,
          aside.hp == 100_000,
          !near.statuses.has(StatusKind.GreenPoison) && !far.statuses.has(StatusKind.GreenPoison),
          s.effects.contains(("mon18", 821 + 10 * Dir.Right.code))
        )
      },
      test("神兽1 连续 60 秒没仗打就钻下去变回小神兽，血按比例、身板按神兽底数") {
        val s = sim()
        val p = caster(s, Job.Taoist, List(30), charm = true)
        p.stats = p.stats.copy(sc = Span(5, 20))
        s.cast(p, 30, None, p.cell); s.run(3000)
        val m   = p.pets.flatMap(s.w.actor).collectFirst { case m: Monster => m }.get
        val foe = put(s, "蛤蟆", Cell(p.cell.x + 4, p.cell.y))
        pin(foe)
        s.run(500)
        val grew = m.defn.name
        s.w.vitals.killWithoutReward(foe)
        m.hp = m.maxHp / 2
        s.run(55_000)
        val still = m.defn.name
        val ratio = m.hp.toDouble / m.maxHp
        s.run(8_000)
        val want = Summoning(3, 20).body(catalog.monster(SpellRules.ShinsuPet).get)
        assertTrue(
          grew == SpellRules.ShinsuWarrior,
          still == SpellRules.ShinsuWarrior,
          m.defn.name == SpellRules.ShinsuPet,
          m.body == want,
          ratio < 1.0,
          math.abs(m.hp.toDouble / m.maxHp - ratio) < 0.05 // 血量比例带过去（允许自然回一跳：小神兽血少，一跳约 3%）
        )
      }
    ),
    test("骷髅（#1209、#1258；#1334 用户定不削）：道术上限每点 +1.8%，不封顶，乘血、攻、防、魔防；技能等级只乘血和攻") {
      val md = catalog.monster(SpellRules.SkeletonPet).get
      def body(sc: Int) = SpellRules.summonedBody(md, 3, sc)
      def x(v: Int, k: Double) = math.floor(v * k + 0.5).toInt
      val g   = SpellRules.growthOf(SpellRules.SkeletonPet)
      val lv3 = g.levelBoost(3)
      assertTrue(
        lv3 == 2.05,
        List(g.hp, g.attack, g.guard).forall(s => s.scBoost(0) == 1.0 && s.scBoost(20) == 1.36 && s.scBoost(50) == 1.9 && s.scBoost(-5) == 1.0),
        List(g.hp, g.attack, g.guard).forall(s => s.scPctText == "1.8" && s.baseText.isEmpty),
        body(0) == PetBody(x(md.hp, lv3), Span(x(md.dc.lo, lv3), x(md.dc.hi, lv3)), md.ac, md.mac),
        body(20).hp == x(md.hp, lv3 * 1.36),
        body(20).dc == Span(x(md.dc.lo, lv3 * 1.36), x(md.dc.hi, lv3 * 1.36)),
        body(20).ac == Span(x(md.ac.lo, 1.36), x(md.ac.hi, 1.36)),
        body(20).mac == Span(x(md.mac.lo, 1.36), x(md.mac.hi, 1.36)),
        body(60).hp > body(40).hp // 不封顶
      )
    },
    test("神兽（#1285、#1245、#1334）：技能每级 +30%；道术每点血 +1.6%、攻 +1.2%、防魔防 +0.6%；攻底数 ×0.65、防魔防 ×0.85，两种形态同一份") {
      val g = SpellRules.growthOf(SpellRules.ShinsuPet)
      val big = catalog.monster(SpellRules.ShinsuWarrior).get
      def x(v: Int, k: Double) = math.floor(v * k + 0.5).toInt
      val b = SpellRules.summonedBody(big, 3, 50)
      val hpSc  = 1.8 // 1 + 50×1.6%
      val atkSc = 1.6 // 1 + 50×1.2%
      val defSc = 1.3 // 1 + 50×0.6%
      val lv    = 1.9 // 1 + 3×30%
      assertTrue(
        SpellRules.growthOf(SpellRules.ShinsuWarrior) == g,
        g.hp.scBoost(50) == hpSc,
        g.attack.scBoost(50) == atkSc,
        g.guard.scBoost(50) == defSc,
        g.levelBoost(3) == lv,
        g.guard.scPctText == "0.6",
        g.attack.baseText.contains("0.65"),
        b.hp == x(big.hp, lv * hpSc),
        b.dc == Span(x(big.dc.lo, 0.65 * (lv * atkSc)), x(big.dc.hi, 0.65 * (lv * atkSc))),
        b.ac == Span(x(big.ac.lo, 0.85 * defSc), x(big.ac.hi, 0.85 * defSc)),
        b.mac == Span(x(big.mac.lo, 0.85 * defSc), x(big.mac.hi, 0.85 * defSc)),
        b.ac.hi == 40 // 赤月档道士（道术上限约 50）的大神兽防御 49 → 40
      )
    },
    test("召唤时按主人道术上限定下宝宝身板，之后道术变了也不变（#1209）") {
      val s = sim()
      val p = caster(s, Job.Taoist, List(17), charm = true)
      p.stats = p.stats.copy(sc = Span(5, 20))
      s.cast(p, 17, None, p.cell); s.run(3000)
      val m  = p.pets.flatMap(s.w.actor).collectFirst { case m: Monster => m }.get
      val want = SpellRules.summonedBody(m.defn, 3, 20)
      p.stats = p.stats.copy(sc = Span(0, 0))
      assertTrue(
        m.body == want,
        m.maxHp == want.hp,
        m.ac == want.ac,
        m.mac == want.mac,
        want.ac != m.defn.ac
      )
    },
    test("客户端看到的宝宝带实际攻防（3 级召唤的骷髅攻击比怪物表大，#1232）；野怪不带") {
      val s = sim()
      val p = caster(s, Job.Taoist, List(17), charm = true)
      p.stats = p.stats.copy(sc = Span(5, 20))
      s.cast(p, 17, None, p.cell); s.run(3000)
      val m    = p.pets.flatMap(s.w.actor).collectFirst { case m: Monster => m }.get
      val want = SpellRules.summonedBody(m.defn, 3, 20)
      val wild = put(s, "蛤蟆", Cell(p.cell.x + 6, p.cell.y))
      s.run(100)
      val pet = s.view.actors.get(m.id).map(_.look).collect { case l: Look.Monster => l.pet }.flatten
      assertTrue(
        pet.contains(PetView(p.id, want.dc, want.ac, want.mac)),
        want.dc.hi > m.defn.dc.hi,
        s.view.actors.get(wild.id).map(_.look).exists { case l: Look.Monster => l.pet.isEmpty; case _ => false }
      )
    },
    test("灵魂火符命中炸开用原版 1360 起的帧，不是幽灵盾的 1320（PlayScn.pas mtExploBujauk）") {
      val s = sim()
      val p = caster(s, Job.Taoist, List(13), charm = true)
      val m = put(s, "蛤蟆", Cell(33, 30))
      pin(m)
      m.maxHpNow = 100_000
      m.hp = 100_000
      s.cast(p, 13, Some(m), m.cell)
      s.run(2000)
      assertTrue(s.effects.contains(("magic", 1360)), !s.effects.contains(("magic", 1320)))
    },
    suite("护身符、药粉放包裹里就能用（#1202）")(
      test("只有包裹里有护身符：灵魂火符照样放，扣包裹里那张的次数；左手镯戴着的毒药不动") {
        val s      = sim()
        val p      = caster(s, Job.Taoist, List(13))
        val powder = charges(78L, "黄色药粉(少量)", 10)
        p.worn(EquipSlot.BraceletL) = powder
        val slot = p.bag.indexOf(None)
        p.bag(slot) = Some(charges(77L, "护身符", 10))
        val m = put(s, "蛤蟆", Cell(33, 30))
        pin(m)
        m.maxHpNow = 100_000
        m.hp = 100_000
        s.cast(p, 13, Some(m), m.cell)
        s.run(2000)
        assertTrue(m.hp < 100_000, p.bag(slot).map(_.count).contains(9), p.worn.get(EquipSlot.BraceletL).contains(powder))
      },
      test("左手镯戴着护身符时先用它，包裹里的不动") {
        val s    = sim()
        val p    = caster(s, Job.Taoist, List(13), charm = true)
        val slot = p.bag.indexOf(None)
        val bagged = charges(79L, "护身符", 10)
        p.bag(slot) = Some(bagged)
        val m = put(s, "蛤蟆", Cell(33, 30))
        pin(m)
        s.cast(p, 13, Some(m), m.cell)
        assertTrue(p.worn.get(EquipSlot.BraceletL).map(_.count).contains(999), p.bag(slot).contains(bagged))
      },
      test("只有包裹里有药粉：施毒术按那份的颜色下毒，最后一次用完从包裹里拿掉并提示") {
        val s    = sim()
        val p    = caster(s, Job.Taoist, List(6))
        val slot = p.bag.indexOf(None)
        p.bag(slot) = Some(charges(80L, "黄色药粉(少量)", 1))
        val m = put(s, "蛤蟆", Cell(33, 30))
        pin(m)
        s.cast(p, 6, Some(m), m.cell)
        val red = m.statuses.find { case Status.Poisoned(k, _, _, _) => k }.contains(PoisonKind.Red)
        assertTrue(red, p.bag(slot).isEmpty, s.notices.exists(_.contains("用完了")), s.view.bag(slot).isEmpty)
      },
      test("灰、黄药粉都带着：施毒术红绿交替，怪身上两种毒同时在，各扣一次") {
        val s     = sim()
        val p     = caster(s, Job.Taoist, List(6))
        val slots = p.bag.indices.filter(p.bag(_).isEmpty).take(2)
        p.bag(slots(0)) = Some(charges(81L, "灰色药粉(少量)", 10))
        p.bag(slots(1)) = Some(charges(82L, "黄色药粉(少量)", 10))
        val m = put(s, "蛤蟆", Cell(33, 30))
        pin(m)
        m.maxHpNow = 100_000
        m.hp = 100_000
        def kinds = StatusKind.poisons.filter(m.statuses.has)
        def tint  = s.view.actors.get(m.id).flatMap(a => Affliction.tint(a.afflictions))
        val before = tint
        s.cast(p, 6, Some(m), m.cell)
        val first = kinds
        val green = tint
        s.run(3000)
        s.cast(p, 6, Some(m), m.cell)
        val both = kinds
        val red  = tint
        s.run(3000)
        s.cast(p, 6, Some(m), m.cell)
        assertTrue(
          before.isEmpty,
          first == List(StatusKind.GreenPoison),
          green.contains(BodyTint.Green),
          both == StatusKind.poisons,
          red.contains(BodyTint.Red),
          p.bag(slots(0)).map(_.count).contains(8),
          p.bag(slots(1)).map(_.count).contains(9),
          m.hp < 100_000
        )
      },
      test("包裹和左手镯都没有：放不了，提示缺什么，不扣蓝") {
        val s  = sim()
        val p  = caster(s, Job.Taoist, List(6, 13))
        val m  = put(s, "蛤蟆", Cell(33, 30))
        val mp = p.mp
        s.cast(p, 13, Some(m), m.cell)
        s.run(2000)
        s.cast(p, 6, Some(m), m.cell)
        assertTrue(p.mp == mp, s.notices.exists(_.contains("没有护身符")), s.notices.exists(_.contains("没有灰色或黄色药粉")))
      }
    ),
    test("火墙：5 格火，怪站在上面掉血，火灭了格子也消失，视野副本跟着出现消失") {
      val s = sim()
      val p = caster(s, Job.Wizard, List(22))
      val m = put(s, "蛤蟆", Cell(33, 30))
      pin(m)
      m.maxHpNow = 100_000
      m.hp = 100_000
      s.cast(p, 22, Some(m), m.cell)
      val placed = s.w.activeFields.map(_.cell).toSet
      val seen   = s.view.fields.values.map(_.cell).toSet
      s.run(7000)
      val burnt = m.hp < 100_000
      s.run(60_000)
      assertTrue(
        placed == Set(Cell(33, 29), Cell(32, 30), Cell(33, 30), Cell(34, 30), Cell(33, 31)),
        seen == placed,
        s.view.fields.values.forall(_.kind == FieldKind.Fire),
        burnt,
        s.w.activeFields.isEmpty,
        s.view.fields.isEmpty
      )
    },
    test("诱惑之光：反复放能收服，宝宝认主、看得出是宝宝；定住、发狂、失败都有提示") {
      val s = sim(seed = 11)
      val p = caster(s, Job.Wizard, List(20), level = 30)
      var m = put(s, "蛤蟆", Cell(32, 30))
      var tries = 0
      while m.master.isEmpty && tries < 400 do
        if m.dead then m = put(s, "蛤蟆", Cell(32, 30))
        if m.cell.chebyshev(p.cell) > 6 then s.w.grid.teleportActor(m, Cell(32, 30))
        s.cast(p, 20, Some(m), m.cell)
        s.run(1000)
        tries += 1
      val shown = s.view.actors.get(m.id).map(_.look)
      assertTrue(
        m.master.contains(p.id),
        p.pets.contains(m.id),
        m.spawn.isEmpty,
        shown.exists(_.master.contains(p.id)),
        s.notices.exists(_.contains("成了你的宝宝")),
        s.notices.exists(n => n.contains("定住") || n.contains("没有成功") || n.contains("发狂"))
      )
    },
    test("诱惑之光：动物、不死系、等级太高的直接说明，不扣蓝") {
      val s  = sim()
      val p  = caster(s, Job.Wizard, List(20), level = 8)
      val ck = put(s, "鸡", Cell(31, 30))
      val sc = put(s, "稻草人", Cell(29, 30))
      val mp = p.mp
      s.cast(p, 20, Some(ck), ck.cell)
      s.run(1000)
      s.cast(p, 20, Some(sc), sc.cell)
      assertTrue(p.mp == mp, s.notices.exists(_.contains("动物")), s.notices.exists(_.contains("不死系")))
    },
    test("困魔咒：目标周围比自己低的怪被定住、不动，四周 8 格光圈；挨打就醒") {
      val s = sim()
      val p = caster(s, Job.Taoist, List(16), charm = true)
      val m = put(s, "蛤蟆", Cell(34, 30))
      s.cast(p, 16, Some(m), m.cell)
      val held  = m.statuses.has(StatusKind.Seized) && m.look.asInstanceOf[Look.Monster].mood == Mood.Seized
      val at    = m.cell
      s.run(5000)
      val still = m.cell == at
      val rings = s.w.activeFields.count(_.effect == FieldEffect.HolyCurtain)
      s.w.vitals.damage(m, 1, Some(p.id))
      assertTrue(held, still, rings == 8, !m.statuses.has(StatusKind.Seized), s.view.fields.size == 8)
    },
    test("神圣战甲术、幽灵盾：没选自己人就加在自己身上，防御/魔防上限 + 等级/7+1，到时恢复") {
      val s   = sim()
      val p   = caster(s, Job.Taoist, List(14, 15), charm = true)
      val ac0 = p.stats.ac
      val mc0 = p.stats.mac
      s.cast(p, 15, None, p.cell)
      s.run(1000)
      s.cast(p, 14, None, p.cell)
      val up = p.stats.ac.hi == ac0.hi + 40 / 7 + 1 && p.stats.mac.hi == mc0.hi + 40 / 7 + 1
      val status = s.view.status.acUpUntil.isDefined && s.view.status.macUpUntil.isDefined
      s.run(400_000)
      assertTrue(up, status, p.stats.ac == ac0, p.stats.mac == mc0, s.view.status.acUpUntil.isEmpty)
    },
    test("圣言术：不死系有机会直接打死，不是不死系的不让放") {
      val s  = sim(seed = 5)
      val p  = caster(s, Job.Wizard, List(32), level = 40)
      val hm = put(s, "蛤蟆", Cell(28, 30))
      pin(hm)
      val mp = p.mp
      s.cast(p, 32, Some(hm), hm.cell)
      val refused = p.mp == mp && s.notices.exists(_.contains("只对不死系"))
      val sc = put(s, "稻草人", Cell(32, 30))
      pin(sc)
      (1 to 40).foreach { _ =>
        if !sc.dead then
          s.cast(p, 32, Some(sc), sc.cell)
          s.run(1500)
      }
      assertTrue(refused, sc.dead, p.exp.value > 0)
    },
    test("疾光电影：一条线上的怪都打到") {
      val s = sim()
      val p = caster(s, Job.Wizard, List(10))
      p.stats = p.stats.copy(mc = Span(200, 200))
      val a = put(s, "蛤蟆", Cell(32, 30))
      val b = put(s, "蛤蟆", Cell(36, 30))
      pin(a)
      pin(b)
      s.cast(p, 10, Some(b), b.cell)
      s.run(500)
      assertTrue(a.dead, b.dead)
    },
    test("群体治疗术：中心周围 1 格的自己人（含宝宝）慢慢回血") {
      val s   = sim()
      val p   = caster(s, Job.Taoist, List(29))
      noRegen(p)
      val pet = put(s, "蛤蟆", Cell(31, 30))
      pin(pet)
      pet.master = Some(p.id)
      p.pets += pet.id
      p.hp = 10
      pet.hp = 1
      s.cast(p, 29, None, p.cell)
      val (selfNow, petNow) = (p.hp, pet.hp)
      s.run(3000)
      assertTrue(selfNow == 10, petNow == 1, p.hp > 10, pet.hp > 1)
    },
    test("治愈术：选中 8 格内的宝宝就治宝宝、不治自己；选中的是怪就治自己") {
      val s   = sim()
      val p   = caster(s, Job.Taoist, List(2))
      noRegen(p)
      val pet = put(s, "蛤蟆", Cell(35, 30))
      val foe = put(s, "鸡", Cell(31, 30))
      pin(pet)
      pin(foe)
      pet.master = Some(p.id)
      p.pets += pet.id
      p.hp = 10
      pet.hp = 1
      s.cast(p, 2, Some(pet), pet.cell)
      s.run(3000)
      val (selfAfterPet, petAfter) = (p.hp, pet.hp)
      s.cast(p, 2, Some(foe), foe.cell)
      s.run(3000)
      assertTrue(petAfter > 1, selfAfterPet == 10, p.hp > 10)
    },
    test("治愈术不是一下回满：800 毫秒后落到身上，每 200 毫秒（40 级）回 5 点，回完正好是治疗量") {
      val s     = sim()
      val p     = caster(s, Job.Taoist, List(2))
      noRegen(p)
      p.stats = p.stats.copy(sc = Span(40, 40))
      val total = SpellRules.heal(catalog.skill(SkillId(2)).get, 3, Span(40, 40), 0)(using Rng.seeded(1))
      p.hp = 10
      s.cast(p, 2, None, p.cell)
      val atCast = p.hp
      s.run(750)
      val beforeLand = p.hp
      s.run(300) // 落到身上 + 一拍
      val firstTick = p.hp
      s.run(400) // 再两拍
      val twoMore = p.hp
      s.run(30_000)
      assertTrue(
        total < SpellRules.HealingPoolCap,
        atCast == 10, beforeLand == 10,
        firstTick == 15, twoMore == 25,
        p.hp == 10 + total, p.mending.isEmpty
      )
    },
    test("待回治疗量最多攒 300；血满了没回完的作废；死了也作废") {
      val s = sim()
      val p = caster(s, Job.Taoist, Nil)
      noRegen(p)
      p.hp = 10
      s.w.vitals.mend(p, 250)
      s.w.vitals.mend(p, 100)
      val capped = p.mending.map(_.left)
      p.hp = p.maxHp - 7
      s.run(1000)
      val fullHp = (p.hp, p.mending)
      s.w.vitals.mend(p, 100)
      s.w.vitals.damage(p, p.maxHp * 2, None)
      assertTrue(
        SpellRules.addHealing(100, 100) == 200, SpellRules.addHealing(250, 100) == 300,
        capped.contains(300), fullHp == (p.maxHp, None), p.dead, p.mending.isEmpty
      )
    },
    test("治愈术给满血的：照常扣蓝、施法，但不回血、不涨熟练度；没满血的才涨") {
      val s = sim()
      val p = caster(s, Job.Taoist, Nil)
      noRegen(p)
      p.skills += LearnedSkill(SkillId(2), 0, 0, None)
      s.cast(p, 2, None, p.cell)
      val (mpAfter, skillAfter, pending) = (p.mp, p.skill(SkillId(2)), p.mending)
      s.run(1000)
      p.hp = 10
      s.cast(p, 2, None, p.cell)
      assertTrue(
        mpAfter < 100_000, skillAfter.contains(LearnedSkill(SkillId(2), 0, 0, None)), pending.isEmpty,
        !p.skill(SkillId(2)).contains(LearnedSkill(SkillId(2), 0, 0, None))
      )
    },
    test("红毒：中毒期间挨打伤害按强度加深") {
      val s = sim()
      val p = caster(s, Job.Taoist, Nil)
      val m = put(s, "蛤蟆", Cell(31, 30))
      pin(m)
      m.maxHpNow = 10_000
      m.hp = 10_000
      s.w.vitals.damage(m, 100, Some(p.id))
      val plain = 10_000 - m.hp
      s.w.conditions.poison(m, PoisonKind.Red, 60_000, 25, p.id)
      s.w.vitals.damage(m, 100, Some(p.id))
      assertTrue(plain == 100, 10_000 - plain - m.hp == 125)
    },
    test("已中同种毒再下：强度取较大的，持续时间从现在重新算，下一跳不推迟") {
      val s = sim()
      val p = caster(s, Job.Taoist, Nil)
      val m = put(s, "蛤蟆", Cell(31, 30))
      pin(m)
      s.w.conditions.poison(m, PoisonKind.Green, 60_000, 9, p.id)
      val firstTick = m.statuses.find { case q: Status.Poisoned => q.nextTick }
      s.run(1000)
      s.w.conditions.poison(m, PoisonKind.Green, 10_000, 3, p.id)
      val after = m.statuses.find { case q: Status.Poisoned => q }
      assertTrue(
        after.map(_.power).contains(9),
        after.map(_.nextTick) == firstTick,
        m.statuses.until(StatusKind.GreenPoison).contains(s.w.now + 10_000)
      )
    },
    test("施毒术：道术和技能等级确定持续时间与强度（3 级、道术 10 → 绿毒 60 秒每跳 9 血，红毒 50 秒加深 30%）") {
      val d = catalog.skill(SkillId(6)).get
      given Rng = Rng.seeded(1)
      val sc    = Span(10, 10)
      assertTrue(
        SpellRules.poisonDose(d, 3, sc, PoisonKind.Green) == PoisonDose(60, 9),
        SpellRules.poisonDose(d, 3, sc, PoisonKind.Red) == PoisonDose(50, 30),
        SpellRules.poisonDose(d, 0, Span(0, 0), PoisonKind.Green) == PoisonDose(20, 1)
      )
    },
    test("地狱雷光、爆裂火焰打到范围里的怪") {
      val s = sim()
      val p = caster(s, Job.Wizard, List(23, 24))
      p.stats = p.stats.copy(mc = Span(300, 300))
      val a = put(s, "蛤蟆", Cell(32, 31))
      val b = put(s, "蛤蟆", Cell(36, 30))
      val c = put(s, "蛤蟆", Cell(37, 31))
      List(a, b, c).foreach(pin)
      s.cast(p, 24, None, p.cell)
      s.run(1000)
      s.cast(p, 23, Some(b), b.cell)
      s.run(1000)
      assertTrue(a.dead, b.dead, c.dead)
    },
    suite("野蛮冲撞（#1168）")(
      test("公式：冲 max(2, 等级+1) 格；推得越少撞得越狠；比自己等级低才推得动") {
        assertTrue(
          SpellRules.rushSteps(0) == 2,
          SpellRules.rushSteps(3) == 4,
          SpellRules.rushImpact(3, 4)(using Rng.sequence(0)) == 10,
          SpellRules.rushImpact(3, 0)(using Rng.sequence(49)) == 49 + 50,
          SpellRules.rushCrash(3)(using Rng.sequence(0)) == 15,
          !SpellRules.rushPushes(10, 10, 3)(using Rng.sequence(0)),
          SpellRules.rushPushes(10, 9, 0)(using Rng.sequence(6)),
          !SpellRules.rushPushes(10, 9, 0)(using Rng.sequence(7))
        )
      },
      test("2 级推着怪冲 3 格：怪脸朝自己倒退、挨一下；3 秒内不能再放；加熟练度") {
        val s = sim()
        val p = caster(s, Job.Warrior, List(27))
        val i = p.skills.indexWhere(_.id == SkillId(27))
        p.skills(i) = p.skills(i).copy(level = 2)
        val m = put(s, "蛤蟆", Cell(31, 30))
        pin(m)
        val hp0 = m.hp
        s.cast(p, 27, Some(m), m.cell)
        val pushed = (p.cell, m.cell, m.action.kind, m.action.from, p.action.kind, p.action.from, m.dir)
        val train  = p.skills(i).train
        s.run(1000)
        s.cast(p, 27, Some(m), m.cell)
        assertTrue(
          pushed == (Cell(33, 30), Cell(34, 30), ActKind.Backstep, Some(Cell(31, 30)), ActKind.Rush, Some(Cell(30, 30)), Dir.Left),
          m.hp < hp0 || m.dead,
          train > 0,
          p.cell == Cell(33, 30)
        )
      },
      test("推不动（自己的宝宝）原地撞一下，提示冲撞力不够，不扣血") {
        val s   = sim()
        val p   = caster(s, Job.Warrior, List(27))
        val pet = put(s, "蛤蟆", Cell(31, 30))
        pet.master = Some(p.id)
        val hp0 = pet.hp
        s.cast(p, 27, None, Cell(31, 30))
        assertTrue(p.cell == Cell(30, 30), pet.cell == Cell(31, 30), pet.hp == hp0, p.action.kind == ActKind.Rush, s.notices.contains("冲撞力不够!!!"))
      },
      test("前面没人一直冲；冲劲没用完撞墙，自己挨一下") {
        val flags = new Array[Byte](60 * 60)
        flags(30 * 60 + 33) = 1 // (33,30) 是墙
        val s = Sim(World(catalog, testMap, Terrain.fromFlags(60, 60, flags), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0)))
        val p = caster(s, Job.Warrior, List(27))
        p.stats = p.stats.copy(ac = Span(0, 0))
        s.cast(p, 27, None, Cell(31, 30))
        val walled = (p.cell, p.hp, s.notices.contains("冲撞力不够!!!"))
        s.run(4000)
        s.cast(p, 27, None, Cell(32, 29))
        assertTrue(walled._1 == Cell(32, 30), walled._2 < 100_000, walled._3, p.cell == Cell(32, 26))
      }
    ),
    suite("瞬息移动（#1177）")(
      test("成功后留在当前地图、换到随机位置，宝宝跟过来（不回城）") {
        val s   = sim()
        val p   = caster(s, Job.Wizard, List(21))
        val pet = put(s, "蛤蟆", Cell(31, 30))
        pet.master = Some(p.id)
        p.pets += pet.id
        val start = p.cell
        (1 to 10).foreach { _ =>
          if p.cell == start then
            s.cast(p, 21, None, p.cell)
            s.run(2000)
        }
        assertTrue(
          p.home.map != s.w.mapId,
          s.w.player(p.id).isDefined,
          p.cell != start,
          pet.cell.chebyshev(p.cell) <= 6
        )
      },
      test("带 NORANDOMMOVE 的地图放不了，不扣蓝、不动") {
        val m = testMap.copy(flags = Set(MapFlag.NoRandomMove))
        val s = Sim(World(catalog, m, Terrain.open(60, 60), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0)))
        val p = caster(s, Job.Wizard, List(21))
        s.cast(p, 21, None, p.cell)
        s.run(2000)
        assertTrue(p.cell == Cell(30, 30), p.mp == 100_000, s.notices.contains("这张地图不能瞬息移动"))
      }
    ),
    suite("智能半月（#1273）")(
      test("身边 2 只怪：半月关着也自动出半月，耗蓝、旁边那只也挨刀") {
        val r = smartWide(monsters = List(Cell(31, 30), Cell(30, 31)))
        assertTrue(r.mpSpent == SwordRules.WideHitMp, r.hurt == List(true, true))
      },
      test("身边只有 1 只怪：普通砍，不耗蓝") {
        val r = smartWide(monsters = List(Cell(31, 30)))
        assertTrue(r.mpSpent == 0, r.hurt == List(true))
      },
      test("怪隔了一格不算身边：普通砍") {
        val r = smartWide(monsters = List(Cell(31, 30), Cell(32, 30)))
        assertTrue(r.mpSpent == 0, r.hurt == List(true, false))
      },
      test("蓝不够 3 点：普通砍") {
        val r = smartWide(monsters = List(Cell(31, 30), Cell(30, 31)), mp = SwordRules.WideHitMp - 1)
        assertTrue(r.mpSpent == 0, r.hurt == List(true, false))
      },
      test("没学半月：普通砍") {
        val r = smartWide(monsters = List(Cell(31, 30), Cell(30, 31)), learned = false)
        assertTrue(r.mpSpent == 0, r.hurt == List(true, false))
      },
      test("设置里关掉：普通砍") {
        val r = smartWide(monsters = List(Cell(31, 30), Cell(30, 31)), on = false)
        assertTrue(r.mpSpent == 0, r.hurt == List(true, false))
      },
      test("身边 1 只怪加 1 个玩家：只数怪，不出半月、不砍到人") {
        val r = smartWide(monsters = List(Cell(31, 30)), bystander = Some(Cell(30, 31)))
        assertTrue(r.mpSpent == 0, r.hurt == List(true), r.bystanderHurt == Some(false))
      }
    ),
    suite("刺杀站位（#1264）")(
      test("开着刺杀：走到隔一格就停下出刀") {
        val (at, dmg) = longHitHunt(longHit = true)
        assertTrue(at.chebyshev(Cell(35, 30)) == 2, at.x == 33, dmg > 0)
      },
      test("关着刺杀：照旧走到贴身") {
        val (at, dmg) = longHitHunt(longHit = false)
        assertTrue(at.chebyshev(Cell(35, 30)) == 1, dmg > 0)
      }
    )
  )

  /** 智能半月一刀的结果：花了多少蓝，各只怪（按给的顺序）、旁边的玩家有没有挨刀 */
  private final case class WideResult(mpSpent: Int, hurt: List[Boolean], bystanderHurt: Option[Boolean])

  /** 战士在 (30,30) 朝右（第一只怪在正前方）砍一刀；半月手动开关关着，看智能半月出不出 */
  private def smartWide(
      monsters: List[Cell], mp: Int = 100_000, learned: Boolean = true, on: Boolean = true, bystander: Option[Cell] = None
  ): WideResult =
    val s = sim()
    val p = caster(s, Job.Warrior, if learned then List(25) else Nil)
    p.wideHitOn = false
    p.settings = p.settings.copy(smartWideHit = on)
    p.stats = p.stats.copy(dc = Span(50, 50), hit = 1000)
    p.mp = mp
    val ms = monsters.map { c =>
      val m = put(s, "半兽人", c)
      pin(m)
      m.maxHpNow = 1_000_000
      m.hp = 1_000_000
      m
    }
    val other = bystander.map { c =>
      val d = CharacterData.newbie(catalog, "乙", Job.Warrior, Sex.Male, UidSource.counter(6000))
      val b = s.w.player(s.w.join(d.copy(place = Place(s.w.mapId, c), level = 40))).get
      b.arrivedAt = Millis(-100_000) // 过了进图保护
      b.stats = b.stats.copy(maxHp = 100_000)
      b.hp = 100_000
      b
    }
    Combat.attack(s.w, p, Dir.Right)
    WideResult(mp - p.mp, ms.map(_.hp < 1_000_000), other.map(_.hp < 100_000))

  /** 学了刺杀的战士按住攻击去打 5 格外钉住的怪，跑 3 秒后站在哪、怪挨了多少 */
  private def longHitHunt(longHit: Boolean): (Cell, Int) =
    val s = sim()
    val p = caster(s, Job.Warrior, List(12))
    p.longHitOn = longHit
    p.settings = p.settings.copy(autoPickup = false)
    val m = put(s, "半兽人", Cell(35, 30))
    pin(m)
    m.maxHpNow = 1_000_000
    m.hp = 1_000_000
    p.intent = Intent.Hunt(m.id, once = false)
    s.run(3000)
    (p.cell, 1_000_000 - m.hp)
