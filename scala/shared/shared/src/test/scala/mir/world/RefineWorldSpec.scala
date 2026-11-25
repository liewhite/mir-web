package mir.world

import mir.core.*
import mir.data.*
import mir.model.*
import mir.protocol.*
import mir.rules.{Mining, Rng, WeaponRefine}
import mir.terrain.Terrain
import mir.testkit.TestFiles
import zio.test.*

/** 世界里的挖矿和武器店升级（#1286）。 */
object RefineWorldSpec extends ZIOSpecDefault:
  private lazy val catalog: Catalog =
    Catalog.decode(TestFiles.gameJson).fold(e => throw new AssertionError(e), identity)

  private final class Sim(map: MapDef, terrain: Terrain, seed: Long = 1):
    val w = World(catalog, map, terrain, Rng.seeded(seed), UidSource.counter(1_000_000), Millis(0))
    var t = 0L

    def cmd(p: Player, c: GameCmd): Vector[ServerMsg] =
      w.handle(p.id, c)
      w.flush().messages.getOrElse(p.id, Vector.empty)

    def join(name: String, at: Cell): Player =
      val d = CharacterData.newbie(catalog, name, Job.Warrior, Sex.Male, UidSource.counter(name.hashCode.abs.toLong * 1000))
      w.player(w.join(d.copy(place = Place(w.mapId, at)))).get

    def item(n: String): ItemInstance = w.newItem(ItemName(n)).get

    def bagItems(p: Player): Vector[ItemInstance] = p.bag.flatten.toVector

  private def refused(r: Vector[ServerMsg]): Boolean = r.exists { case ServerMsg.Refused(_) => true; case _ => false }

  // ---------------- 武器店 ----------------

  /** 盟重（沙巴克铁匠在这张图上） */
  private def town(seed: Long = 1) =
    val md = catalog.map(MapId("3")).get
    Sim(md, Terrain.open(md.width, md.height), seed)

  private def smith(s: Sim): Npc =
    s.w.actorsNow.collectFirst { case n: Npc if n.defn.service == MerchantService.WeaponUpgrade => n }.get

  /** 站在铁匠旁边、手拿炼狱、包裹里有黑铁、戒指、头盔、一瓶药，2 万金币 */
  private def ready(s: Sim): (Player, Npc, ItemInstance, Vector[ItemInstance]) =
    val n = smith(s)
    val p = s.join("甲", n.cell.step(Dir.Down))
    p.bag.indices.foreach(p.bag(_) = None)
    val sword = s.item("炼狱")
    p.worn(EquipSlot.Weapon) = sword
    val mats = Vector(s.item("黑铁矿石").copy(dura = 16000), s.item("黑铁矿石").copy(dura = 9000), s.item("力量戒指"), s.item("骷髅头盔"))
    (mats :+ s.item("金创药(小量)")).zipWithIndex.foreach((it, i) => p.bag(i) = Some(it))
    p.gold = Gold(20000)
    (p, n, sword, mats)

  def spec = suite("挖矿、武器店升级（#1286）")(
    test("升级：收 1 万金币和包裹里全部黑铁、首饰（不收药），手上的武器当场成功加点或碎掉") {
      val results = (1L to 30L).map { seed =>
        val s                     = town(seed)
        val (p, n, sword, mats)   = ready(s)
        val r                     = s.cmd(p, GameCmd.RefineWeapon(n.id, sword.uid, mats.map(_.uid).toSet))
        val left                  = s.bagItems(p).map(_.name.value)
        val ok = !refused(r) && p.gold == Gold(10000) && left == Vector("金创药(小量)")
        val outcome = p.worn.get(EquipSlot.Weapon) match
          case Some(w) => w.uid == sword.uid && WeaponRefine.points(w) >= 1
          case None    => true
        (ok && outcome, p.worn.contains(EquipSlot.Weapon))
      }
      assertTrue(
        results.forall(_._1),
        results.exists(_._2),  // 成功过
        results.exists(!_._2)  // 碎过
      )
    },
    test("包裹里的东西和确认时看到的不一样、武器换了、离太远、没黑铁：都拒绝，什么都不收") {
      val s                   = town()
      val (p, n, sword, mats) = ready(s)
      val before              = s.bagItems(p)
      val r1 = s.cmd(p, GameCmd.RefineWeapon(n.id, sword.uid, mats.drop(1).map(_.uid).toSet))
      val r2 = s.cmd(p, GameCmd.RefineWeapon(n.id, ItemUid(42), mats.map(_.uid).toSet))
      val far = s.join("乙", n.cell.step(Dir.Down, 10))
      far.worn(EquipSlot.Weapon) = s.item("木剑")
      far.bag(0) = Some(s.item("黑铁矿石"))
      far.gold = Gold(20000)
      val r3 = s.cmd(far, GameCmd.RefineWeapon(n.id, far.worn(EquipSlot.Weapon).uid, Set(far.bag(0).get.uid)))
      p.bag.indices.foreach(i => if p.bag(i).exists(_.name.value == "黑铁矿石") then p.bag(i) = None)
      val r4 = s.cmd(p, GameCmd.RefineWeapon(n.id, sword.uid, Set(mats(2).uid, mats(3).uid)))
      assertTrue(
        refused(r1), refused(r2), refused(r3), refused(r4),
        p.gold == Gold(20000), far.gold == Gold(20000),
        p.worn.get(EquipSlot.Weapon).contains(sword),
        s.bagItems(far).nonEmpty,
        before.filterNot(_.name.value == "黑铁矿石") == s.bagItems(p)
      )
    },
    // ---------------- 挖矿 ----------------
    test("矿区拿鹤嘴锄朝矿壁一直挖：挖出带纯度的矿石进包裹，锄头磨损；别的图挥空") {
      def mine(flags: Set[MapFlag]) =
        val md   = MapDef(MapId("MT"), "MT", "测试矿区", flags, 20, 20)
        val grid = new Array[Byte](20 * 20)
        grid(10 * 20 + 11) = 1 // (11,10) 是矿壁
        val s = Sim(md, Terrain.fromFlags(20, 20, grid))
        val p = s.join("甲", Cell(10, 10))
        p.bag.indices.foreach(p.bag(_) = None)
        val pick = s.item("鹤嘴锄")
        p.worn(EquipSlot.Weapon) = pick
        (1 to 3000).foreach { _ =>
          s.t += 1000
          s.w.tick(Millis(s.t))
          s.cmd(p, GameCmd.Attack(Dir.Right, None))
        }
        (p, pick)
      val (p, pick)   = mine(Set(MapFlag.Mine))
      val (q, pick2)  = mine(Set.empty)
      val ores        = p.bag.flatten.toVector
      val names       = Mining.Ore.values.map(_.item).toSet
      assertTrue(
        ores.nonEmpty,
        ores.forall(o => names.contains(o.name) && o.dura >= 3000 && o.dura < 26000),
        p.worn(EquipSlot.Weapon).dura < pick.dura,
        q.bag.flatten.isEmpty,
        q.worn(EquipSlot.Weapon).dura == pick2.dura
      )
    }
  )
