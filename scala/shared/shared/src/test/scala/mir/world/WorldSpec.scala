package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.Rng
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
      test("两人同图互相看见，视野副本与世界一致") {
        val s = sim()
        val a = join(s.w, "甲", Cell(30, 30))
        val b = join(s.w, "乙", Cell(33, 30))
        s.flush()
        val va = s.views(a.id)
        val vb = s.views(b.id)
        assertTrue(
          va.actors.contains(b.id), vb.actors.contains(a.id),
          va.actors(b.id).look == Look.Human("乙", Job.Warrior, Sex.Male, 1, Some(ItemName("木剑")), Some(ItemName("布衣(男)"))),
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
        val swords = p.bag.flatten.filter(_.name == sword).map(_.count).toList
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
        s.cmd(p, GameCmd.Unequip(EquipSlot.Weapon))
        val noWeapon = !p.worn.contains(EquipSlot.Weapon) && s.views(p.id).worn.get(EquipSlot.Weapon).isEmpty
        val sword = p.bag.flatten.find(_.name == ItemName("木剑")).get
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
