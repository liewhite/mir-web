package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.{PetBody, PoisonDose, Rng, SpellRules}
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
    if charm then p.worn(EquipSlot.BraceletL) = ItemInstance(ItemUid(77L), ItemName("护身符"), 100_000, 100_000)
    s.w.vitals.recalc(p)
    p.stats = p.stats.copy(maxHp = 100_000, maxMp = 100_000)
    p.hp = 100_000
    p.mp = 100_000
    s.me = Some(id)
    s.flush()
    p

  private def put(s: Sim, name: String, at: Cell): Monster =
    s.w.spawner.spawnMonster(catalog.monster(MonsterName(name)).getOrElse(throw new AssertionError(name)), None, at, 0).get

  /** 关掉自然回血、拿走药（测试治疗量用：血上限 10 万时自然回血一跳上千，血少了还会自动喝药） */
  private def noRegen(p: Player): Unit =
    p.lastRegen = Millis(Long.MaxValue / 2)
    p.bag.indices.foreach(p.bag(_) = None)
    p.belt.indices.foreach(p.belt(_) = None)

  /** 把怪钉住不动（测试用：远远地定住，免得乱跑） */
  private def pin(m: Monster): Unit = m.statuses.put(Status.Seized, Millis(Long.MaxValue / 2))

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
    test("道术加成宝宝（#1209）：道术上限每点 +2%，封顶 +60%，乘血、攻、防、魔防；技能等级只乘血和攻") {
      val md = catalog.monster(SpellRules.SkeletonPet).get
      def body(sc: Int) = SpellRules.summonedBody(md, 3, sc)
      def x(v: Int, k: Double) = math.floor(v * k + 0.5).toInt
      val lv3 = SpellRules.summonBoost(3)
      assertTrue(
        SpellRules.summonScBoost(0) == 1.0,
        SpellRules.summonScBoost(20) == 1.4,
        SpellRules.summonScBoost(30) == 1.6,
        SpellRules.summonScBoost(40) == 1.6,
        body(0) == PetBody(x(md.hp, lv3), Span(x(md.dc.lo, lv3), x(md.dc.hi, lv3)), md.ac, md.mac),
        body(20).hp == x(md.hp, lv3 * 1.4),
        body(20).dc == Span(x(md.dc.lo, lv3 * 1.4), x(md.dc.hi, lv3 * 1.4)),
        body(20).ac == Span(x(md.ac.lo, 1.4), x(md.ac.hi, 1.4)),
        body(20).mac == Span(x(md.mac.lo, 1.4), x(md.mac.hi, 1.4)),
        body(40) == body(30)
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
        val powder = ItemInstance(ItemUid(78L), ItemName("黄色药粉(少量)"), 1000, 1000)
        p.worn(EquipSlot.BraceletL) = powder
        val slot = p.bag.indexOf(None)
        p.bag(slot) = Some(ItemInstance(ItemUid(77L), ItemName("护身符"), 1000, 1000))
        val m = put(s, "蛤蟆", Cell(33, 30))
        pin(m)
        m.maxHpNow = 100_000
        m.hp = 100_000
        s.cast(p, 13, Some(m), m.cell)
        s.run(2000)
        assertTrue(m.hp < 100_000, p.bag(slot).map(_.dura).contains(900), p.worn.get(EquipSlot.BraceletL).contains(powder))
      },
      test("左手镯戴着护身符时先用它，包裹里的不动") {
        val s    = sim()
        val p    = caster(s, Job.Taoist, List(13), charm = true)
        val slot = p.bag.indexOf(None)
        val bagged = ItemInstance(ItemUid(79L), ItemName("护身符"), 1000, 1000)
        p.bag(slot) = Some(bagged)
        val m = put(s, "蛤蟆", Cell(33, 30))
        pin(m)
        s.cast(p, 13, Some(m), m.cell)
        assertTrue(p.worn.get(EquipSlot.BraceletL).map(_.dura).contains(100_000 - 100), p.bag(slot).contains(bagged))
      },
      test("只有包裹里有药粉：施毒术按那份的颜色下毒，最后一次用完从包裹里拿掉并提示") {
        val s    = sim()
        val p    = caster(s, Job.Taoist, List(6))
        val slot = p.bag.indexOf(None)
        p.bag(slot) = Some(ItemInstance(ItemUid(80L), ItemName("黄色药粉(少量)"), 100, 100))
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
        p.bag(slots(0)) = Some(ItemInstance(ItemUid(81L), ItemName("灰色药粉(少量)"), 1000, 1000))
        p.bag(slots(1)) = Some(ItemInstance(ItemUid(82L), ItemName("黄色药粉(少量)"), 1000, 1000))
        val m = put(s, "蛤蟆", Cell(33, 30))
        pin(m)
        m.maxHpNow = 100_000
        m.hp = 100_000
        def kinds = StatusKind.poisons.filter(m.statuses.has)
        def tint  = s.view.actors.get(m.id).flatMap(_.tint)
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
          p.bag(slots(0)).map(_.dura).contains(800),
          p.bag(slots(1)).map(_.dura).contains(900),
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
        shown.exists { case Look.Monster(_, _, Some(owner), _) => owner == p.id; case _ => false },
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
      val total = SpellRules.heal(catalog.skill(SkillId(2)).get, 3, Span(40, 40))(using Rng.seeded(1))
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
    )
  )
