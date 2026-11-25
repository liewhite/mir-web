package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.{Pk, PkStanding, Rng, SafeZone, Skill}
import mir.terrain.Terrain
import mir.testkit.TestFiles
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** PK（#1225）：安全区、灰名、PK 值涨落、红名掉东西、守卫砍红名、宝宝护主、范围技能误伤、同队互不伤害。 */
object PkSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  /** 不在数据表里的空地（没有安全区、刷怪、守卫） */
  private val open  = MapDef(MapId("T"), "T", "测试", Set.empty, 60, 60)
  /** 竞技地图（和竞技场同编号；别的带 FIGHT 的室内图整张算安全区） */
  private val arena = MapDef(MapId("0137"), "0137", "竞技测试", Set(MapFlag.Fight), 60, 60)
  /** 城里的店（数据表带 FIGHT） */
  private val shop  = MapDef(MapId("0108"), "0108", "药店测试", Set(MapFlag.Fight), 60, 60)
  /** 和比奇省同编号：出生点 (330, 266) 横竖 10 格内是安全区 */
  private val bichi = MapDef(MapId("0"), "0", "比奇测试", Set.empty, 700, 700)

  private final class Sim(val w: World):
    /** 推进 ms 毫秒，返回期间每人收到的提示 */
    def run(ms: Long): Map[ActorId, Vector[String]] =
      (0L until ms by 50L).foldLeft(Map.empty[ActorId, Vector[String]]) { (acc, _) =>
        w.tick(w.now + 50)
        merge(acc, notices())
      }

    def notices(): Map[ActorId, Vector[String]] =
      w.flush().messages.map((id, ms) => id -> ms.collect { case ServerMsg.Notice(t, _) => t })

    private def merge(a: Map[ActorId, Vector[String]], b: Map[ActorId, Vector[String]]) =
      (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, Vector.empty) ++ b.getOrElse(k, Vector.empty))).toMap

  private def sim(map: MapDef = open, company: Company = Company.Alone): Sim =
    Sim(World(catalog, map, Terrain.open(map.width, map.height), Rng.seeded(7), UidSource.counter(1_000_000), Millis(0), company))

  /** 只有一队的组队 */
  private def party(names: String*): Company = n => if names.contains(n) then names.toVector else Vector.empty

  private def join(w: World, name: String, at: Cell): Player =
    val d  = CharacterData.newbie(catalog, name, Job.Warrior, Sex.Male, UidSource.counter(name.hashCode.abs.toLong * 1000))
    val p  = w.player(w.join(d.copy(place = Place(w.mapId, at)))).get
    p.stats = p.stats.copy(maxHp = 100_000)
    p.hp = 100_000
    p.arrivedAt = Millis(-Pk.ArrivalGraceMs) // 过了进图保护（另有一项专测它）
    p

  private def put(w: World, name: String, at: Cell): Monster =
    w.spawner.spawnMonster(catalog.monster(MonsterName(name)).getOrElse(throw new AssertionError(name)), None, at, 0).get

  /** 必中、不扣防的一下 */
  private def hit(w: World, by: Actor, t: Actor, n: Int = 10): StrikeResult = w.vitals.strike(Some(by), t, Blow(Defence.Pierce, n))

  private def kill(w: World, by: Actor, t: Player): Unit =
    t.hp = 1
    hit(w, by, t): Unit

  private def standing(p: Player): PkStanding = p.look match
    case h: Look.Human => h.pk
    case _             => throw new AssertionError("不是玩家")

  def spec = suite("PK（#1225）")(
    test("安全区（出生点横竖 10 格）里打不了人、也挨不了人打；两人都在区外才能打，打不到自己的宝宝") {
      val s  = sim(bichi)
      val a  = join(s.w, "甲", Cell(330, 270))
      val b  = join(s.w, "乙", Cell(331, 270))
      val inside = !s.w.canAttack(a, b) && !s.w.harms(a, b) && hit(s.w, a, b) == StrikeResult.Ignored
      s.w.grid.teleportActor(a, Cell(340, 266)) // 区内边上
      s.w.grid.teleportActor(b, Cell(341, 266)) // 区外
      val edge = !s.w.canAttack(a, b) && !s.w.canAttack(b, a)
      s.w.grid.teleportActor(a, Cell(342, 266))
      val pet = put(s.w, "蛤蟆", Cell(343, 266))
      pet.master = Some(a.id)
      a.pets += pet.id
      assertTrue(
        SafeZone.covers(bichi, Cell(340, 276)), !SafeZone.covers(bichi, Cell(341, 266)),
        inside, edge,
        s.w.canAttack(a, b), s.w.harms(a, b), !s.w.isEnemy(a, b), hit(s.w, a, b) == StrikeResult.Wounded(10),
        !s.w.harms(a, pet), s.w.harms(b, pet)
      )
    },
    test("先动手打清白的人名字变灰 60 秒；被打的人还手不变灰；旁人看得到名字颜色") {
      val s = sim()
      val a = join(s.w, "甲", Cell(30, 30))
      val b = join(s.w, "乙", Cell(31, 30))
      s.run(100)
      hit(s.w, a, b)
      val seen = s.w.flush().messages.getOrElse(b.id, Vector.empty).collect { case ServerMsg.LookChanged(id, h: Look.Human) if id == a.id => h.pk }
      hit(s.w, b, a)
      val grayA = standing(a) == PkStanding.Brawler
      val cleanB = standing(b) == PkStanding.Clean
      s.run(Pk.FlagMs + 100)
      assertTrue(seen == Vector(PkStanding.Brawler), grayA, cleanB, standing(a) == PkStanding.Clean)
    },
    test("杀清白的人 PK 值 +100（黄名）、再杀一个红名；杀先动手的灰名是正当防卫、杀红名的都不加") {
      val s  = sim()
      val a  = join(s.w, "甲", Cell(30, 30))
      // 灰名先动手，被杀算正当防卫，还手的人也不变灰
      val v3 = join(s.w, "丁", Cell(30, 31))
      hit(s.w, v3, a)
      kill(s.w, a, v3)
      val n3     = s.notices()
      val lawful = a.pkPoints == 0 && !a.brawler
      val v1 = join(s.w, "乙", Cell(31, 30))
      kill(s.w, a, v1)
      val n1     = s.notices()
      val after1 = a.pkPoints
      val v2 = join(s.w, "丙", Cell(29, 30))
      kill(s.w, a, v2)
      val red = standing(a) == PkStanding.Red && a.pkPoints == 200
      // 红名被杀不算
      val v4 = join(s.w, "戊", Cell(30, 29))
      v4.pkPoints = 250
      kill(s.w, a, v4)
      assertTrue(
        v1.dead, after1 == 100, n1.getOrElse(a.id, Vector.empty).exists(_.contains("谋杀")),
        n1.getOrElse(v1.id, Vector.empty).exists(_.contains("甲")),
        red, v3.dead, lawful, n3.getOrElse(a.id, Vector.empty).exists(_.contains("正当")), v4.dead, a.pkPoints == 200
      )
    },
    test("宝宝打人算主人的：主人正打着谁、谁打了主人，宝宝就打谁；宝宝杀的 PK 值记在主人头上，宝宝打人不让主人变灰") {
      val s   = sim()
      val a   = join(s.w, "甲", Cell(30, 30))
      val b   = join(s.w, "乙", Cell(34, 30))
      val pet = put(s.w, "蛤蟆", Cell(31, 30))
      pet.master = Some(a.id)
      a.pets += pet.id
      val calm = !s.w.isEnemy(pet, b)
      hit(s.w, b, a)
      val defends = s.w.isEnemy(pet, b)
      b.pkPoints = 0
      b.statuses.remove(StatusKind.Brawler) // 乙不算灰名，这样宝宝杀他算谋杀
      kill(s.w, pet, b)
      assertTrue(calm, defends, b.dead, a.pkPoints == Pk.KillPoints, !a.brawler)
    },
    test("PK 值在线每 2 分钟降 1；存档带着 PK 值，旧存档没有这个字段算 0") {
      val s = sim()
      val a = join(s.w, "甲", Cell(30, 30))
      a.pkPoints = 201
      s.run(Pk.DecayMs + 100)
      val decayed = a.pkPoints == 200
      val saved   = s.w.leave(a.id).get
      val json    = saved.toJson.fromJson[Json].toOption.get
      val legacy  = json match
        case Json.Obj(fields) => Json.Obj(fields.filterNot(_._1 == "pkPoints")).toJson.fromJson[CharacterData]
        case _                => Left("不是对象")
      assertTrue(decayed, saved.pkPoints == 200, legacy.map(_.pkPoints) == Right(0))
    },
    test("红名死了包裹和快捷栏全掉在地上；不是红名死了什么都不掉") {
      val s = sim()
      val a = join(s.w, "甲", Cell(30, 30))
      val b = join(s.w, "乙", Cell(40, 40))
      s.w.newItem(ItemName("金创药(中量)")).foreach(it => a.bag(3) = Some(it))
      val carried = (a.bag ++ a.belt).flatten.map(_.uid).toSet
      a.pkPoints = 200
      s.w.vitals.damage(a, 1_000_000, None)
      val dropped = s.w.groundItems.collect { case GroundItem(_, _, Loot.Item(it), _, _) => it.uid }.toSet
      val bBefore = (b.bag ++ b.belt).flatten.toList
      s.w.vitals.damage(b, 1_000_000, None)
      assertTrue(
        carried.size >= 3, a.dead, (a.bag ++ a.belt).forall(_.isEmpty), carried.subsetOf(dropped),
        b.dead, (b.bag ++ b.belt).flatten.toList == bBefore, b.worn.size == 2,
        Pk.deathDrop(199).isEmpty, Pk.deathDrop(200).map(_.wornOdds).contains(30), Pk.deathDrop(300).map(_.wornOdds).contains(15)
      )
    },
    test("守卫见了红名就砍，黄名不砍") {
      val s      = sim()
      val red    = join(s.w, "甲", Cell(33, 30))
      val yellow = join(s.w, "乙", Cell(30, 33))
      red.pkPoints = 200
      yellow.pkPoints = 150
      val g = put(s.w, "卫士", Cell(30, 30))
      s.run(5000)
      assertTrue(s.w.isEnemy(g, red), !s.w.isEnemy(g, yellow), red.hp < 100_000, yellow.hp == 100_000)
    },
    test("半月弯刀打怪时把旁边不同队的玩家也砍到（不分攻击模式）") {
      val s = sim()
      val a = join(s.w, "甲", Cell(30, 30))
      val b = join(s.w, "乙", Cell(31, 31))
      val m = put(s.w, "鸡", Cell(31, 30))
      a.skills += LearnedSkill(Skill.WideHit.id, 3, 0, None)
      a.wideHitOn = true
      a.mp = 1000
      a.stats = a.stats.copy(dc = mir.data.Span(50, 50), hit = 200)
      s.w.vitals.setMaxHp(m, 100_000) // 鸡不死：打死了会升级、重算属性，把上面改的准确冲掉
      Combat.attack(s.w, a, Dir.Right)
      assertTrue(m.hp < m.maxHp, b.hp < 100_000, a.brawler)
    },
    test("同队互不伤害（#1274）：队友和队友的宝宝打不着、半月不误伤、宝宝不互咬、不变灰；不同队的照打") {
      val s    = sim(company = party("甲", "乙"))
      val a    = join(s.w, "甲", Cell(30, 30))
      val b    = join(s.w, "乙", Cell(31, 31))
      val c    = join(s.w, "丙", Cell(40, 40)) // 离得远，半月砍不到
      val petA = put(s.w, "蛤蟆", Cell(29, 31))
      petA.master = Some(a.id)
      a.pets += petA.id
      val petB = put(s.w, "蛤蟆", Cell(30, 31))
      petB.master = Some(b.id)
      b.pets += petB.id
      val m = put(s.w, "鸡", Cell(31, 30))
      s.w.vitals.setMaxHp(m, 100_000)
      val rules = !s.w.canAttack(a, b) && !s.w.harms(a, b) && !s.w.canAttack(a, petB) && !s.w.harms(petA, b) &&
        !s.w.isEnemy(petA, petB) && s.w.allied(petA, b) && s.w.canAttack(a, c) && !s.w.allied(a, c)
      val strikes = hit(s.w, a, b) == StrikeResult.Ignored && hit(s.w, petA, petB) == StrikeResult.Ignored
      a.skills += LearnedSkill(Skill.WideHit.id, 3, 0, None)
      a.wideHitOn = true
      a.mp = 1000
      a.stats = a.stats.copy(dc = mir.data.Span(50, 50), hit = 200)
      Combat.attack(s.w, a, Dir.Right)
      val spared = b.hp == 100_000 && petB.hp == petB.maxHp && m.hp < m.maxHp && !a.brawler
      // 队友打不着，宝宝护主也不会去咬队友；不同队的丙打了甲，宝宝照咬丙
      hit(s.w, c, a)
      assertTrue(rules, strikes, spared, s.w.isEnemy(petA, c), !s.w.isEnemy(petA, b))
    },
    test("刚进图、传送的 3 秒内不能打人也不挨人打") {
      val s = sim()
      val a = join(s.w, "甲", Cell(30, 30))
      val b = join(s.w, "乙", Cell(31, 30))
      s.run(1000)
      b.arrivedAt = s.w.now
      val fresh = !s.w.canAttack(a, b) && !s.w.canAttack(b, a)
      s.run(Pk.ArrivalGraceMs)
      assertTrue(fresh, s.w.canAttack(a, b), s.w.canAttack(b, a))
    },
    test("被别的玩家打不会自动还手（自动还手只打怪）") {
      val s = sim()
      val a = join(s.w, "甲", Cell(30, 30))
      val b = join(s.w, "乙", Cell(31, 30))
      hit(s.w, b, a)
      s.run(1000)
      assertTrue(a.settings.autoFightBack, a.intent == Intent.Idle, b.hp == 100_000)
    },
    test("城里的店铺房屋（带 FIGHT、不是竞技场）整张算安全区（#1230）；数据表里只有竞技场还能打") {
      val s = sim(shop)
      val a = join(s.w, "甲", Cell(30, 30))
      val b = join(s.w, "乙", Cell(31, 30))
      s.run(Pk.ArrivalGraceMs + 100)
      val fightMaps = catalog.data.maps.filter(_.has(MapFlag.Fight))
      assertTrue(
        !s.w.canAttack(a, b), hit(s.w, a, b) == StrikeResult.Ignored, b.hp == b.maxHp,
        fightMaps.size == 78, fightMaps.filterNot(SafeZone.wholeMap).map(_.id) == List(MapId("0137")),
        !catalog.data.maps.filterNot(_.has(MapFlag.Fight)).exists(SafeZone.wholeMap)
      )
    },
    test("竞技地图里打人杀人都不算：不变灰、不涨 PK 值，红名死了也不掉东西") {
      val s = sim(arena)
      val a = join(s.w, "甲", Cell(30, 30))
      val b = join(s.w, "乙", Cell(31, 30))
      val c = join(s.w, "丙", Cell(29, 30))
      c.pkPoints = 300
      hit(s.w, a, b)
      kill(s.w, a, b)
      s.w.vitals.damage(c, 1_000_000, None)
      assertTrue(b.dead, !a.brawler, a.pkPoints == 0, c.dead, (c.bag ++ c.belt).flatten.nonEmpty)
    }
  )
