package mir.world

import mir.core.*
import mir.model.*
import mir.protocol.{DealOffer, DealView, ServerMsg, Tone}
import mir.rules.TradeRules

import scala.collection.mutable

/**
 * 玩家面对面交易（#1222，照原版 M2Server ObjPlay.pas ClientDealTry / ClientAddDealItem / ClientDelDealItem /
 * ClientChangeDealGold / ClientDealEnd / DealCancel）。交易双方必在同一张图上（要相邻才能开始），所以归世界管，
 * 成交时在同一个线程里一次换完两人的包裹和金币。
 *
 * 规则：
 * - 开始：两人相邻（TradeRules.OpenRange），一方点「交易」是请求，对方 30 秒内也对他点「交易」就打开窗口
 *   （原版要两人面对面、一方按交易键；手机上转向不方便，改成双方各点一次，同样是两边都同意才开始）。
 * - 交易栏是「指着包裹里的东西」：东西一直留在包裹里（存档、掉线都不会丢），交易栏记物品编号和个数。
 *   每次处理完指令、推进完时间都核对一遍（settle）：交易栏里的东西被用掉、扔掉、穿上、卖掉了就从栏里去掉，
 *   个数少了就跟着减，金币不够了就减到身上有的，有变化就取消双方的确认。
 * - 任一边改了交易栏，双方的确认都取消；对方 1 秒内改过就不能确认（原版 DealOKTime，防最后一刻换东西）。
 * - 双方都确认才成交：先核对一遍，再把两人的包裹和金币记下来，拿出双方的东西、互相放进去，
 *   任何一步放不下（包裹满、金币超上限）就整体恢复原样，交易栏留着、确认取消，告诉谁放不下。成交后要求立刻存档。
 * - 走开（超过 TradeRules.KeepRange 格）、死了、下线、换图、点取消：交易取消，东西本来就在各自包裹里。
 */
private[world] final class Trades(w: World):
  import Trades.*

  /** 请求交易的人 → 请求 */
  private val asks  = mutable.Map.empty[ActorId, Ask]
  /** 交易中的人（两边都登记） → 交易 */
  private val deals = mutable.Map.empty[ActorId, Deal]

  def dealing(p: Player): Boolean = deals.contains(p.id)

  // ---------------- 指令 ----------------

  /** 请求和 target 交易；target 已经在请求我就开始。 */
  def request(p: Player, target: ActorId): Unit =
    val check = for
      _ <- Either.cond(!p.dead, (), "你已经死了")
      _ <- Either.cond(!dealing(p), (), "你正在交易")
      t <- w.player(target).filter(_ ne p).toRight("只能和别的玩家交易")
      _ <- Either.cond(!t.dead, (), s"${t.charName}已经死了")
      _ <- Either.cond(!dealing(t), (), s"${t.charName}正在和别人交易")
      _ <- Either.cond(p.cell.chebyshev(t.cell) <= TradeRules.OpenRange, (), s"要站到${t.charName}旁边才能交易")
    yield t
    check match
      case Left(why) => w.out.refuse(p, why)
      case Right(t) =>
        if asks.get(t.id).exists(a => a.to == p.id && fresh(a)) then open(p, t)
        else
          val again = asks.get(p.id).exists(a => a.to == t.id && w.now - a.at < TradeRules.RepeatAskMs)
          if !again then
            asks(p.id) = Ask(t.id, w.now)
            w.out.notice(p, s"已向${t.charName}请求交易，等对方也对你点「交易」。", Tone.System)
            w.out.notice(t, s"${p.charName}想和你交易：锁定${p.charName}，站在旁边点「交易」。", Tone.Good)

  /** 放进交易栏（同一件再放就改个数）。 */
  def put(p: Player, uid: ItemUid, count: Option[Int]): Unit = withSide(p) { (d, me) =>
    Inventory.carried(p, uid) match
      case None => w.out.refuse(p, "包裹里没有这件物品")
      case Some((_, it)) =>
        val n    = count.fold(it.count)(_.max(1).min(it.count))
        val rest = me.stakes.filterNot(_.uid == uid)
        if rest.size >= TradeRules.MaxItems then w.out.refuse(p, s"交易栏最多放 ${TradeRules.MaxItems} 件")
        else
          me.stakes = rest :+ Stake(uid, n)
          changed(d, me)
  }

  /** 从交易栏拿回。 */
  def take(p: Player, uid: ItemUid): Unit = withSide(p) { (d, me) =>
    if !me.stakes.exists(_.uid == uid) then w.out.refuse(p, "交易栏里没有这件")
    else
      me.stakes = me.stakes.filterNot(_.uid == uid)
      changed(d, me)
  }

  /** 交易栏里放多少金币（原版 ClientChangeDealGold：改成这个数）。 */
  def gold(p: Player, amount: Gold): Unit = withSide(p) { (d, me) =>
    if amount.value < 0 then w.out.refuse(p, "金币数不对")
    else if amount.value > p.gold.value then w.out.refuse(p, s"你只有 ${p.gold.value} 金币")
    else if amount != me.gold then
      me.gold = amount
      changed(d, me)
  }

  /** 确认；对方也确认了就成交。 */
  def confirm(p: Player): Unit = withSide(p) { (d, me) =>
    val them = d.other(me)
    if w.now - them.changedAt < TradeRules.SettleMs then w.out.refuse(p, "对方刚改了交易栏，看清楚再确认")
    else if me.stakes.isEmpty && me.gold == Gold.zero && them.stakes.isEmpty && them.gold == Gold.zero then
      w.out.refuse(p, "交易栏是空的")
    else if !me.confirmed then
      me.confirmed = true
      if them.confirmed then commit(d)
      else
        w.out.notice(p, s"你已确认，等${them.p.charName}确认。", Tone.System)
        w.out.notice(them.p, s"${p.charName}已确认交易，你确认后就成交。", Tone.Good)
        sync(d)
  }

  def cancel(p: Player): Unit = withSide(p) { (d, _) => close(d, Some(s"${p.charName}取消了交易。")) }

  // ---------------- 核对 ----------------

  /**
   * 每次处理完指令、推进完时间调用：过期的请求去掉；交易中有人走开、死了、不在图上就取消；
   * 交易栏里的东西对不上包裹了就改过来并取消确认。
   */
  def settle(): Unit =
    asks.filterInPlace((from, a) => fresh(a) && w.player(from).exists(!_.dead) && w.player(a.to).isDefined)
    deals.values.toVector.distinct.foreach { d =>
      broken(d) match
        case Some(why) => close(d, Some(why))
        case None      => if refresh(d) then sync(d)
    }

  private def fresh(a: Ask): Boolean = w.now - a.at <= TradeRules.AskMs

  /** 交易还能不能继续：不能的原因。 */
  private def broken(d: Deal): Option[String] =
    d.sides.collectFirst {
      case s if !w.present(s.p) => s"${s.p.charName}离开了，交易取消。"
      case s if s.p.dead        => s"${s.p.charName}死了，交易取消。"
    }.orElse(Option.when(d.a.p.cell.chebyshev(d.b.p.cell) > TradeRules.KeepRange)("离得太远，交易取消。"))

  /** 交易栏按包裹现在的样子改过来；有变化（东西没了、少了、金币不够）就取消双方确认、提示并返回 true。 */
  private def refresh(d: Deal): Boolean =
    val moved = d.sides.filter { s =>
      val stakes = s.stakes.flatMap(st => Inventory.carried(s.p, st.uid).map((_, it) => st.copy(count = st.count.min(it.count))))
      val gold   = if s.gold.value > s.p.gold.value then s.p.gold else s.gold
      val diff   = stakes != s.stakes || gold != s.gold
      if diff then
        s.stakes = stakes
        s.gold = gold
        s.changedAt = w.now
      diff
    }
    moved.foreach(s => d.sides.foreach(x => w.out.notice(x.p, s"${s.p.charName}交易栏里的东西和包裹对不上了（用掉、扔掉、穿上或花掉了），已按包裹改过，请重新确认。", Tone.Bad)))
    if moved.nonEmpty then d.sides.foreach(_.confirmed = false)
    moved.nonEmpty

  // ---------------- 内部 ----------------

  private def withSide(p: Player)(f: (Deal, Side) => Unit): Unit =
    deals.get(p.id) match
      case None    => w.out.refuse(p, "你没有在交易")
      case Some(d) => f(d, d.side(p))

  private def open(p: Player, t: Player): Unit =
    asks.remove(p.id)
    asks.remove(t.id)
    val d = Deal(Side(t, w.now), Side(p, w.now))
    deals(p.id) = d
    deals(t.id) = d
    d.sides.foreach(s => w.out.notice(s.p, s"开始和${d.other(s).p.charName}交易。", Tone.Good))
    sync(d)

  /** 一边改了交易栏：双方确认取消，记下改的时刻。 */
  private def changed(d: Deal, me: Side): Unit =
    me.changedAt = w.now
    d.sides.foreach(_.confirmed = false)
    sync(d)

  private def close(d: Deal, why: Option[String]): Unit =
    d.sides.foreach { s =>
      deals.remove(s.p.id)
      if w.present(s.p) then
        w.out.tell(s.p, ServerMsg.Deal(None))
        why.foreach(w.out.notice(s.p, _, Tone.Bad))
    }

  /** 双方都看到交易窗口现在的样子。 */
  private def sync(d: Deal): Unit =
    d.sides.foreach { s =>
      val them = d.other(s)
      w.out.tell(s.p, ServerMsg.Deal(Some(DealView(them.p.charName, offer(s), offer(them)))))
    }

  private def offer(s: Side): DealOffer =
    DealOffer(s.stakes.flatMap(st => Inventory.carried(s.p, st.uid).map((_, it) => it.copy(count = st.count))).toList, s.gold, s.confirmed)

  /**
   * 成交：先按包裹核对（有变化就不成交、要重新确认），再整体换；任何一边放不下就恢复两人原来的包裹和金币。
   */
  private def commit(d: Deal): Unit =
    if refresh(d) then sync(d)
    else
      val saved = d.sides.map(s => s -> Pocket.of(s.p))
      // 拿出各自放的东西（刚核对过，个数都够）；拆开一叠时拿出的那份换新编号
      val outgoing = d.sides.map(s => s -> s.stakes.flatMap(st => Inventory.take(s.p, st.uid, Some(st.count), () => w.newUid())))
      d.sides.foreach(s => s.p.gold = s.p.gold - s.gold)
      val short = d.sides.filterNot { s =>
        val them = d.other(s)
        val incoming = outgoing.collectFirst { case (x, items) if x eq them => items }.getOrElse(Vector.empty)
        s.p.gold.value.toLong + them.gold.value <= TradeRules.GoldMax &&
        incoming.forall(Inventory.add(w.catalog, s.p, _))
      }
      if short.nonEmpty then
        saved.foreach((s, pocket) => pocket.restore(s.p))
        d.sides.foreach(_.confirmed = false)
        short.foreach { s =>
          w.out.notice(s.p, "你的包裹放不下对方的东西（或金币太多），交易没有成功。", Tone.Bad)
          w.out.notice(d.other(s).p, s"${s.p.charName}的包裹放不下（或金币太多），交易没有成功。", Tone.Bad)
        }
        sync(d)
      else
        d.sides.foreach { s =>
          s.p.gold = s.p.gold + d.other(s).gold
          w.out.selfSound(s.p, Sounds.money)
          w.out.checkpoint(s.p)
        }
        close(d, None)
        d.sides.foreach(s => w.out.notice(s.p, s"和${d.other(s).p.charName}交易成功。", Tone.Good))

private[world] object Trades:
  /** 一个交易请求：向谁、什么时候 */
  final case class Ask(to: ActorId, at: Millis)

  /** 交易栏里的一件：包裹里哪件（编号）、放几个 */
  final case class Stake(uid: ItemUid, count: Int)

  /** 交易的一边。 */
  final class Side(val p: Player, var changedAt: Millis):
    var stakes: Vector[Stake] = Vector.empty
    var gold: Gold            = Gold.zero
    var confirmed: Boolean    = false

  final class Deal(val a: Side, val b: Side):
    def sides: List[Side]         = List(a, b)
    def side(p: Player): Side     = if a.p eq p then a else b
    def other(s: Side): Side      = if s eq a then b else a

  /** 一个人包裹、快捷栏、金币的样子（成交失败时恢复用）。 */
  final case class Pocket(bag: Vector[Option[ItemInstance]], belt: Vector[Option[ItemInstance]], gold: Gold):
    def restore(p: Player): Unit =
      bag.indices.foreach(i => p.bag(i) = bag(i))
      belt.indices.foreach(i => p.belt(i) = belt(i))
      p.gold = gold

  object Pocket:
    def of(p: Player): Pocket = Pocket(p.bag.toVector, p.belt.toVector, p.gold)
