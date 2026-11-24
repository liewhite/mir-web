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
      deliver(ServerMsg.LoggedIn("本地"))
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
    case SessionCmd.Login(account, _) => deliver(ServerMsg.LoggedIn(account))
    case SessionCmd.ListChars         => deliver(ServerMsg.CharList(chars.map(_._1).toList))
    case SessionCmd.CreateChar(name, job, sex) =>
      val id = CharId(chars.size + 1L)
      val base = CharacterData.newbie(catalog, name, job, sex, uids)
      val data = if hero then heroOf(base) else base
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

  /** 本机只有一个角色：组队照同一套规则跑（拉别人进组总是「不在线」），换图不变。 */
  private val groups = mir.social.Groups()

  private def social(s: SocialCmd): Unit = for
    (w, id) <- here
    p       <- w.player(id)
  do
    val find = (n: String) => Option.when(n.equalsIgnoreCase(p.charName))(mir.social.Seen(p.charName, !p.isDead))
    groups.handle(p.charName, s, find).filter(_.to == p.charName).foreach(l => deliver(l.msg))

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

  private def enter(data: CharacterData, carried: Carried): Unit =
    world(data.place.map).recoverWith(_ => world(CharacterData.start.map)).foreach { w =>
      val place = if w.mapId == data.place.map then data.place else CharacterData.start
      here = Some(w -> w.join(data.copy(place = place), carried))
      groups.online(data.name, data.social).foreach(l => deliver(l.msg))
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

  private def tick(): Unit = here.foreach { (w, me) =>
    w.tick(clock)
    val out = w.flush()
    out.messages.get(me).foreach(_.foreach(deliver))
    out.departures.find(_.player == me).foreach { d =>
      here = None
      enter(d.data, d.carried)
    }
  }

object LocalServer:
  /** 本地世界多久推进一次 */
  val TickMs = 50
