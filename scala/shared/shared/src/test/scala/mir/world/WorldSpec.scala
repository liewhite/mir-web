package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.{Pace, Rates, Rng}
import mir.terrain.Terrain
import mir.testkit.TestFiles
import mir.view.ViewState
import zio.test.*

/**
 * 世界的模拟测试：用真实 game.json，在全可走的空地上放玩家和怪，推进时间看结果。
 * 怪物行为 7 项 + 传送菜单照 TS 版 game/test/monster_ai.test.ts；多人、视野、非法指令是新加的。
 */
object WorldSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  /** 一个不在数据表里的测试图（没有刷怪、守卫、NPC）。 */
  private val testMap = MapDef(MapId("T"), "T", "测试", Set.empty, 60, 60)

  private final class Sim(val w: World):
    val views = scala.collection.mutable.Map.empty[ActorId, ViewState]
    var departures = Vector.empty[Departure]

    /** 收一次消息：套到各自的视野副本上，返回每人收到的。 */
    def flush(): Map[ActorId, Vector[ServerMsg]] =
      val out = w.flush()
      out.messages.foreach((id, ms) => views(id) = views.getOrElse(id, ViewState()).applyAll(ms))
      departures ++= out.departures
      out.messages

    /** 推进 ms 毫秒（每 50 毫秒一步），返回期间 who 挨打的总伤害（看消息，不看血：会回血）。 */
    def run(ms: Int, who: Option[Player] = None): Int =
      (0 until ms by 50).map { _ =>
        w.tick(w.now + 50)
        val got = flush()
        who.fold(0)(p => got.getOrElse(p.id, Vector.empty).collect { case ServerMsg.Damage(t, n, _) if t == p.id => n }.sum)
      }.sum

    def cmd(p: Player, c: GameCmd): Vector[ServerMsg] =
      w.handle(p.id, c)
      flush().getOrElse(p.id, Vector.empty)

    /** 视野副本和世界此刻对得上（刚 flush 过时调用）。 */
    def consistent(p: Player): Boolean =
      val (as, gs) = w.visibleTo(p)
      val v        = views.getOrElse(p.id, ViewState())
      val sv       = w.visibility.selfView(p)
      v.me.contains(p.id) &&
      v.actors == as.map(a => a.id -> w.viewOf(a)).toMap &&
      v.ground == gs.map(g => g.id -> g.view).toMap &&
      v.stats.contains(sv.stats) && v.bag == sv.bag && v.belt == sv.belt && v.worn == sv.worn &&
      v.storage == sv.storage && v.skills == sv.skills && v.sword == sv.sword && v.settings == sv.settings

  private def world(map: MapDef = testMap, seed: Long = 1): World =
    World(catalog, map, Terrain.open(map.width, map.height), Rng.seeded(seed), UidSource.counter(1_000_000), Millis(0))

  private def join(w: World, name: String, at: Cell, job: Job = Job.Warrior, tough: Boolean = true): Player =
    val d  = CharacterData.newbie(catalog, name, job, Sex.Male, UidSource.counter(name.hashCode.abs.toLong * 1000))
    val id = w.join(d.copy(place = Place(w.mapId, at)))
    val p  = w.player(id).get
    if tough then
      p.stats = p.stats.copy(maxHp = 100_000)
      p.hp = 100_000
    p

  private def put(w: World, name: String, at: Cell): Monster =
    w.spawner.spawnMonster(catalog.monster(MonsterName(name)).getOrElse(throw new AssertionError(name)), None, at, 0).get

  private def sim(map: MapDef = testMap, seed: Long = 1): Sim = Sim(world(map, seed))

  def spec = suite("世界")(
    suite("怪物行为（照 TS 版 7 项）")(
      test("食人花埋在地下，人走近才钻出来，打不到埋着的") {
        val s = sim()
        val p = join(s.w, "甲", Cell(30, 30))
        val m = put(s.w, "食人花", Cell(40, 30))
        val buried0 = m.cover == Cover.Buried && !s.w.isEnemy(p, m)
        s.run(3000)
        val buried1 = m.cover == Cover.Buried
        s.w.grid.teleportActor(p, Cell(38, 30))
        s.run(3000)
        assertTrue(buried0, buried1, m.cover == Cover.Open, s.w.isEnemy(p, m))
      },
      test("祖玛雕像是石像，2 格内醒来并叫醒附近的") {
        val s = sim()
        val p = join(s.w, "甲", Cell(30, 30))
        val a = put(s.w, "祖玛雕像", Cell(40, 30))
        val b = put(s.w, "祖玛雕像", Cell(45, 30))
        val stone = a.cover == Cover.Stone
        s.w.grid.teleportActor(p, Cell(38, 30))
        s.run(3000)
        assertTrue(stone, a.cover == Cover.Open, b.cover == Cover.Open)
      },
      test("洞蛆贴身喷气会打人") {
        val s = sim()
        val p = join(s.w, "甲", Cell(30, 30))
        put(s.w, "洞蛆", Cell(31, 30))
        assertTrue(s.run(20_000, Some(p)) > 0)
      },
      test("僵尸电光隔几格也能打到") {
        val s = sim()
        val p = join(s.w, "甲", Cell(30, 30))
        val m = put(s.w, "僵尸1", Cell(34, 30))
        m.dc = Span(200, 200)
        assertTrue(s.run(10_000, Some(p)) > 0)
      },
      test("角蝇巢不动，见人放蝙蝠") {
        val s  = sim()
        join(s.w, "甲", Cell(30, 30))
        val q  = put(s.w, "角蝇", Cell(34, 30))
        val at = q.cell
        s.run(15_000)
        assertTrue(q.cell == at, q.minions.nonEmpty)
      },
      test("爆裂蜘蛛贴身自爆，自己死、不给经验") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(30, 30))
        val sp  = put(s.w, "爆裂蜘蛛", Cell(31, 30))
        val exp = p.exp
        s.run(10_000)
        assertTrue(sp.dead, p.exp == exp)
      },
      test("魔龙巨蛾隔几格射毒刺：不走近也打得到，有时中绿毒（#1313）") {
        val s  = sim()
        val p  = join(s.w, "甲", Cell(30, 30))
        val m  = put(s.w, "魔龙巨蛾", Cell(35, 30))
        val at = m.cell
        var poisoned = false
        val hurt = (1 to 60).map { _ =>
          val n = s.run(500, Some(p))
          poisoned ||= p.statuses.has(StatusKind.GreenPoison)
          n
        }.sum
        assertTrue(hurt > 0, poisoned, m.cell == at)
      },
      test("魔龙教主一下打掉两三百血（#1313，用户 11-25 定攻 250~350）") {
        val s = sim()
        val p = join(s.w, "甲", Cell(30, 30))
        put(s.w, "魔龙教主", Cell(31, 30))
        s.run(2500)
        val hits = (0 until 300).flatMap { _ =>
          s.w.tick(s.w.now + 50)
          s.flush().getOrElse(p.id, Vector.empty).collect { case ServerMsg.Damage(t, n, _) if t == p.id && n > 0 => n }
        }
        assertTrue(hits.nonEmpty, hits.forall(n => n >= 150 && n <= 350), hits.sum.toDouble / hits.size > 200)
      },
      test("暗之魔龙教主攻击是魔龙教主的 1.5 倍（#1329，用户 11-26 定）") {
        val s = sim()
        val p = join(s.w, "甲", Cell(30, 30))
        put(s.w, "暗之魔龙教主", Cell(31, 30))
        s.run(2500)
        val hits = (0 until 300).flatMap { _ =>
          s.w.tick(s.w.now + 50)
          s.flush().getOrElse(p.id, Vector.empty).collect { case ServerMsg.Damage(t, n, _) if t == p.id && n > 0 => n }
        }
        assertTrue(hits.nonEmpty, hits.forall(n => n >= 225 && n <= 525), hits.sum.toDouble / hits.size > 300)
      },
      test("祖玛教主掉血会召唤祖玛怪") {
        val s = sim()
        join(s.w, "甲", Cell(30, 30))
        val k = put(s.w, "祖玛教主", Cell(31, 30))
        s.run(3000)
        val woke = k.cover == Cover.Open
        k.hp = k.maxHp / 2
        s.run(3000)
        assertTrue(woke, k.minions.nonEmpty)
      },
      test("会复活的僵尸：打死后原地爬起来，血上限和经验减半；次数用完就不再起来") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(30, 30))
        val z   = put(s.w, "僵尸3", Cell(33, 30))
        val max = z.maxHp
        z.extras = Vector(ExtraState.Revive(left = 1, at = None, times = 0))
        s.w.vitals.strike(Some(p), z, Blow(Defence.Pierce, 100_000)): Unit
        val died = z.isDead
        s.run(30_000)
        val revived = !z.isDead && z.maxHp == max / 2 && z.expScale == 0.5
        s.w.vitals.strike(Some(p), z, Blow(Defence.Pierce, 100_000)): Unit
        s.run(30_000)
        assertTrue(died, revived, z.isDead)
      },
      test("沃玛教主血少了先蓄力（几乎不出手），30 秒后检查转为狂暴（攻速、移速变快）") {
        val s = sim()
        join(s.w, "甲", Cell(30, 30))
        val k = put(s.w, "沃玛教主", Cell(40, 40))
        k.hp = k.maxHp * 3 / 10 // 狂暴档位 7 - 血/(上限/7) = 5
        k.lastRegen = Millis(Long.MaxValue / 2) // 不让它自然回血（#1214）换档，只看狂暴
        s.run(30_500)
        val ready = (k.extras, k.attackInterval) match
          case (Vector(ExtraState.CowKing(_, Rage.Ready, _, _)), 10_000) => true
          case _                                                       => false
        s.run(30_000)
        val crazy = (k.extras, k.attackInterval, k.walkInterval) match
          case (Vector(ExtraState.CowKing(_, Rage.Crazy, _, _)), 500, 400) => true
          case _                                                         => false
        assertTrue(ready, crazy)
      },
      test("守卫平时不和玩家互打、不打鸡鹿羊，隔几格也一刀砍死野怪（#1205）") {
        val s    = sim()
        val p    = join(s.w, "甲", Cell(33, 30))
        val g    = put(s.w, "卫士", Cell(30, 30))
        val deer = put(s.w, "鹿", Cell(30, 34))
        val bug  = put(s.w, "洞蛆", Cell(26, 30))
        val hurt = s.run(10_000, Some(p))
        assertTrue(
          g.behavior.strike == Strike.Warp,
          hurt == 0,
          bug.dead,
          !deer.dead,
          !s.w.isEnemy(p, g),
          !s.w.isEnemy(g, p),
          s.w.canAttack(p, g)
        )
      },
      test("玩家选中守卫打它，守卫还手（走远几格照样砍到）；30 秒没再打就不记仇（#1205）") {
        val s  = sim()
        val p  = join(s.w, "甲", Cell(31, 30))
        val g  = put(s.w, "带刀护卫", Cell(30, 30))
        s.cmd(p, GameCmd.Attack(Dir.Left, Some(g.id)))
        val angry = s.w.isEnemy(g, p)
        p.intent = Intent.Idle // 只打这一下（不管「按住攻击」设置）
        s.w.grid.teleportActor(p, Cell(35, 30))
        val hit = s.run(5000, Some(p))
        s.run(Grudge.Ms.toInt)
        val after = s.run(10_000, Some(p))
        assertTrue(angry, hit > 0, after == 0, !s.w.isEnemy(g, p), g.cell == Cell(30, 30))
      },
      test("卫士照原版主动砍宝宝，主人照样不挨打（#1216）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(33, 30))
        val g   = put(s.w, "卫士", Cell(30, 30))
        val pet = put(s.w, "蛤蟆", Cell(32, 30))
        pet.master = Some(p.id)
        p.pets += pet.id
        val hurt = s.run(10_000, Some(p))
        assertTrue(pet.dead, hurt == 0, !s.w.isEnemy(g, p))
      },
      test("卫士不主动打 Race 80 以下的宝宝：诱惑来的鸡（#1216，原版 SuperGuard 只认 Race ≥ 80；野狼见下一项）") {
        val s     = sim()
        val p     = join(s.w, "甲", Cell(40, 40))
        put(s.w, "卫士", Cell(30, 30))
        val hen   = put(s.w, "鸡", Cell(32, 30))
        hen.master = Some(p.id)
        p.pets += hen.id
        hen.statuses.put(Status.Seized, Millis(Long.MaxValue / 2)) // 鸡站着不走回主人身边
        s.run(10_000)
        assertTrue(hen.hp == hen.maxHp)
      },
      test("野狼和卫士相安无事：野怪不主动打守卫，挨了守卫的打也不还手；守卫仍把野怪当敌人（#1217，原版 IsAttackTarget 野怪只认玩家和宝宝）") {
        val s    = sim()
        val g    = put(s.w, "卫士", Cell(30, 30))
        val wolf = put(s.w, "狼", Cell(31, 30))
        s.run(5000) // 卫士不找 Race 80 以下的狼（#1216），狼也不找卫士：两边相安无事
        val ignored = g.hp == g.maxHp && wolf.hp == wolf.maxHp && !wolf.target.contains(g.id)
        g.target = Some(wolf.id)
        s.w.vitals.strike(Some(g), wolf, Blow(Defence.Pierce, 1)): Unit
        assertTrue(ignored, !wolf.target.contains(g.id), !s.w.isEnemy(wolf, g), s.w.isEnemy(g, wolf))
      },
      test("弓箭守卫不打宝宝（#1216，原版 ArcherGuard 只打打它的、红名）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(33, 30))
        val g   = put(s.w, "弓箭守卫", Cell(30, 30))
        val pet = put(s.w, "蛤蟆", Cell(32, 30))
        pet.master = Some(p.id)
        p.pets += pet.id
        s.run(10_000)
        assertTrue(!pet.dead, pet.hp == pet.maxHp, !s.w.isEnemy(g, pet))
      },
      test("守卫砍某只宝宝时，挨砍的还手、同主人的宝宝一起围上去，别人的宝宝和主人不掺和（#1216，IsAttackTarget）") {
        val s     = sim()
        val p     = join(s.w, "甲", Cell(40, 30))
        val q     = join(s.w, "乙", Cell(40, 40))
        val g     = put(s.w, "卫士", Cell(30, 30))
        val a     = put(s.w, "骷髅", Cell(32, 30))
        val b     = put(s.w, "骷髅", Cell(36, 30))
        val other = put(s.w, "骷髅", Cell(36, 33))
        Seq(a, b).foreach { m =>
          m.master = Some(p.id)
          p.pets += m.id
        }
        other.master = Some(q.id)
        q.pets += other.id
        g.target = Some(a.id)
        s.w.vitals.strike(Some(g), a, Blow(Defence.Pierce, 1)): Unit
        assertTrue(
          a.target.contains(g.id),
          s.w.isEnemy(a, g),
          s.w.isEnemy(b, g),
          !s.w.isEnemy(other, g),
          !s.w.isEnemy(p, g),
          s.w.isEnemy(g, other)
        )
      },
      test("怪贴身打玩家时宝宝打它一下，它就改打宝宝（#1207，AnimalObject.Struck）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(30, 30))
        val m   = put(s.w, "洞蛆", Cell(31, 30))
        val pet = put(s.w, "蛤蟆", Cell(33, 30))
        pet.master = Some(p.id)
        p.pets += pet.id
        m.target = Some(p.id)
        s.w.vitals.strike(Some(pet), m, Blow(Defence.Pierce, 1)): Unit
        assertTrue(!m.dead, m.target.contains(pet.id))
      },
      test("有目标的怪每 8 秒重找一次最近的敌人，换成更近的宝宝（#1207，AtMonster.Run）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(30, 30))
        val m   = put(s.w, "掷斧骷髅", Cell(35, 30)) // 隔 5 格扔斧头，不用走动
        val pet = put(s.w, "蛤蟆", Cell(37, 30))
        pet.master = Some(p.id)
        p.pets += pet.id
        pet.statuses.put(Status.Seized, Millis(Long.MaxValue / 2)) // 宝宝站着不还手
        s.w.vitals.setMaxHp(pet, 1_000_000)
        m.target = Some(p.id)
        m.lastSearch = s.w.now
        s.run(2000)
        val early = m.target
        s.run(7000)
        assertTrue(early.contains(p.id), m.target.contains(pet.id))
      },
      test("僵尸（LightingZombi）有目标就一直追着，不按 8 秒换（#1207）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(30, 30))
        val m   = put(s.w, "僵尸1", Cell(34, 30))
        val pet = put(s.w, "蛤蟆", Cell(36, 30))
        pet.master = Some(p.id)
        p.pets += pet.id
        pet.statuses.put(Status.Seized, Millis(Long.MaxValue / 2))
        s.w.vitals.setMaxHp(pet, 1_000_000)
        m.target = Some(p.id)
        s.run(10_000)
        assertTrue(m.target.contains(p.id))
      },
      test("宝宝离主人超过 20 格就瞬移回主人身边，丢下原来的目标（#1210，ObjMon.pas SpaceMove）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(10, 30))
        val m   = put(s.w, "洞蛆", Cell(40, 31))
        val pet = put(s.w, "蛤蟆", Cell(40, 30))
        pet.master = Some(p.id)
        p.pets += pet.id
        pet.target = Some(m.id)
        s.run(2500) // 刚刷出的怪最多 2 秒后才开始想
        assertTrue(pet.cell.chebyshev(p.cell) <= 2, !pet.target.contains(m.id))
      },
      test("宝宝离主人 20 格以内不瞬移，自己走回去（#1210）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(10, 30))
        val pet = put(s.w, "蛤蟆", Cell(30, 30))
        pet.master = Some(p.id)
        p.pets += pet.id
        s.run(2500)
        assertTrue(pet.cell.chebyshev(p.cell) >= 12, pet.cell.chebyshev(p.cell) <= 20)
      },
      test("主人在同一张图里传送，宝宝跟到新位置旁边（#1210）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(10, 30))
        val pet = put(s.w, "蛤蟆", Cell(11, 30))
        pet.master = Some(p.id)
        p.pets += pet.id
        s.w.roster.transfer(p, Place(s.w.mapId, Cell(50, 50)))
        s.flush()
        assertTrue(p.cell.chebyshev(Cell(50, 50)) <= 1, pet.cell.chebyshev(p.cell) <= 2)
      },
      test("传送菜单：每个目的地都是游戏里有的地图，传到别的图交给服务端") {
        val s    = sim()
        val p    = join(s.w, "甲", Cell(30, 30))
        val list = catalog.data.teleports
        val dest = list(1)
        s.flush()
        s.cmd(p, GameCmd.Teleport(TeleportRef(1)))
        assertTrue(
          list.size > 10,
          list.forall(t => catalog.map(t.map).isDefined),
          s.departures.map(_.to) == Vector(Place(dest.map, dest.at)),
          s.w.player(p.id).isEmpty
        )
      }
    ),
    suite("多人")(
      test("组队分经验（#1220，照原版 GainExp）：同图 12 格内活着的队友按人数加成后按等级分，远的、死的、不在队里的不分") {
        val team = Set("甲", "乙", "丙", "丁")
        val w    = World(catalog, testMap, Terrain.open(60, 60), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0), n => if team(n) then team.toSeq else Nil)
        val s    = Sim(w)
        val a    = join(w, "甲", Cell(20, 30))
        val b    = join(w, "乙", Cell(28, 38)) // 横竖都在 12 格内
        val far  = join(w, "丙", Cell(20, 43)) // 纵向 13 格：不分
        val dead = join(w, "丁", Cell(21, 30))
        val out  = join(w, "戊", Cell(22, 30)) // 不在队里
        a.level = 1
        b.level = 3
        w.vitals.damage(dead, 1_000_000, None)
        val chick = put(w, "鸡", Cell(21, 31))
        s.flush()
        def gained(p: Player, got: Map[ActorId, Vector[ServerMsg]]): Long =
          got.getOrElse(p.id, Vector.empty).collect { case ServerMsg.Notice(t, _) if t.startsWith("获得经验 ") => t.drop(5).toLong }.sum
        w.vitals.damage(chick, 1_000_000, Some(a.id))
        val got   = s.flush()
        val exp   = Rates.killExp(chick.defn.exp, 1.0)
        val total = math.round(exp.value * 1.3)
        assertTrue(
          exp.value > 0,
          gained(a, got) == math.round(total / 4.0 * 1),
          gained(b, got) == math.round(total / 4.0 * 3),
          gained(far, got) == 0L, gained(dead, got) == 0L, gained(out, got) == 0L
        )
      },
      test("组队但附近没有活着的队友：打死怪的人照常全拿") {
        val w = World(catalog, testMap, Terrain.open(60, 60), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0), _ => Seq("甲", "乙"))
        val s = Sim(w)
        val a = join(w, "甲", Cell(20, 30))
        join(w, "乙", Cell(50, 50))
        val chick = put(w, "鸡", Cell(21, 30))
        s.flush()
        w.vitals.damage(chick, 1_000_000, Some(a.id))
        val got = s.flush().getOrElse(a.id, Vector.empty)
        assertTrue(got.contains(ServerMsg.Notice(s"获得经验 ${Rates.killExp(chick.defn.exp, 1.0).value}", Tone.Good)))
      },
      test("两人同图互相看见，视野副本与世界一致") {
        val s = sim()
        val a = join(s.w, "甲", Cell(30, 30))
        val b = join(s.w, "乙", Cell(33, 30))
        s.flush()
        val va = s.views(a.id)
        val vb = s.views(b.id)
        assertTrue(
          va.actors.contains(b.id), vb.actors.contains(a.id),
          va.actors(b.id).look == Look.Human("乙", Job.Warrior, Sex.Male, 1, Some(ItemName("铁剑")), Some(ItemName("布衣(男)"))),
          s.consistent(a), s.consistent(b)
        )
      },
      test("别人开魔法盾旁人看得见，后进视野的也看得见，到期一起消失（#1297）") {
        val s = sim()
        val a = join(s.w, "甲", Cell(30, 30))
        val b = join(s.w, "乙", Cell(33, 30))
        s.flush()
        val before = s.views(b.id).actors(a.id).auras
        s.w.conditions.addStatus(a, Status.Shielded(2), 5_000)
        s.flush()
        val seenB = s.views(b.id).actors(a.id).auras
        val c     = join(s.w, "丙", Cell(30, 33))
        s.flush()
        val seenC = s.views(c.id).actors(a.id).auras
        s.run(6_000)
        assertTrue(
          before.isEmpty,
          seenB == Set(Aura.MagicShield), seenC == Set(Aura.MagicShield),
          s.views(b.id).actors(a.id).auras.isEmpty, s.views(c.id).actors(a.id).auras.isEmpty,
          s.views(a.id).actors(a.id).auras.isEmpty,
          s.consistent(a), s.consistent(b)
        )
      },
      test("各打各的怪；怪的血量、死活以世界为准，两人看到的一样") {
        val s  = sim(seed = 7)
        val a  = join(s.w, "甲", Cell(20, 30))
        val b  = join(s.w, "乙", Cell(26, 30))
        val ma = put(s.w, "鸡", Cell(21, 30))
        val mb = put(s.w, "鸡", Cell(27, 30))
        s.flush()
        (1 to 40).foreach { _ =>
          if !ma.dead then s.w.handle(a.id, GameCmd.Attack(Dir.Right, Some(ma.id)))
          if !mb.dead then s.w.handle(b.id, GameCmd.Attack(Dir.Right, Some(mb.id)))
          s.run(1000)
        }
        val va = s.views(a.id)
        val vb = s.views(b.id)
        assertTrue(
          ma.dead, mb.dead,
          a.exp.value > 0, b.exp.value > 0,
          va.actors.get(mb.id).map(_.dead) == vb.actors.get(mb.id).map(_.dead),
          s.consistent(a), s.consistent(b)
        )
      },
      test("掉落以世界为准：一人打死怪，附近两人看到同样的地上物品，只有一人捡得到") {
        val s = sim(seed = 3)
        val a = join(s.w, "甲", Cell(20, 30))
        val b = join(s.w, "乙", Cell(24, 30))
        a.settings = a.settings.copy(autoPickup = false)
        b.settings = b.settings.copy(autoPickup = false)
        s.w.dropAt(Cell(21, 30), Loot.Coins(Gold(500)))
        s.flush()
        val seenA = s.views(a.id).ground.values.toSet
        val seenB = s.views(b.id).ground.values.toSet
        val g     = s.w.groundItems.head
        s.w.grid.teleportActor(a, g.cell)
        s.cmd(a, GameCmd.PickUp)
        s.w.grid.teleportActor(a, Cell(g.cell.x - 2, g.cell.y))
        s.w.grid.teleportActor(b, g.cell)
        val gold0 = b.gold
        s.cmd(b, GameCmd.PickUp)
        assertTrue(
          seenA == seenB, seenA.size == 1,
          a.gold == Gold(800), b.gold == gold0,
          s.views(a.id).ground.isEmpty, s.views(b.id).ground.isEmpty
        )
      },
      test("一只怪最多掉 10 件：掷中 13 件时留最稀有的，金币照掉不占名额（#1241）；金币和药直接进归属者身上（#1328）") {
        def row(n: String, num: Int, den: Int, amount: Int = 1) = DropDef(ItemName(n), num, den, amount)
        // 12 行药各一叠（必中）+ 裁决之杖（原版 1/20，×20 必中，最稀有）+ 金币
        val rows = List.fill(12)(row("金创药(小量)", 1, 1)) ++ List(row("裁决之杖", 1, 20), row(DropDef.GoldName.value, 1, 1, 100))
        val cat  = Catalog(catalog.data.copy(drops = catalog.data.drops + (MonsterName("鸡") -> rows)))
        val w    = World(cat, testMap, Terrain.open(testMap.width, testMap.height), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0))
        val p    = join(w, "甲", Cell(20, 30))
        def pots = (p.bag.iterator ++ p.belt.iterator).flatten.filter(_.name == ItemName("金创药(小量)")).map(_.count).sum
        val (pots0, gold0) = (pots, p.gold)
        val hen  = put(w, "鸡", Cell(21, 30))
        w.vitals.damage(hen, 100_000, Some(p.id))
        val loot  = w.groundItems.map(_.loot).toList
        val items = loot.collect { case Loot.Item(it) => it.name.value }
        assertTrue(
          hen.dead,
          items == List("裁决之杖"),
          pots - pots0 == 9,
          p.gold.value > gold0.value,
          loot.count { case Loot.Coins(_) => true; case _ => false } == 0
        )
      },
      test("怪掉的药放不下（包裹和快捷栏满了）照旧掉地上，带归属保护（#1328）") {
        val rows = List(DropDef(ItemName("金创药(中量)"), 1, 1, 1), DropDef(DropDef.GoldName, 1, 1, 100))
        val cat  = Catalog(catalog.data.copy(drops = catalog.data.drops + (MonsterName("鸡") -> rows)))
        val w    = World(cat, testMap, Terrain.open(testMap.width, testMap.height), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0))
        val p    = join(w, "甲", Cell(20, 30))
        p.settings = p.settings.copy(autoPickup = false)
        val pot  = cat.item(ItemName("金创药(中量)")).get
        p.bag.indices.foreach(i => p.bag(i) = w.newItem(ItemName("木剑")))
        p.belt.indices.foreach(i => p.belt(i) = w.newItem(ItemName("木剑")))
        val gold0 = p.gold
        val hen  = put(w, "鸡", Cell(21, 30))
        w.vitals.damage(hen, 100_000, Some(p.id))
        val ground = w.groundItems.toList
        assertTrue(
          !Inventory.hasRoom(p, pot),
          ground.map(_.loot).collect { case Loot.Item(it) => it.name } == List(pot.name),
          ground.forall(_.ward.exists(_.owner == "甲")),
          p.gold.value > gold0.value
        )
      },
      test("归属照原版（#1158）：先打的人拿经验和掉落，补最后一刀的拿不走；保护 1 分钟内只有他和队友捡得起") {
        val rows = List(DropDef(ItemName("铁剑"), 1, 1, 1), DropDef(ItemName("铁剑"), 1, 1, 1))
        val cat  = Catalog(catalog.data.copy(drops = catalog.data.drops + (MonsterName("鸡") -> rows)))
        val team = Set("甲", "丙")
        val w    = World(cat, testMap, Terrain.open(60, 60), Rng.seeded(1), UidSource.counter(1_000_000), Millis(0), n => if team(n) then team.toSeq else Nil)
        val s    = Sim(w)
        val a    = join(w, "甲", Cell(20, 30))
        val b    = join(w, "乙", Cell(22, 30))
        val c    = join(w, "丙", Cell(40, 40)) // 甲的队友，离得远不分经验
        Seq(a, b, c).foreach(p => p.settings = p.settings.copy(autoPickup = false))
        val hen = put(w, "鸡", Cell(21, 30))
        w.vitals.damage(hen, 1, Some(a.id))
        w.vitals.damage(hen, 1_000_000, Some(b.id))
        val exp0 = (a.exp, b.exp)
        val drops = w.groundItems.toVector
        def heldIn(p: Player) = p.bag.flatten.count(_.name == ItemName("铁剑"))
        val start = Seq(b, c).map(p => p.id -> heldIn(p)).toMap
        def bagged(p: Player) = heldIn(p) - start(p.id)
        s.flush()
        w.grid.teleportActor(b, drops(0).cell)
        val refused = s.cmd(b, GameCmd.PickUp).contains(ServerMsg.Notice("在一定时间以内无法捡起此物品！", Tone.Bad)) && bagged(b) == 0
        w.grid.teleportActor(c, drops(0).cell)
        s.cmd(c, GameCmd.PickUp)
        val mateOk = bagged(c) == 1
        w.tick(w.now + Rates.lootWardMs)
        w.grid.teleportActor(b, drops(1).cell)
        s.cmd(b, GameCmd.PickUp)
        assertTrue(
          hen.dead, exp0._1.value > 0, exp0._2.value == 0L,
          drops.size == 2, drops.forall(_.ward.exists(_.owner == "甲")),
          refused, mateOk, bagged(b) == 1
        )
      },
      test("归属者停手超过 6 秒就作废，下一个打它的人接手（#1158）") {
        val w   = world()
        val s   = Sim(w)
        val a   = join(w, "甲", Cell(20, 30))
        val b   = join(w, "乙", Cell(40, 30))
        val hen = put(w, "鸡", Cell(30, 30))
        w.vitals.damage(hen, 1, Some(a.id))
        s.run(6500)
        w.vitals.damage(hen, 1, Some(b.id))
        w.vitals.damage(hen, 1_000_000, Some(a.id))
        assertTrue(hen.dead, b.exp.value > 0, a.exp.value == 0L, w.groundItems.forall(_.ward.exists(_.owner == "乙")))
      },
      test("一人换图后从另一人视野消失") {
        val s = sim()
        val a = join(s.w, "甲", Cell(30, 30))
        val b = join(s.w, "乙", Cell(32, 30))
        s.flush()
        val before = s.views(b.id).actors.contains(a.id)
        s.w.handle(a.id, GameCmd.Teleport(TeleportRef(0)))
        val got = s.flush()
        assertTrue(
          before,
          got(b.id).contains(ServerMsg.Disappear(a.id)),
          !s.views(b.id).actors.contains(a.id),
          s.departures.exists(_.player == a.id),
          s.consistent(b)
        )
      },
      test("喝了普通药马上换图：没回完的药、魔法盾跟到新图接着生效（#1170）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(30, 30), tough = false)
        val pot = p.belt.flatten.head
        val hp  = catalog.item(pot.name).map(mir.rules.ItemKind.of).collect { case mir.rules.ItemKind.Potion(h, _, false) => h }.get
        p.settings = p.settings.copy(autoHpPercent = 0) // 只看这一瓶，不让自动喝药再补
        p.hp = 1
        s.w.conditions.addStatus(p, Status.Shielded(2), 60_000)
        s.cmd(p, GameCmd.UseItem(pot.uid))
        s.w.handle(p.id, GameCmd.Teleport(TeleportRef(0)))
        s.flush()
        val d   = s.departures.find(_.player == p.id).get
        val s2  = sim(testMap.copy(id = MapId("U")))
        val p2  = s2.w.player(s2.w.join(d.data.copy(place = Place(s2.w.mapId, Cell(30, 30))), d.carried)).get
        s2.run(1700) // 药 5 跳在 0~1600 毫秒，自然回复要到 2000 毫秒
        assertTrue(
          s.w.player(p.id).isEmpty,
          p2.hp == math.min(p2.stats.maxHp, 1 + mir.rules.PotionRules.Ticks * mir.rules.PotionRules.perTick(hp)),
          p2.potions.isEmpty,
          s2.w.visibility.selfView(p2).status.shieldUntil.contains(Millis(60_000))
        )
      },
      test("宝宝换图带着身板：血、攻击、防御、魔防都还原（#1209）") {
        val s    = sim()
        val p    = join(s.w, "甲", Cell(30, 30))
        val pet  = put(s.w, "蛤蟆", Cell(31, 30))
        val body = mir.rules.PetBody(500, Span(20, 30), Span(11, 12), Span(13, 14))
        pet.master = Some(p.id)
        p.pets += pet.id
        s.w.vitals.shape(pet, body, Some(321))
        s.w.handle(p.id, GameCmd.Teleport(TeleportRef(0)))
        s.flush()
        val d   = s.departures.find(_.player == p.id).get
        val s2  = sim(testMap.copy(id = MapId("U")))
        val p2  = s2.w.player(s2.w.join(d.data.copy(place = Place(s2.w.mapId, Cell(30, 30))), d.carried)).get
        val got = p2.pets.flatMap(s2.w.actor).collectFirst { case m: Monster => m }
        assertTrue(got.map(_.body).contains(body), got.map(_.hp).contains(321), got.map(_.ac).contains(Span(11, 12)))
      },
      test("离开视野范围就消失，走回来重新出现") {
        val s = sim()
        val a = join(s.w, "甲", Cell(5, 30))
        val b = join(s.w, "乙", Cell(8, 30))
        s.flush()
        s.w.grid.teleportActor(b, Cell(5 + World.ViewRange + 3, 30))
        s.flush()
        val gone = !s.views(a.id).actors.contains(b.id)
        s.w.grid.teleportActor(b, Cell(9, 30))
        s.flush()
        assertTrue(gone, s.views(a.id).actors.contains(b.id), s.consistent(a), s.consistent(b))
      },
      test("一群人打一群怪跑 60 秒，每一步视野副本都与世界一致") {
        val s    = sim(seed = 11)
        val ps   = List(join(s.w, "甲", Cell(20, 20)), join(s.w, "乙", Cell(25, 25), Job.Wizard), join(s.w, "丙", Cell(40, 40), Job.Taoist))
        List("鸡", "鹿", "稻草人", "钉耙猫", "多钩猫", "半兽人", "食人花", "洞蛆").zipWithIndex.foreach { (n, i) =>
          put(s.w, n, Cell(15 + i * 4, 22 + (i % 3) * 5))
        }
        val rng = Rng.seeded(5)
        val bad = (1 to 600).count { step =>
          ps.foreach { p =>
            rng.random(4) match
              case 0 => s.w.handle(p.id, GameCmd.Walk(p.cell, Dir.fromCode(rng.random(8))))
              case 1 => s.w.handle(p.id, GameCmd.Attack(Dir.fromCode(rng.random(8)), None))
              case 2 => s.w.handle(p.id, GameCmd.Turn(Dir.fromCode(rng.random(8))))
              case _ => ()
          }
          s.w.tick(s.w.now + 100)
          s.flush()
          !ps.forall(s.consistent)
        }
        assertTrue(bad == 0)
      }
    ),
    suite("人物穿怪（#1240）")(
      test("被怪堵在角落：走到怪身上、跑过两只怪都行，怪还在原地；离开后格子还给怪") {
        val s  = sim()
        val p  = join(s.w, "甲", Cell(0, 0))
        val ms = List(Cell(1, 0), Cell(0, 1), Cell(1, 1)).map(put(s.w, "蛤蟆", _))
        s.flush()
        val walked = s.cmd(p, GameCmd.Walk(Cell(0, 0), Dir.DownRight)).contains(ServerMsg.MoveOk)
        val onTop  = p.cell == Cell(1, 1) && s.w.occupants(Cell(1, 1)).map(_.id) == List(ms(2).id, p.id)
        // 跑：起点 (0,0)，(1,1)、(2,2) 都站着怪，一口气跑到 (2,2)
        val s2 = sim()
        val q  = join(s2.w, "乙", Cell(0, 0))
        List(Cell(1, 0), Cell(0, 1), Cell(1, 1), Cell(2, 2)).foreach(put(s2.w, "蛤蟆", _))
        s2.flush()
        val ran = s2.cmd(q, GameCmd.Run(Cell(0, 0), Dir.DownRight)).contains(ServerMsg.MoveOk) && q.cell == Cell(2, 2)
        // 走开后那格只剩怪
        s.run(1000)
        val left = s.cmd(p, GameCmd.Walk(Cell(1, 1), Dir.Right)).contains(ServerMsg.MoveOk) &&
          s.w.occupants(Cell(1, 1)).map(_.id) == List(ms(2).id)
        assertTrue(walked, onTop, ran, left, ms.forall(m => !m.dead))
      },
      test("别的玩家照旧挡路；怪不能走进玩家站的格子（叠着怪和人的格子也不行）") {
        val s = sim()
        val p = join(s.w, "甲", Cell(10, 10))
        val q = join(s.w, "乙", Cell(11, 10))
        val m = put(s.w, "蛤蟆", Cell(10, 12))
        s.flush()
        val blocked = s.cmd(p, GameCmd.Walk(Cell(10, 10), Dir.Right)).contains(ServerMsg.MoveDenied(Cell(10, 10), p.dir))
        s.run(1000)
        s.cmd(p, GameCmd.Walk(Cell(10, 10), Dir.Down)): Unit
        s.run(1000)
        s.cmd(p, GameCmd.Walk(Cell(10, 11), Dir.Down)): Unit
        val stacked = p.cell == Cell(10, 12)
        assertTrue(
          blocked, stacked,
          !s.w.walkableFor(m)(Cell(11, 10)), !s.w.walkableFor(m)(Cell(10, 12)), !s.w.canEnter(Cell(10, 12)),
          s.w.walkableFor(q)(Cell(10, 12)) == false // 叠着玩家的格子别的玩家也进不去
        )
      },
      test("站在锁定的怪身上时，寻路「走到旁边」会先挪开一格") {
        val t    = Terrain.open(10, 10)
        val path = mir.terrain.Path.find(t.walkable, Cell(5, 5), Cell(5, 5), 100, adjacent = true)
        assertTrue(path.exists(_.size == 1), mir.terrain.Path.find(t.walkable, Cell(5, 5), Cell(5, 5), 100, adjacent = false).contains(Nil))
      },
      test("挥刀打前面一格：那格叠着怪和玩家时打得到怪") {
        val s = sim()
        val p = join(s.w, "甲", Cell(10, 10))
        val q = join(s.w, "乙", Cell(20, 20))
        val m = put(s.w, "蛤蟆", Cell(11, 10))
        s.w.grid.teleportActor(q, Cell(11, 10))
        val hp = m.hp
        (0 until 10).foreach { _ =>
          s.run(1500)
          s.cmd(p, GameCmd.Attack(Dir.Right, Some(m.id))): Unit
        }
        assertTrue(m.hp < hp || m.dead)
      }
    ),
    suite("防加速（#1319）：早到的指令照收，但提前量不累积")(
      test("每步都抢早发走路：10 步至少花 9×600−余量 毫秒（旧规则每步都快一个余量）") {
        val s  = sim()
        val p  = join(s.w, "甲", Cell(30, 30))
        s.flush()
        // 改版客户端：每 50 毫秒都试一次，能收就收
        val oks = Iterator.continually {
          s.run(50)
          val dir = if p.cell.x < 35 then Dir.Right else Dir.Left
          Option.when(s.cmd(p, GameCmd.Walk(p.cell, dir)).contains(ServerMsg.MoveOk))(s.w.now)
        }.flatten.take(10).toVector
        assertTrue(oks.last - oks.head >= 9L * Pace.WalkMs - World.Slack)
      },
      test("每下都抢早发普通攻击：10 下至少花 9×攻击间隔−余量 毫秒") {
        val s  = sim()
        val p  = join(s.w, "甲", Cell(30, 30))
        s.flush()
        val gap = math.max(Pace.HitAnimMs, p.stats.hitInterval)
        val oks = Iterator.continually {
          s.run(50)
          Option.when(Combat.attack(s.w, p, Dir.Up, World.Slack) == Outcome.Done)(s.w.now)
        }.flatten.take(10).toVector
        assertTrue(oks.last - oks.head >= 9L * gap - World.Slack)
      },
      test("正常节奏带 0~250 毫秒网络抖动：走路、攻击、施法都不被拒") {
        val s  = sim()
        val p  = join(s.w, "甲", Cell(30, 30), job = Job.Wizard)
        s.flush()
        val jitter = Vector(250, 0, 250, 0, 120, 30, 0, 250, 90, 10, 150, 0, 60, 240, 0, 250, 100, 0, 250, 75)
        // 客户端按节奏发，每条在路上多耽搁 jitter 毫秒才到
        def paced(every: Int)(act: => Boolean): Boolean =
          val t0 = s.w.now + 1000
          jitter.zipWithIndex.forall { (j, k) =>
            s.w.tick(t0 + (k.toLong * every + j))
            s.flush()
            act
          }
        val walked = paced(Pace.WalkMs) {
          val dir = if p.cell.x < 35 then Dir.Right else Dir.Left
          s.cmd(p, GameCmd.Walk(p.cell, dir)).contains(ServerMsg.MoveOk)
        }
        val gap = math.max(Pace.HitAnimMs, p.stats.hitInterval)
        val hit = paced(gap)(Combat.attack(s.w, p, Dir.Up, World.Slack) == Outcome.Done)
        // 施法：「忙到」和冷却（火球术 800 毫秒）都容忍抖动（#1321）
        val fireBall = SkillId(1)
        p.skills += LearnedSkill(fireBall, 3, 0, None)
        val dummy = put(s.w, "鸡", p.cell.step(Dir.Up, 3))
        def castAt(skill: SkillId) =
          p.mp = 1000
          dummy.hp = 1_000_000 // 打不死的靶子
          Combat.cast(s.w, p, skill, SpellTarget(dummy.cell, Some(dummy)), World.Slack) == Outcome.Done
        val cast = paced(Pace.SpellIntervalMs)(castAt(fireBall))
        // 冷却比施法间隔长的（雷电术 1000 毫秒）按冷却的节奏发
        val thunder = SkillId(11)
        p.skills += LearnedSkill(thunder, 3, 0, None)
        val cd = mir.rules.Skill.of(thunder).flatMap(sk => s.w.catalog.skill(thunder).flatMap(sk.cooldownMs)).get
        val cooled = paced(cd)(castAt(thunder))
        assertTrue(walked, hit, cast, cooled, cd > Pace.SpellIntervalMs)
      },
      test("每次都抢早发雷电术（冷却 1000）：10 次至少花 9×冷却−余量 毫秒（#1321 冷却加了余量也不累积）") {
        val s  = sim()
        val p  = join(s.w, "甲", Cell(30, 30), job = Job.Wizard)
        s.flush()
        val thunder = SkillId(11)
        p.skills += LearnedSkill(thunder, 3, 0, None)
        val dummy = put(s.w, "鸡", p.cell.step(Dir.Up, 3))
        val cd    = mir.rules.Skill.of(thunder).flatMap(sk => s.w.catalog.skill(thunder).flatMap(sk.cooldownMs)).get
        val oks = Iterator.continually {
          s.run(50)
          p.mp = 1000
          dummy.hp = 1_000_000
          Option.when(Combat.cast(s.w, p, thunder, SpellTarget(dummy.cell, Some(dummy)), World.Slack) == Outcome.Done)(s.w.now)
        }.flatten.take(10).toVector
        assertTrue(oks.last - oks.head >= 9L * cd - World.Slack)
      }
    ),
    suite("非法指令被拒，不能凭空生成物品和金币")(
      test("用、扔、卖不在身上的物品；仓库里没有的取不出；走错起点被拉回") {
        val s    = sim()
        val p    = join(s.w, "甲", Cell(30, 30))
        s.flush()
        val gold = p.gold
        val bag  = s.w.visibility.selfView(p)
        val fake = ItemUid(999_999_999L)
        val r1 = s.cmd(p, GameCmd.UseItem(fake))
        val r2 = s.cmd(p, GameCmd.DropItem(fake))
        val r3 = s.cmd(p, GameCmd.Sell(ActorId(12345), p.belt.flatten.head.uid))
        val r4 = s.cmd(p, GameCmd.Withdraw(ActorId(12345), fake))
        val r5 = s.cmd(p, GameCmd.Buy(ActorId(12345), ItemName("木剑")))
        val r6 = s.cmd(p, GameCmd.Walk(Cell(10, 10), Dir.Up))
        val r7 = s.cmd(p, GameCmd.MoveItem(p.belt.flatten.head.uid, ItemPlace.Bag(99)))
        val r8 = s.cmd(p, GameCmd.Equip(p.belt.flatten.head.uid, None))
        def refused(r: Vector[ServerMsg]) = r.exists { case ServerMsg.Refused(_) => true; case _ => false }
        assertTrue(
          List(r1, r2, r3, r4, r5, r7, r8).forall(refused),
          r6.contains(ServerMsg.MoveDenied(Cell(30, 30), p.dir)),
          p.gold == gold,
          s.w.visibility.selfView(p).bag == bag.bag, s.w.visibility.selfView(p).belt == bag.belt,
          s.w.groundItems.isEmpty
        )
      },
      test("在商店买东西：钱够才买，离远了不卖，卖出拿一半价钱；买的药并进同名一叠，一叠可以只卖一个") {
        val bichi = catalog.map(MapId("0")).get
        val s     = sim(bichi)
        val shopI = catalog.data.merchants.indexWhere(m => m.map == bichi.id && m.goods.exists(_.name == ItemName("金创药(小量)")))
        val shop  = s.w.actorsNow.collectFirst { case n: Npc if n.ref == MerchantRef(shopI) => n }.get
        val p     = join(s.w, "甲", shop.cell.step(Dir.Down, 2))
        s.flush()
        val price = catalog.item(ItemName("金创药(小量)")).get.price
        val g0    = p.gold
        s.cmd(p, GameCmd.Buy(shop.id, ItemName("金创药(小量)")))
        val g1    = p.gold
        val notSold = s.cmd(p, GameCmd.Buy(shop.id, ItemName("裁决之杖")))
        p.gold = Gold(0)
        val poor  = s.cmd(p, GameCmd.Buy(shop.id, ItemName("金创药(小量)")))
        p.gold = Gold(1000)
        s.w.grid.teleportActor(p, shop.cell.step(Dir.Down, 10))
        val far   = s.cmd(p, GameCmd.Buy(shop.id, ItemName("金创药(小量)")))
        s.w.grid.teleportActor(p, shop.cell.step(Dir.Down, 2))
        val stack = p.belt.flatten.head
        val uid   = stack.uid
        s.cmd(p, GameCmd.Sell(shop.id, uid, Some(1)))
        val sold1 = p.gold == Gold(1000 + price / 2) && p.belt.flatten.head.count == stack.count - 1
        s.cmd(p, GameCmd.Sell(shop.id, uid))
        def refused(r: Vector[ServerMsg]) = r.exists { case ServerMsg.Refused(_) => true; case _ => false }
        assertTrue(
          g1 == g0 - Gold(price),
          refused(notSold), refused(poor), refused(far),
          sold1,
          p.gold == Gold(1000 + price / 2 * stack.count),
          !p.belt.flatten.exists(_.uid == uid),
          s.consistent(p)
        )
      },
      test("批量买（#1304）：药并成一叠、装备一件一格；金币或格子不够买这么多、数量非法都整单拒绝") {
        val bichi = catalog.map(MapId("0")).get
        val s     = sim(bichi)
        def shopSelling(n: String) =
          val i = catalog.data.merchants.indexWhere(m => m.map == bichi.id && m.goods.exists(_.name == ItemName(n)))
          s.w.actorsNow.collectFirst { case npc: Npc if npc.ref == MerchantRef(i) => npc }.get
        val drug  = shopSelling("金创药(小量)")
        val p     = join(s.w, "甲", drug.cell.step(Dir.Down, 2))
        s.flush()
        def refused(r: Vector[ServerMsg]) = r.exists { case ServerMsg.Refused(_) => true; case _ => false }
        def potions = (p.belt.toVector ++ p.bag.toVector).flatten.filter(_.name == ItemName("金创药(小量)"))
        val price  = catalog.item(ItemName("金创药(小量)")).get.price
        p.gold = Gold(price * 30)
        val have0  = potions.map(_.count).sum
        val stacks0 = potions.size
        s.cmd(p, GameCmd.Buy(drug.id, ItemName("金创药(小量)"), 25))
        val bought = potions.map(_.count).sum == have0 + 25 && potions.size == math.max(1, stacks0) && p.gold == Gold(price * 5)
        val poor   = s.cmd(p, GameCmd.Buy(drug.id, ItemName("金创药(小量)"), 6))
        val zero   = s.cmd(p, GameCmd.Buy(drug.id, ItemName("金创药(小量)"), 0))
        val neg    = s.cmd(p, GameCmd.Buy(drug.id, ItemName("金创药(小量)"), -3))
        p.gold = Gold(Int.MaxValue)
        val huge   = s.cmd(p, GameCmd.Buy(drug.id, ItemName("金创药(小量)"), Int.MaxValue))
        val afterBad = p.gold == Gold(Int.MaxValue) && potions.map(_.count).sum == have0 + 25
        // 装备：包裹只剩 2 格时买 3 把整单拒绝，买 2 把正好放满
        val weapon = catalog.data.merchants.find(m => m.map == bichi.id && m.goods.exists(g => catalog.item(g.name).exists(d => !mir.rules.ItemKind.stacks(d))))
          .flatMap(_.goods.map(_.name).find(n => catalog.item(n).exists(d => !mir.rules.ItemKind.stacks(d)))).get
        val smith  = shopSelling(weapon.value)
        s.w.grid.teleportActor(p, smith.cell.step(Dir.Down, 2))
        val filler = ItemInstance.fresh(ItemUid(888_000_000L), catalog.item(weapon).get)
        p.bag.indices.foreach(i => p.bag(i) = Option.when(i >= 2)(filler.copy(uid = ItemUid(888_000_000L + i))))
        val free   = p.bag.count(_.isEmpty)
        val tooMany = s.cmd(p, GameCmd.Buy(smith.id, weapon, free + 1))
        val g2     = p.gold
        s.cmd(p, GameCmd.Buy(smith.id, weapon, free))
        val wprice = catalog.item(weapon).get.price
        assertTrue(
          bought,
          refused(poor), refused(zero), refused(neg), refused(huge),
          afterBad,
          free == 2, refused(tooMany),
          p.bag.forall(_.isDefined),
          p.gold == g2 - Gold(wprice * free),
          s.consistent(p)
        )
      },
      test("随时出售（#1178）：不用 NPC，一叠可只卖几个，价钱同商店；穿着的、不在身上的、死了都卖不了") {
        val s     = sim()
        val p     = join(s.w, "甲", Cell(30, 30))
        p.settings = p.settings.copy(autoPickup = false)
        s.flush()
        def refused(r: Vector[ServerMsg]) = r.exists { case ServerMsg.Refused(_) => true; case _ => false }
        val stack = p.belt.flatten.head
        val each  = mir.rules.Pricing.sell(catalog.item(stack.name).get)
        val g0    = p.gold
        s.cmd(p, GameCmd.QuickSell(stack.uid, Some(1)))
        val one   = p.gold == g0 + Gold(each) && p.belt.flatten.head.count == stack.count - 1
        val sword = s.w.newItem(ItemName("铁剑")).get
        Inventory.add(catalog, p, sword)
        val swordPrice = mir.rules.Pricing.sell(catalog.item(sword.name).get)
        val g1    = p.gold
        s.cmd(p, GameCmd.QuickSell(sword.uid))
        val soldSword = p.gold == g1 + Gold(swordPrice) && !p.bag.flatten.exists(_.uid == sword.uid)
        val worn  = p.worn(EquipSlot.Weapon)
        val g2    = p.gold
        val rWorn = s.cmd(p, GameCmd.QuickSell(worn.uid))
        val rFake = s.cmd(p, GameCmd.QuickSell(ItemUid(999_999_999L)))
        val synced = s.consistent(p)
        p.hp = 0
        p.dead = true
        val rDead = s.cmd(p, GameCmd.QuickSell(stack.uid))
        assertTrue(
          each > 0, one, soldSword,
          refused(rWorn), refused(rFake), refused(rDead),
          p.gold == g2, p.worn.get(EquipSlot.Weapon).contains(worn),
          synced
        )
      },
      suite("护身符、药粉按次叠成一格（#1213）")(
        test("新拿到的一份就是它的次数；同名的并进那一格，次数相加；不同名的不混") {
          val s  = sim()
          val p  = join(s.w, "甲", Cell(30, 30))
          val a  = s.w.newItem(ItemName("护身符")).get
          val b  = s.w.newItem(ItemName("护身符")).get
          val g  = s.w.newItem(ItemName("灰色药粉(少量)")).get
          List(a, b, g).foreach(Inventory.add(catalog, p, _))
          val charms  = p.bag.toList.flatten.filter(_.name == ItemName("护身符"))
          val powders = p.bag.toList.flatten.filter(_.name == ItemName("灰色药粉(少量)"))
          assertTrue(a.count == 300, g.count == 100, charms.map(_.count) == List(600), powders.map(_.count) == List(100))
        },
        test("旧存档里一份一格的符和毒：进游戏并成一格，次数是每份剩余次数之和，不丢；左手镯戴着的也换成次数") {
          val s = sim()
          val d = CharacterData.newbie(catalog, "乙", Job.Taoist, Sex.Male, UidSource.counter(7000))
          def old(uid: Long, name: String, dura: Int) = Some(ItemInstance(ItemUid(uid), ItemName(name), dura, 30_000))
          val bag = d.bag.updated(0, old(1L, "护身符", 30_000)).updated(3, old(2L, "护身符", 1_200)).updated(5, old(3L, "护身符", 0))
            .updated(7, old(4L, "黄色药粉(少量)", 900))
          val worn = d.worn + (EquipSlot.BraceletL -> ItemInstance(ItemUid(5L), ItemName("灰色药粉(少量)"), 5_000, 10_000))
          val id = s.w.join(d.copy(bag = bag, worn = worn, place = Place(s.w.mapId, Cell(30, 30))))
          val p  = s.w.player(id).get
          assertTrue(
            p.bag(0).map(_.count).contains(312),
            p.bag(3).isEmpty, p.bag(5).isEmpty,
            p.bag(7).map(_.count).contains(9),
            p.worn.get(EquipSlot.BraceletL).map(_.count).contains(50)
          )
        },
        test("出售按次折算（一份 300 次卖 250，卖 150 次得 125）；左手镯上的一叠脱下来并回包裹那一格") {
          val s = sim()
          val p = join(s.w, "甲", Cell(30, 30))
          val c = s.w.newItem(ItemName("护身符")).get
          Inventory.add(catalog, p, c)
          val each = mir.rules.Pricing.sell(catalog.item(c.name).get)
          val g0   = p.gold
          s.cmd(p, GameCmd.QuickSell(c.uid, Some(150)))
          val half = p.gold == g0 + Gold(each / 2) && p.bag.flatten.find(_.name == c.name).map(_.count).contains(150)
          val worn = s.w.newItem(ItemName("护身符")).get
          p.worn(EquipSlot.BraceletL) = worn
          s.cmd(p, GameCmd.Unequip(EquipSlot.BraceletL))
          val merged = p.bag.toList.flatten.filter(_.name == c.name).map(_.count) == List(450) && !p.worn.contains(EquipSlot.BraceletL)
          val g1 = p.gold
          s.cmd(p, GameCmd.QuickSell(c.uid))
          assertTrue(each == 250, half, merged, p.gold == g1 + Gold(375), !p.bag.flatten.exists(_.name == c.name), s.consistent(p))
        },
        test("怪一次掉几份就是一叠几份的次数，捡起来并进身上那一格") {
          val s = sim()
          val p = join(s.w, "甲", Cell(30, 30))
          Inventory.add(catalog, p, s.w.newItem(ItemName("护身符")).get)
          val d = catalog.item(ItemName("护身符")).get
          s.w.dropAt(Cell(30, 30), Loot.Item(mir.rules.Charges.settle(d, ItemInstance.fresh(s.w.newUid(), d).copy(count = 2))))
          PlayerOps.pickUp(s.w, p)
          assertTrue(p.bag.toList.flatten.filter(_.name == d.name).map(_.count) == List(900))
        }
      ),
      test("自动捡物只捡设置里勾了的种类（#1178），总开关关了什么都不捡") {
        val s = sim()
        val p = join(s.w, "甲", Cell(30, 30))
        p.settings = p.settings.copy(autoPickup = true, pickupKinds = Set(PickupKind.Equip), pickupTier = PickupFloor.All)
        s.w.dropAt(Cell(32, 30), Loot.Item(s.w.newItem(ItemName("铁剑")).get))
        s.w.dropAt(Cell(28, 30), Loot.Item(s.w.newItem(ItemName("金创药(小量)")).get))
        s.w.dropAt(Cell(30, 33), Loot.Coins(Gold(77)))
        s.run(5000)
        val onlySword = p.bag.flatten.exists(_.name == ItemName("铁剑")) && s.w.groundItems.size == 2
        s.cmd(p, GameCmd.UpdateSettings(p.settings.copy(autoPickup = false, pickupKinds = PickupKind.values.toSet)))
        s.run(5000)
        val masterOff = s.w.groundItems.size == 2
        val g0 = p.gold
        s.cmd(p, GameCmd.UpdateSettings(p.settings.copy(autoPickup = true, pickupKinds = Set(PickupKind.Gold))))
        s.run(5000)
        val gotGold = p.gold == g0 + Gold(77) && s.w.groundItems.map(_.loot).toList.collect { case Loot.Item(it) => it.name } == List(ItemName("金创药(小量)"))
        assertTrue(onlySword, masterOff, gotGold, s.consistent(p))
      },
      test("自动捡装备按档位：只捡该档及以上，药品不受影响；「全部」都捡（#1301）") {
        val s = sim()
        // 法师：铁剑、炼狱没有魔法，不算比身上强，只看档位
        val p = join(s.w, "甲", Cell(30, 30), Job.Wizard)
        p.settings = p.settings.copy(autoPickup = true, pickupKinds = PickupKind.values.toSet, pickupTier = PickupFloor.Zuma)
        def drop(n: String, at: Cell) = s.w.dropAt(at, Loot.Item(s.w.newItem(ItemName(n)).get))
        drop("铁剑", Cell(32, 30))
        drop("炼狱", Cell(28, 30))
        drop("裁决之杖", Cell(30, 32))
        drop("屠龙", Cell(30, 28))
        drop("金创药(小量)", Cell(31, 31))
        s.run(8000)
        // 药捡起来进快捷栏
        def has(n: String) = (p.bag.iterator ++ p.belt.iterator).flatten.exists(_.name == ItemName(n))
        val zuma = List(has("裁决之杖"), has("屠龙"), has("金创药(小量)"), !has("铁剑"), !has("炼狱"))
        s.cmd(p, GameCmd.UpdateSettings(p.settings.copy(pickupTier = PickupFloor.All)))
        s.run(8000)
        val all = List(has("铁剑"), has("炼狱"), s.w.groundItems.isEmpty)
        assertTrue(zuma == List.fill(5)(true), all == List.fill(3)(true), s.consistent(p))
      },
      test("祝福油：给手上的武器加幸运并计入人物幸运；手上没武器不消耗（#1320）") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(30, 30))
        val oil = s.w.newItem(ItemName("祝福油")).get
        p.bag(0) = Some(oil)
        val before = p.stats.luck
        s.cmd(p, GameCmd.UseItem(oil.uid))
        val b      = mir.rules.BlessOil.blessing(p.worn(EquipSlot.Weapon))
        val used   = !p.bag.flatten.exists(_.uid == oil.uid)
        val after  = p.stats.luck
        val oil2   = s.w.newItem(ItemName("祝福油")).get
        p.bag(0) = Some(oil2)
        p.worn.remove(EquipSlot.Weapon)
        s.w.vitals.recalc(p)
        val refused = s.cmd(p, GameCmd.UseItem(oil2.uid)).exists { case ServerMsg.Refused(_) => true; case _ => false }
        assertTrue(
          used,
          b.luck + b.curse == 1, // 0 点的武器：必加 1 点幸运，或 1/25 机会被诅咒 1 点
          refused,
          p.bag.flatten.exists(_.uid == oil2.uid),
          after == before + b.luck - b.curse
        )
      },
      test("比身上强的装备不管档位都自动捡（#1301，用户定）") {
        val s = sim()
        val p = join(s.w, "甲", Cell(30, 30)) // 战士，换上木剑（新人自带铁剑，#1310）
        s.w.newItem(ItemName("木剑")).foreach(p.worn(EquipSlot.Weapon) = _)
        p.settings = p.settings.copy(autoPickup = true, pickupKinds = PickupKind.values.toSet, pickupTier = PickupFloor.Redmoon)
        s.w.dropAt(Cell(32, 30), Loot.Item(s.w.newItem(ItemName("铁剑")).get))
        s.w.dropAt(Cell(28, 30), Loot.Item(s.w.newItem(ItemName("魔法长袍(男)")).get))
        s.run(8000)
        val names = p.bag.flatten.map(_.name.value).toSet
        assertTrue(names("铁剑"), !names("魔法长袍(男)"), s.w.groundItems.size == 1, s.consistent(p))
      },
      test("自动捡物走到格子上只捡勾了的种类，同一格叠着的装备留在地上；手动捡取全捡（#1235）") {
        val s = sim()
        val p = join(s.w, "甲", Cell(25, 30))
        p.settings = p.settings.copy(autoPickup = false)
        // 周围 7×7 都铺满金币后，再掉的东西就叠在中心格上
        (1 to 49).foreach(_ => s.w.dropAt(Cell(30, 30), Loot.Coins(Gold(1))))
        s.w.dropAt(Cell(30, 30), Loot.Item(s.w.newItem(ItemName("铁剑")).get))
        val sword = s.w.groundItems.find(_.loot.isInstanceOf[Loot.Item]).get
        val stacked = s.w.groundItems.count(_.cell == sword.cell) >= 2
        val g0 = p.gold
        s.cmd(p, GameCmd.UpdateSettings(p.settings.copy(autoPickup = true, pickupKinds = Set(PickupKind.Gold))))
        s.run(60000)
        val coinsTaken = p.gold == g0 + Gold(49) && s.w.groundItems.map(_.id).toList == List(sword.id)
        val swordLeft  = !p.bag.flatten.exists(_.name == ItemName("铁剑"))
        s.cmd(p, GameCmd.UpdateSettings(p.settings.copy(autoPickup = false)))
        s.w.grid.teleportActor(p, sword.cell)
        s.cmd(p, GameCmd.PickUp)
        val manual = p.bag.flatten.exists(_.name == ItemName("铁剑")) && s.w.groundItems.isEmpty
        assertTrue(stacked, coinsTaken, swordLeft, manual, s.consistent(p))
      },
      test("物品按 StdMode 分到自动捡物的各类：肉和矿石一类、捆装药算药品") {
        def kind(n: String) = mir.rules.ItemKind.pickupKind(catalog.item(ItemName(n)).get)
        assertTrue(
          kind("肉") == PickupKind.Material, kind("铁矿") == PickupKind.Material, kind("蝎子尾巴") == PickupKind.Material,
          kind("金创药(小)包") == PickupKind.Potion, kind("太阳水") == PickupKind.Potion,
          kind("铁剑") == PickupKind.Equip, kind("护身符") == PickupKind.Amulet, kind("回城卷") == PickupKind.Scroll,
          kind("治愈术") == PickupKind.Book, kind("祖玛头像") == PickupKind.Other
        )
      },
      test("仓库：存进去取出来是同一件") {
        val (m, i) = catalog.data.merchants.zipWithIndex.find(_._1.service == MerchantService.Storage).get
        val map    = catalog.map(m.map).get
        val s      = sim(map)
        val npc    = s.w.actorsNow.collectFirst { case n: Npc if n.ref == MerchantRef(i) => n }.get
        val p      = join(s.w, "甲", npc.cell.step(Dir.Down, 2))
        val it     = p.belt.flatten.head
        s.cmd(p, GameCmd.Deposit(npc.id, it.uid))
        val stored = p.storage.toList
        val again  = s.cmd(p, GameCmd.Deposit(npc.id, it.uid))
        s.cmd(p, GameCmd.Withdraw(npc.id, it.uid))
        assertTrue(
          stored == List(it),
          again.exists { case ServerMsg.Refused(_) => true; case _ => false },
          p.storage.isEmpty,
          (p.belt.iterator ++ p.bag.iterator).flatten.contains(it),
          s.consistent(p)
        )
      },
      test("叠放（#1159）：捡、拆包并进同名一叠，喝一个减一，仓库存取几个，装备不叠") {
        val (m, i) = catalog.data.merchants.zipWithIndex.find(_._1.service == MerchantService.Storage).get
        val s      = sim(catalog.map(m.map).get)
        val npc    = s.w.actorsNow.collectFirst { case n: Npc if n.ref == MerchantRef(i) => n }.get
        val p      = join(s.w, "甲", npc.cell.step(Dir.Down, 2))
        s.flush()
        val red    = ItemName("金创药(小量)")
        def reds   = (p.belt.iterator ++ p.bag.iterator).flatten.filter(_.name == red).toList
        val start  = reds.map(_.count)
        s.w.newItem(red).foreach(it => PlayerOps.addToBag(s.w, p, it.copy(count = 5)))
        val merged = reds.map(_.count)
        val bundle = catalog.data.items.find(d => d.mode == 31 && catalog.data.items.exists(o => o.mode != 31 && o.ani == d.shape && o.name == red)).get
        s.w.newItem(bundle.name).foreach(it => PlayerOps.addToBag(s.w, p, it.copy(count = 2)))
        val bUid   = (p.belt.iterator ++ p.bag.iterator).flatten.find(_.name == bundle.name).get.uid
        s.cmd(p, GameCmd.UseItem(bUid))
        val opened = reds.map(_.count)
        val leftB  = (p.belt.iterator ++ p.bag.iterator).flatten.find(_.uid == bUid).map(_.count)
        p.hp = 1
        s.cmd(p, GameCmd.UseItem(reds.head.uid))
        val drank  = reds.map(_.count)
        s.cmd(p, GameCmd.Deposit(npc.id, reds.head.uid, Some(3)))
        s.cmd(p, GameCmd.Deposit(npc.id, reds.head.uid, Some(2)))
        val stored = p.storage.toList.map(x => x.name -> x.count)
        s.cmd(p, GameCmd.Withdraw(npc.id, p.storage.head.uid, Some(4)))
        val back   = (reds.map(_.count), p.storage.toList.map(_.count))
        val sword  = ItemName("木剑")
        (1 to 2).foreach(_ => s.w.newItem(sword).foreach(PlayerOps.addToBag(s.w, p, _)))
        val swords = p.bag.toList.flatten.filter(_.name == sword).map(_.count).toList
        assertTrue(
          start == List(4), merged == List(9), opened == List(15), leftB.contains(1), drank == List(14),
          stored == List(red -> 5), back == (List(13), List(1)), swords == List(1, 1),
          { s.flush(); s.consistent(p) }
        )
      },
      test("旧存档：没有数量的物品按 1，进图时同名药并成一叠") {
        import zio.json.*
        val s    = sim()
        val base = CharacterData.newbie(catalog, "甲", Job.Warrior, Sex.Male, UidSource.counter(1))
        val json = base.toJson.replaceAll(",\"count\":\\d+", "")
        val old  = json.fromJson[CharacterData].fold(e => throw new AssertionError(e), identity)
        val pot  = old.belt.flatten.head
        val split = old.copy(belt = old.belt.updated(1, Some(pot.copy(uid = ItemUid(77L)))).updated(2, Some(pot.copy(uid = ItemUid(78L)))))
        val id   = s.w.join(split.copy(place = Place(s.w.mapId, Cell(30, 30))))
        val p    = s.w.actor(id).collect { case p: Player => p }.get
        assertTrue(
          !json.contains("\"count\""),
          old.allItems.forall(_.count == 1),
          p.belt.flatten.filter(_.name == pot.name).map(_.count).toList == List(3)
        )
      },
      test("不限重量：1 级人物包里塞 20 件重盔甲还能再拿，只有格子满了才拿不了") {
        val s     = sim()
        val p     = join(s.w, "甲", Cell(30, 30), tough = false)
        val armor = catalog.item(ItemName("重盔甲(男)")).get
        (1 to 20).foreach(_ => s.w.newItem(armor.name).foreach(PlayerOps.addToBag(s.w, p, _)))
        val heavy = (p.bag.iterator ++ p.belt.iterator).flatten.count(_.name == armor.name)
        val more  = PlayerOps.fits(p, armor)
        while Inventory.hasRoom(p, armor) do s.w.newItem(armor.name).foreach(PlayerOps.addToBag(s.w, p, _))
        assertTrue(heavy == 20, armor.weight * 20 > 100, more.isRight, PlayerOps.fits(p, armor) == Left("包裹满了"))
      },
      test("穿脱装备、挪位置、喝药都由服务端改，副本跟着变") {
        val s   = sim()
        val p   = join(s.w, "甲", Cell(30, 30), tough = false)
        s.flush()
        val pot = p.belt.flatten.head
        p.hp = 1
        s.cmd(p, GameCmd.UseItem(pot.uid))
        s.run(3000)
        val healed = p.hp > 1
        val weapon = p.worn(EquipSlot.Weapon).name
        s.cmd(p, GameCmd.Unequip(EquipSlot.Weapon))
        val noWeapon = !p.worn.contains(EquipSlot.Weapon) && s.views(p.id).worn.get(EquipSlot.Weapon).isEmpty
        val sword = p.bag.flatten.find(_.name == weapon).get
        s.cmd(p, GameCmd.MoveItem(sword.uid, ItemPlace.Bag(10)))
        val moved = p.bag(10).contains(sword)
        s.cmd(p, GameCmd.MoveItem(sword.uid, ItemPlace.Worn(EquipSlot.Weapon)))
        val beltRefused = s.cmd(p, GameCmd.MoveItem(p.worn(EquipSlot.Dress).uid, ItemPlace.Belt(0)))
        assertTrue(
          healed, noWeapon, moved, p.worn.get(EquipSlot.Weapon).contains(sword),
          beltRefused.exists { case ServerMsg.Refused(_) => true; case _ => false },
          s.consistent(p)
        )
      }
    ),
    suite("大图")(
      test("比奇省按刷怪表刷满（上千只怪）、两人在城里待 30 秒：跑得动，只看得到身边的，视野副本一致") {
        val bichi = catalog.map(MapId("0")).get
        val s     = sim(bichi, seed = 2)
        val a     = join(s.w, "甲", CharacterData.start.cell)
        val b     = join(s.w, "乙", CharacterData.start.cell.step(Dir.Right, 3))
        val t0    = java.lang.System.nanoTime()
        s.run(30_000)
        val ms    = (java.lang.System.nanoTime() - t0) / 1_000_000
        val mons  = s.w.monsters.size
        println(s"比奇省 ${mons} 只怪，模拟 30 秒用了 $ms 毫秒")
        assertTrue(mons > 1000, s.consistent(a), s.consistent(b), s.views(a.id).actors.size < mons)
      }
    ),
    suite("自然回血（#1214）")(
      test("怪物每 6 秒回 上限/75+1；中绿毒期间不回，只掉血") {
        val s = sim()
        join(s.w, "甲", Cell(5, 5))
        val m = put(s.w, "僵尸1", Cell(55, 55))
        m.statuses.put(Status.Seized, Millis(Long.MaxValue / 2))
        val half = m.maxHp / 2
        m.hp = half
        s.run(5500)
        val before6s = m.hp
        s.run(1000)
        val after6s = m.hp
        s.w.conditions.poison(m, PoisonKind.Green, 20_000, 1, m.id)
        val poisoned = m.hp
        s.run(19_000)
        assertTrue(before6s == half, after6s == half + m.maxHp / 75 + 1, m.hp < poisoned)
      },
      test("玩家中绿毒不自然回血回蓝，毒消失后照常回") {
        val s = sim()
        val p = join(s.w, "甲", Cell(5, 5))
        val m = put(s.w, "僵尸1", Cell(55, 55))
        m.statuses.put(Status.Seized, Millis(Long.MaxValue / 2))
        p.belt.indices.foreach(p.belt(_) = None) // 不让它自动喝药
        p.bag.indices.foreach(p.bag(_) = None)
        p.hp = 50_000
        p.mp = 0
        s.w.conditions.poison(p, PoisonKind.Green, 10_000, 1, m.id)
        s.run(9500)
        val (hp1, mp1) = (p.hp, p.mp)
        s.run(3000)
        assertTrue(hp1 < 50_000, mp1 == 0, p.hp > hp1, p.mp > 0)
      }
    ),
    suite("boss 日志（#1277）")(
      test("刷怪点刷出 boss 报一条刷出；被打死报死亡（归属、最后一下、下次刷新时刻）；bossSpots 看得到活着和死了几点刷") {
        val map    = catalog.map(MapId("D2079")).get
        val w      = world(map)
        val p      = join(w, "甲", Cell(10, 10))
        w.tick(Millis(50))
        val born   = w.flush().chronicle
        val boss   = w.monsters.find(_.defn.name == MonsterName("牛魔王")).get
        val spot   = w.bossSpots.find(_.monster == MonsterName("牛魔王")).get
        val minutes = Rates.respawnMinutes(MonsterName("牛魔王"), catalog.data.spawns.find(s => s.map == map.id && s.monster == MonsterName("牛魔王")).get.minutes)
        w.vitals.damage(boss, boss.hp, Some(p.id))
        val died   = w.flush().chronicle
        val after  = w.bossSpots.find(_.monster == MonsterName("牛魔王")).get
        assertTrue(
          born.contains(Chronicle.BossSpawned(MonsterName("牛魔王"), boss.cell)),
          !born.exists { case Chronicle.BossSpawned(m, _) => !Rates.bosses(m); case _ => true }, // 小怪不报
          spot.alive == Vector(BossAlive(boss.cell, boss.maxHp, boss.maxHp)),
          spot.nextRefill.contains(Millis(50 + minutes * 60_000L)),
          died == Vector(Chronicle.BossSlain(MonsterName("牛魔王"), boss.cell, Some("甲"), Some("甲"), Some(Millis(50 + minutes * 60_000L)))),
          after.alive.isEmpty,
          after.nextRefill == spot.nextRefill
        )
      },
      test("幻境十层刷牛魔小怪和赤月恶魔、牛魔王、暗之牛魔王、暗之双头金刚、魔龙教主、暗之魔龙教主，打得死（#1317、#1329）") {
        val map   = catalog.map(MapId("H010")).get
        val w     = world(map)
        val p     = join(w, "甲", Cell(155, 157))
        w.tick(Millis(50))
        val names = w.monsters.map(_.defn.name.value).toSet
        val boss  = w.monsters.find(_.defn.name == MonsterName("暗之牛魔王")).get
        val dark  = w.monsters.find(_.defn.name == MonsterName("暗之魔龙教主")).get
        w.vitals.damage(boss, boss.hp, Some(p.id))
        assertTrue(
          Set("牛魔战士", "牛魔法师", "赤月恶魔", "牛魔王", "暗之牛魔王", "暗之双头金刚", "魔龙教主", "暗之魔龙教主").subsetOf(names),
          dark.maxHp == 32000,
          !names.exists(_.startsWith("宝箱")),
          boss.dead
        )
      },
      test("普通怪死了不报") {
        val s = sim()
        val p = join(s.w, "甲", Cell(30, 30))
        val m = put(s.w, "鸡", Cell(31, 30))
        s.w.vitals.damage(m, m.hp, Some(p.id))
        assertTrue(m.dead, s.w.flush().chronicle.isEmpty)
      }
    ),
    suite("地图文件")(
      test("Terrain.parseBin 读格子标记，坏文件报错") {
        val bytes = "M176".getBytes("US-ASCII") ++ Array[Byte](2, 0, 1, 0) ++ Array[Byte](0, 3)
        val t     = Terrain.parseBin(bytes)
        assertTrue(
          t.exists(t => t.width == 2 && t.walkable(Cell(0, 0)) && !t.walkable(Cell(1, 0)) && !t.flyable(Cell(1, 0)) && !t.walkable(Cell(2, 0))),
          Terrain.parseBin("XXXX".getBytes("US-ASCII")).isLeft,
          Terrain.parseBin(bytes.dropRight(1)).isLeft
        )
      }
    )
  )
