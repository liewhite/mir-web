package mir.client.net

import mir.client.assets.Assets
import mir.core.*
import mir.data.Catalog
import mir.protocol.*
import mir.rules.Rng
import mir.terrain.Terrain
import mir.world.*
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*
import zio.json.*

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js.typedarray.Int8Array

/**
 * 开发用的本地服务端（页面地址带 ?local）：在浏览器里直接跑共用的世界（mir.world.World），说和真服务端同一套协议，
 * 服务端没好或不想连线时也能验证客户端。指令和消息都过一遍 JSON 编解码，和走网络时一样。
 * 只有一个本地角色，不存档（刷新就没了）；换图时按需建目的地图的世界。
 */
final class LocalServer(catalog: Catalog, assets: Assets, listener: LinkEvent => Unit) extends Transport:
  import LocalServer.*

  private val worlds = mutable.HashMap.empty[MapId, Future[World]]
  private val uids   = UidSource.counter(1)
  private val rng    = Rng.seeded(System.currentTimeMillis())
  private var chars  = Vector.empty[(CharSummary, CharacterData)]
  /** 自己在哪张图、对象编号 */
  private var here   = Option.empty[(World, ActorId)]
  private var timer  = Option.empty[Int]

  // 和真服务端一样：连上后先告诉客户端登录的是谁
  dom.window.setTimeout(
    () =>
      listener(LinkEvent.Up)
      deliver(ServerMsg.loggedIn("本地", Release.of("")))
    ,
    0
  ): Unit

  private def clock: Millis = Millis(math.round(dom.window.performance.now()))

  def send(cmd: ClientCmd): Unit =
    // 过一遍编解码：和真服务端收到的一样
    val json = SessionCmd.clientCmd.encoder.encodeJson(cmd, None).toString
    SessionCmd.clientCmd.decoder.decodeJson(json) match
      case Left(err)            => dom.console.error("指令编码有误", err, json)
      case Right(s: SessionCmd) => session(s)
      case Right(g: GameCmd)    => here.foreach((w, id) => w.handle(id, g))
      case Right(s: SocialCmd)  => social(s)

  def close(): Unit = timer.foreach(dom.window.clearInterval)

  private def deliver(m: ServerMsg): Unit =
    m.toJson.fromJson[ServerMsg] match
      case Right(back) => listener(LinkEvent.Received(back))
      case Left(err)   => dom.console.error("消息编码有误", err)

  private def session(c: SessionCmd): Unit = c match
    case SessionCmd.Login(account, _) => deliver(ServerMsg.loggedIn(account, Release.of("")))
    case SessionCmd.ListChars         => deliver(ServerMsg.CharList(chars.map(_._1).toList))
    case SessionCmd.CreateChar(name, job, sex) =>
      val id = CharId(chars.size + 1L)
      val base = CharacterData.newbie(catalog, name, job, sex, uids)
      val strong = if hero then heroOf(base) else base
      val mined  = if miner then minerOf(strong) else strong
      val geared = if gear180 then gear180Of(mined) else mined
      val data   = startAt.fold(geared)(p => geared.copy(place = p))
      chars :+= CharSummary(id, name, job, sex, data.level) -> data
      deliver(ServerMsg.CharList(chars.map(_._1).toList))
    case SessionCmd.DeleteChar(id) =>
      chars = chars.filterNot(_._1.id == id)
      deliver(ServerMsg.CharList(chars.map(_._1).toList))
    case SessionCmd.EnterGame(id) =>
      chars.find(_._1.id == id) match
        case None => deliver(ServerMsg.Refused("没有这个角色"))
        case Some((_, data)) =>
          here.flatMap((w, me) => w.leave(me)).foreach(d => chars = chars.map(c => if c._1.id == id then c._1 -> d else c))
          here = None
          enter(chars.find(_._1.id == id).fold(data)(_._2), Carried())
    case SessionCmd.Ping(ms) => deliver(ServerMsg.Pong(ms, clock))
    case SessionCmd.Reloading => () // 本地服务端跟着页面一起刷新，没有要留的
    case SessionCmd.Loaded(_, _) => () // 只给线上记日志

  /** 本机只有一个角色：组队、私聊、好友照同一套规则跑（别人总是「不在线」、查无此人），换图不变。 */
  private val society = mir.social.Social()

  private def social(s: SocialCmd): Unit = for
    (w, id) <- here
    p       <- w.player(id)
  do
    val find = (n: String) => Option.when(n.equalsIgnoreCase(p.charName))(mir.social.Seen(p.charName, !p.isDead))
    society.handle(p.charName, s, mir.social.Directory.onlineOnly(find)).filter(_.to == p.charName).foreach(l => deliver(l.msg))

  /** 地址带 at=图,x,y（如 `?local&at=H006,83,27`，#1322，本机看某张图某处用）：新建的角色就出生在那里；写错了照常出生。 */
  private def startAt: Option[Place] =
    Option(new dom.URLSearchParams(dom.window.location.search).get("at")).map(_.split(',')).collect {
      case Array(m, x, y) if x.toIntOption.isDefined && y.toIntOption.isDefined => Place(MapId(m), Cell(x.toInt, y.toInt))
    }

  /** 地址带 &hero：新建的角色 40 级、学会本职业全部技能（3 级，前 8 个按 F1–F8）、左手镯戴护身符、包裹里放灰黄药粉（本机看技能效果用）。 */
  private def hero: Boolean = dom.window.location.search.contains("hero")

  private def heroOf(d: CharacterData): CharacterData =
    val skills = catalog.data.skills.filter(_.job == d.job).zipWithIndex.map { (s, i) =>
      mir.model.LearnedSkill(s.id, 3, 0, Option.when(i < 8)(i + 1))
    }
    val charm = catalog.item(ItemName("护身符")).map(it => mir.model.ItemInstance(uids.next(), it.name, 100_000, 100_000))
    val powders = List("灰色药粉(大量)", "黄色药粉(大量)").flatMap(n => catalog.item(ItemName(n))).map(it => mir.model.ItemInstance(uids.next(), it.name, 100_000, 100_000))
    val free    = d.bag.indices.filter(d.bag(_).isEmpty)
    val bag     = free.zip(powders).foldLeft(d.bag)((b, p) => b.updated(p._1, Some(p._2)))
    d.copy(level = 40, hp = Hp(99_999), mp = Mp(99_999), skills = skills.toList, bag = bag, worn = d.worn ++ charm.map(mir.model.EquipSlot.BraceletL -> _))

  /**
   * 地址带 &miner（#1286，本机看挖矿、武器升级用）：新建的角色包裹里放鹤嘴锄、炼狱、几块不同纯度的黑铁矿石、
   * 力量戒指和绿色项链，5 万金币。
   */
  private def miner: Boolean = dom.window.location.search.contains("miner")

  private def minerOf(d: CharacterData): CharacterData =
    def one(n: String, dura: Option[Int] = None) =
      catalog.item(ItemName(n)).map(it => mir.model.ItemInstance.fresh(uids.next(), it)).map(i => dura.fold(i)(x => i.copy(dura = x)))
    val kit = List(one("鹤嘴锄"), one("炼狱"), one("力量戒指"), one("绿色项链")) ++
      List(17000, 15000, 12000, 9000).map(p => one("黑铁矿石", Some(p)))
    val free = d.bag.indices.filter(d.bag(_).isEmpty)
    d.copy(gold = Gold(50_000), bag = free.zip(kit.flatten).foldLeft(d.bag)((b, p) => b.updated(p._1, Some(p._2))))

  /**
   * 地址带 &gear180（#1312，本机看 1.80 新装备的图标、外观、说明用）：新建的角色 60 级，穿本职业的雷霆/烈焰/光芒整套和终极武器，
   * 包裹里放三职业其余 1.80 装备（放得下的部分）。
   */
  private def gear180: Boolean = dom.window.location.search.contains("gear180")

  private def gear180Of(d: CharacterData): CharacterData =
    import mir.model.{EquipSlot, Job, Sex}
    def one(n: String) = catalog.item(ItemName(n)).map(it => mir.model.ItemInstance.fresh(uids.next(), it))
    val (set, weapon) = d.job match
      case Job.Warrior => ("雷霆", "开天")
      case Job.Wizard  => ("烈焰", "镇天")
      case Job.Taoist  => ("光芒", "玄天")
    val armor = (d.job, d.sex) match
      case (Job.Warrior, Sex.Male) => "雷霆战甲(男)"
      case (Job.Warrior, _)        => "雷霆战甲(女)"
      case (Job.Wizard, Sex.Male)  => "烈焰魔衣(男)"
      case (Job.Wizard, _)         => "烈焰魔衣(女)"
      case (Job.Taoist, Sex.Male)  => "光芒道袍(男)"
      case (Job.Taoist, _)         => "光芒道袍(女)"
    val gear = catalog.data.items.filter(it => it.id >= 2000).map(_.name.value)
    val worn = List(EquipSlot.Dress -> armor, EquipSlot.Weapon -> weapon) ++
      gear.filter(n => n.startsWith(set)).flatMap { n =>
        catalog.item(ItemName(n)).map(mir.rules.ItemKind.of).collect { case mir.rules.ItemKind.Equip(s :: _) => s -> n }
      }.filterNot((s, _) => s == EquipSlot.Dress)
    val wornMap = worn.flatMap((s, n) => one(n).map(s -> _)).toMap
    val rest    = gear.filterNot(n => worn.exists(_._2 == n)).flatMap(one)
    val free    = d.bag.indices.filter(d.bag(_).isEmpty)
    d.copy(level = 60, bag = free.zip(rest).foldLeft(d.bag)((b, p) => b.updated(p._1, Some(p._2))), worn = d.worn ++ wornMap)

  private def enter(data: CharacterData, carried: Carried): Unit =
    world(data.place.map).recoverWith(_ => world(CharacterData.start.map)).foreach { w =>
      val place = if w.mapId == data.place.map then data.place else CharacterData.start
      here = Some(w -> w.join(data.copy(place = place), carried))
      if buddyOn then
        buddy = Some(w.join(CharacterData.newbie(catalog, BuddyName, mir.model.Job.Taoist, mir.model.Sex.Female, uids).copy(place = Place(w.mapId, place.cell))))
      society.online(data.name, data.social).foreach(l => deliver(l.msg))
      if timer.isEmpty then timer = Some(dom.window.setInterval(() => tick(), TickMs))
    }

  private def world(id: MapId): Future[World] =
    worlds.getOrElseUpdate(
      id,
      catalog.map(id) match
        case None => Future.failed(Exception(s"没有地图 ${id.value}"))
        case Some(defn) =>
          assets.bytes(s"map/${defn.asset}.bin").flatMap { buf =>
            Terrain.parseBin(new Int8Array(buf).toArray) match
              case Left(e)  => Future.failed(Exception(e))
              case Right(t) => Future.successful(World(catalog, defn, t, rng, uids, clock))
          }
    )

  /**
   * 地址带 &buddy：进图时身边放一个本地角色「木头人」（本机看交易窗口用）：你对它点交易它就同意，
   * 打开后它放 2 瓶小红和 10 金币，你确认后它也确认。换图就没了。
   */
  private def buddyOn: Boolean = dom.window.location.search.contains("buddy")
  private var buddy        = Option.empty[ActorId]

  private def actBuddy(w: World, me: ActorId, b: ActorId, msgs: Vector[ServerMsg]): Unit = msgs.foreach {
    case ServerMsg.Notice(text, _) if text.contains("想和你交易") => w.handle(b, GameCmd.DealTry(me))
    case ServerMsg.Deal(Some(d)) if d.mine.isEmpty && !d.theirs.confirmed =>
      w.player(b).flatMap(_ => w.snapshot(b)).flatMap(_.belt.flatten.headOption).foreach(it => w.handle(b, GameCmd.DealPut(it.uid, Some(2))))
      w.handle(b, GameCmd.DealGold(Gold(10)))
    case ServerMsg.Deal(Some(d)) if d.theirs.confirmed && !d.mine.confirmed => w.handle(b, GameCmd.DealConfirm)
    case _ => ()
  }

  private def tick(): Unit = here.foreach { (w, me) =>
    w.tick(clock)
    val out = w.flush()
    out.messages.get(me).foreach(_.foreach(deliver))
    buddy.filter(b => w.player(b).isDefined).foreach(b => out.messages.get(b).foreach(actBuddy(w, me, b, _)))
    out.departures.find(_.player == me).foreach { d =>
      here = None
      enter(d.data, d.carried)
    }
  }

object LocalServer:
  /** 本地世界多久推进一次 */
  val TickMs = 50
  val BuddyName = "木头人"
