package mir.world

import mir.core.*
import mir.data.{ItemDef, MerchantService}
import mir.model.*
import mir.protocol.{GameCmd, InspectView, ServerMsg, ChatKind, Tone}
import mir.rules.{ItemKind, Pace, PotionRules, Pricing, Requirement, ScrollEffect, Skill}

/**
 * 玩家指令的执行：移动、攻击、施法、物品、NPC 买卖存取、设置、说话。
 * 一切都按服务端的状态判定：没有的物品、不够的金币、够不着的 NPC 都拒绝，回 Refused 说明原因。
 * 物品逻辑照 TS 版 world.ts（useItem/equip/unequip/pickUp）和 ui/classic/classic.ts（挪位置、买卖、存取）移植。
 */
object PlayerOps:
  import World.Slack

  def handle(w: World, p: Player, cmd: GameCmd): Unit = cmd match
    case GameCmd.Walk(from, dir) => step(w, p, from, dir, run = false)
    case GameCmd.Run(from, dir)  => step(w, p, from, dir, run = true)
    case GameCmd.Turn(dir)       => if !p.dead then w.grid.face(p, dir)
    case GameCmd.Attack(dir, target) =>
      if p.settings.stickyAttack then
        target.flatMap(w.actor).filter(w.canAttack(p, _)).foreach(t => p.intent = Intent.Hunt(t.id, once = false))
      report(w, p, Combat.attack(w, p, dir, Slack))
    case GameCmd.Cast(skill, target, cell) =>
      report(w, p, Combat.cast(w, p, skill, SpellTarget(cell, target.flatMap(w.actor)), Slack))
    case GameCmd.SetSword(art, on) => Combat.setSword(w, p, art, on)
    case GameCmd.TalkTo(npc) =>
      npcFor(w, p, npc, _ => true).fold(w.out.refuse(p, _), n => w.out.tell(p, ServerMsg.NpcOpened(n.id, n.ref)))
    case GameCmd.PickUp =>
      if !p.dead then pickUp(w, p): Unit
    case GameCmd.Revive =>
      if p.dead then w.vitals.revivePlayer(p) else w.out.refuse(p, "你还活着")
    case GameCmd.Teleport(dest) =>
      // 传送菜单：任意地方直接传到目的地（用户 #1139 定，不要钱、不看地图禁传标记）
      w.catalog.teleport(dest) match
        case None              => w.out.refuse(p, "没有这个目的地")
        case Some(_) if p.dead => w.out.refuse(p, "你已经死了")
        case Some(t)           => w.roster.transfer(p, Place(t.map, t.at))
    case GameCmd.UseItem(uid)           => result(w, p, useItem(w, p, uid))
    case GameCmd.Equip(uid, slot)       => result(w, p, equip(w, p, uid, slot))
    case GameCmd.Unequip(slot)          => result(w, p, unequip(w, p, slot, None))
    case GameCmd.MoveItem(uid, to)      => result(w, p, moveItem(w, p, uid, to))
    case GameCmd.DropItem(uid)          => result(w, p, dropItem(w, p, uid))
    case GameCmd.QuickSell(uid, n)      => result(w, p, quickSell(w, p, uid, n))
    case GameCmd.Buy(npc, item)         => result(w, p, buy(w, p, npc, item))
    case GameCmd.Sell(npc, uid, n)      => result(w, p, sell(w, p, npc, uid, n))
    case GameCmd.Deposit(npc, uid, n)   => result(w, p, deposit(w, p, npc, uid, n))
    case GameCmd.Withdraw(npc, uid, n)  => result(w, p, withdraw(w, p, npc, uid, n))
    case GameCmd.DealTry(partner)       => w.trades.request(p, partner)
    case GameCmd.DealPut(uid, n)        => w.trades.put(p, uid, n)
    case GameCmd.DealTake(uid)          => w.trades.take(p, uid)
    case GameCmd.DealGold(amount)       => w.trades.gold(p, amount)
    case GameCmd.DealConfirm            => w.trades.confirm(p)
    case GameCmd.DealCancel             => w.trades.cancel(p)
    case GameCmd.SetSkillKey(skill, key) => result(w, p, setSkillKey(p, skill, key))
    case GameCmd.UpdateSettings(s) =>
      p.settings = s.copy(autoHpPercent = s.autoHpPercent.max(0).min(100), autoMpPercent = s.autoMpPercent.max(0).min(100))
    case GameCmd.Say(text) => say(w, p, text)
    case GameCmd.Inspect(target) =>
      inspect(w, p, target).fold(w.out.refuse(p, _), v => w.out.tell(p, ServerMsg.Inspected(v)))

  private def report(w: World, p: Player, o: Outcome): Unit = o match
    case Outcome.Refused(why) => w.out.refuse(p, why)
    case Outcome.Done | Outcome.Busy => ()

  /** 物品、NPC 操作：Left 是不行的原因。 */
  private type Result = Either[String, Unit]
  private val ok: Result = Right(())

  private def result(w: World, p: Player, r: Result): Unit = r.left.foreach(w.out.refuse(p, _))

  // ---------------- 查看别人装备 ----------------

  /**
   * 查看另一个玩家身上的装备（#1263，照原版 ClientQueryUserState：对方要在身边视野里，CretInNearXY）：
   * 同图、视野内（World.ViewRange）、活着的别的玩家；只给穿戴中的，不给包裹。
   */
  private[world] def inspect(w: World, p: Player, target: ActorId): Either[String, InspectView] =
    for
      t <- w.player(target).filter(t => (t ne p) && p.cell.chebyshev(t.cell) <= World.ViewRange).toRight("看不到这个玩家")
      _ <- Either.cond(!t.dead, (), s"${t.charName}已经死了")
    yield InspectView(t.charName, t.sex, t.hair, t.standing, t.worn.toMap)

  // ---------------- 移动 ----------------

  /** 原版做法：客户端带上起点格，对得上、走得通就回 MoveOk，否则回 MoveDenied 把他拉回服务端的位置。 */
  private def step(w: World, p: Player, from: Cell, dir: Dir, run: Boolean): Unit =
    val n     = if run then 2 else 1
    val clear = (1 to n).forall(k => w.walkableFor(p)(p.cell.step(dir, k)))
    if p.dead || from != p.cell || w.now + Slack < p.busyUntil || !clear then
      w.out.tell(p, ServerMsg.MoveDenied(p.cell, p.dir))
    else
      w.out.tell(p, ServerMsg.MoveOk)
      p.intent = Intent.Idle
      p.manualUntil = w.now + 1000
      w.grid.move(p, dir, run): Unit

  // ---------------- 身上的物品 ----------------

  private def defOf(w: World, it: ItemInstance): Either[String, ItemDef] = w.catalog.item(it.name).toRight("数据表里没有这件物品")

  import Inventory.{at, put}

  private def carriedOrWhy(p: Player, uid: ItemUid): Either[String, (ItemPlace, ItemInstance)] =
    Inventory.carried(p, uid).toRight("包裹里没有这件物品")

  private def validPlace(place: ItemPlace): Boolean = place match
    case ItemPlace.Bag(i)  => i >= 0 && i < Capacity.bag
    case ItemPlace.Belt(i) => i >= 0 && i < Capacity.belt
    case ItemPlace.Worn(_) => true

  private def onBelt(k: ItemKind): Boolean = Inventory.beltable(k)

  /** 放到身上：同名可叠的并进已有的一叠，药品、卷轴优先放快捷栏空位；放不下返回 false。 */
  private[world] def addToBag(w: World, p: Player, it: ItemInstance): Boolean = Inventory.add(w.catalog, p, it)

  /** 再拿一件（或一叠）这种东西：只看格子，不限重量（用户 11-23 定）。 */
  private[world] def fits(p: Player, d: ItemDef): Result =
    Either.cond(Inventory.hasRoom(p, d), (), "包裹满了")

  /** 用掉一个（一叠只减一）。 */
  private def consumeOne(w: World, p: Player, uid: ItemUid): Unit =
    Inventory.take(p, uid, Some(1), () => w.newUid()): Unit

  /** 拿走 n 个（None = 整件）。 */
  private def takeOwned(w: World, p: Player, uid: ItemUid, n: Option[Int]): Either[String, ItemInstance] =
    Inventory.take(p, uid, n, () => w.newUid()).toRight("包裹里没有这件物品")

  /** 提示里的叫法：一叠写成「金创药(小量) ×5」。 */
  private def label(it: ItemInstance): String = if it.count > 1 then s"${it.name.value} ×${it.count}" else it.name.value

  /** p 现在捡不捡得起 g：怪掉的保护期内只有归属者和他的队友行（Ward，#1158）。 */
  def mayPick(w: World, p: Player, g: GroundItem): Boolean =
    g.ward.forall(_.admits(p.charName, w.company.mates(p.charName), w.now))

  /**
   * 捡起脚下的东西（金币直接加）；`take` 挑捡哪些，手动捡取全捡，自动捡物只捡勾了的种类。
   * 别人的保护期内的跳过，一件都没捡到时照原版提示。
   */
  def pickUp(w: World, p: Player, take: Loot => Boolean = _ => true): Boolean =
    val (here, warded) = w.groundItems.filter(g => g.cell == p.cell && take(g.loot)).toVector.partition(mayPick(w, p, _))
    val got = here.count { g =>
      g.loot match
        case Loot.Coins(amount) =>
          w.out.selfSound(p, Sounds.money)
          p.gold = p.gold + amount
          w.out.notice(p, s"捡到金币 ${amount.value}", Tone.Item)
          w.removeGround(g.id)
          true
        case Loot.Item(it) =>
          w.out.selfSound(p, Sounds.itemClick)
          defOf(w, it).flatMap(fits(p, _)) match
            case Left(why) =>
              w.out.notice(p, why, Tone.Bad)
              false
            case Right(()) =>
              addToBag(w, p, it)
              w.out.notice(p, s"捡到 ${label(it)}", Tone.Item)
              w.removeGround(g.id)
              true
    }
    if got == 0 && warded.nonEmpty then w.out.notice(p, "在一定时间以内无法捡起此物品！", Tone.Bad)
    got > 0

  /** 双击物品：喝药、读书、用卷轴、穿装备、拆药包。 */
  def useItem(w: World, p: Player, uid: ItemUid): Result =
    for
      _          <- Either.cond(!p.dead, (), "你已经死了")
      (place, it) <- carriedOrWhy(p, uid)
      d          <- defOf(w, it)
      r          <- ItemKind.of(d) match
        case ItemKind.Potion(hp, mp, instant) =>
          consumeOne(w, p, uid)
          w.out.selfSound(p, Sounds.eatDrug)
          if instant then
            val h = w.vitals.heal(p, hp, mp)
            if h > 0 then w.out.nearby(p.cell, ServerMsg.Heal(p.id, h))
          else
            // 普通药：分几跳慢慢回（PotionRules）
            p.potions = p.potions :+ PotionCourse(PotionRules.perTick(hp), PotionRules.perTick(mp), PotionRules.Ticks, w.now)
          ok
        case ItemKind.Bundle(_) =>
          w.catalog.bundleContent(d) match
            case None => Left("这个包拆不开")
            case Some(one) =>
              // 拆出的几个并成一叠：身上已有这种药就并进去；没有就要一个空位（这包是最后一个时它腾出的格子也算）
              if it.count > 1 && !Inventory.hasRoom(p, one) then Left("包裹满了，拆包需要一个空位")
              else
                consumeOne(w, p, uid)
                w.newItem(one.name).foreach(x => addToBag(w, p, x.copy(count = ItemKind.BundleSize)))
                ok
        case ItemKind.Scroll(ScrollEffect.Town | ScrollEffect.Escape) =>
          consumeOne(w, p, uid)
          w.roster.transfer(p, p.home)
          ok
        case ItemKind.Scroll(ScrollEffect.Random) =>
          // EatUseItems 2：带 NORANDOMMOVE 的图用不了，卷轴不消耗
          w.randomSpot(using w.rngOf).map { c =>
            consumeOne(w, p, uid)
            w.roster.transfer(p, Place(w.mapId, c))
          }
        case ItemKind.Scroll(ScrollEffect.Other) => Left("这个暂时用不了")
        case ItemKind.Book(_, level) =>
          for
            skill <- w.catalog.skillsByName.get(d.name.value).toRight("没有这个技能")
            _     <- Either.cond(skill.job == p.job, (), "你的职业学不了这个技能")
            _     <- Either.cond(p.level >= level, (), s"需要 $level 级才能学")
            _     <- Either.cond(p.skill(skill.id).isEmpty, (), "已经学会了")
          yield
            consumeOne(w, p, uid)
            val key = Option.unless(Skill.isPassive(skill.id))((1 to 8).find(k => !p.skills.exists(_.key.contains(k)))).flatten
            p.skills += LearnedSkill(skill.id, 0, 0, key)
            w.vitals.recalc(p)
            w.out.notice(p, s"学会了 ${skill.name}", Tone.Good)
        case ItemKind.Equip(_) | ItemKind.Amulet(_) => equip(w, p, uid, None)
        case ItemKind.Material | ItemKind.Other     => Left("这个东西不能直接使用")
    yield r

  private def canWear(p: Player, d: ItemDef): Result =
    Requirement.refusal(d, p.level, p.stats, p.sex).toLeft(())

  /** 穿戴；prefer 指定左右手镯/戒指位。换下来的放回原处。 */
  def equip(w: World, p: Player, uid: ItemUid, prefer: Option[EquipSlot]): Result =
    for
      _          <- Either.cond(!p.dead, (), "你已经死了")
      (place, it) <- carriedOrWhy(p, uid)
      d          <- defOf(w, it)
      slots       = ItemKind.slots(ItemKind.of(d))
      _          <- Either.cond(slots.nonEmpty, (), "这个不能穿戴")
      _          <- canWear(p, d)
      _          <- Either.cond(prefer.forall(slots.contains), (), "不能放在这个位置")
    yield
      val slot = prefer.orElse(slots.find(s => !p.worn.contains(s))).getOrElse(slots.head)
      val old  = p.worn.get(slot)
      put(p, place, None)
      p.worn(slot) = it
      old.foreach { o =>
        place match
          case ItemPlace.Bag(_) => put(p, place, Some(o))
          case _                => if !addToBag(w, p, o) then put(p, place, Some(o))
      }
      w.vitals.recalc(p)

  /** 脱下：放到指定的空格；没指定就放回包裹（可叠的护身符、药粉并进同名那一叠，#1213），否则第一个空格。 */
  def unequip(w: World, p: Player, slot: EquipSlot, into: Option[ItemPlace]): Result =
    for
      it <- p.worn.get(slot).toRight("这个位置没有装备")
      _ <- into match
        case Some(dest) =>
          Either.cond(at(p, dest).isEmpty, (), "请放到空格子里").map { _ =>
            p.worn.remove(slot)
            put(p, dest, Some(it))
          }
        case None =>
          p.worn.remove(slot)
          Either.cond(addToBag(w, p, it), (), "包裹满了").left.map { why =>
            p.worn(slot) = it
            why
          }
    yield w.vitals.recalc(p)

  /** 在包裹、快捷栏、装备位之间挪（原版拖物品）。 */
  def moveItem(w: World, p: Player, uid: ItemUid, to: ItemPlace): Result =
    if !validPlace(to) then Left("没有这个格子")
    else
      to match
        case ItemPlace.Worn(slot) => equip(w, p, uid, Some(slot))
        case _ =>
          val wornSlot = p.worn.collectFirst { case (s, it) if it.uid == uid => s }
          wornSlot match
            case Some(slot) =>
              to match
                case ItemPlace.Belt(_) => Left("快捷栏只能放药品和卷轴")
                case _                 => unequip(w, p, slot, Some(to))
            case None =>
              for
                (from, it) <- carriedOrWhy(p, uid)
                d          <- defOf(w, it)
                target      = at(p, to)
                _          <- swappable(w, from, to, d, target)
              yield target match
                // 一叠拖到同名的另一叠上：并成一叠（#1159）
                case Some(t) if t.uid != it.uid && t.name == it.name && ItemKind.stacks(d) =>
                  put(p, from, None)
                  put(p, to, Some(t.copy(count = t.count + it.count)))
                case _ =>
                  put(p, from, target)
                  put(p, to, Some(it))

  /** 快捷栏只放药品和卷轴：放上去的和换下来放回快捷栏的都要是。 */
  private def swappable(w: World, from: ItemPlace, to: ItemPlace, d: ItemDef, target: Option[ItemInstance]): Result =
    def beltOk(it: Option[ItemInstance]) = it.forall(t => w.catalog.item(t.name).map(ItemKind.of).exists(onBelt))
    (from, to) match
      case (_, ItemPlace.Belt(_)) if !onBelt(ItemKind.of(d)) => Left("快捷栏只能放药品和卷轴")
      case (ItemPlace.Belt(_), _) if !beltOk(target)        => Left("请放到空格子里")
      case _                                               => ok

  /** 扔到脚下。 */
  def dropItem(w: World, p: Player, uid: ItemUid): Result =
    for
      _          <- Either.cond(!p.dead, (), "你已经死了")
      (place, it) <- carriedOrWhy(p, uid)
    yield
      put(p, place, None)
      w.dropAt(p.cell, Loot.Item(it))

  // ---------------- NPC ----------------

  private def npcFor(w: World, p: Player, id: ActorId, service: MerchantService => Boolean): Either[String, Npc] =
    for
      n <- w.actor(id).collect { case n: Npc => n }.toRight("找不到这个 NPC")
      _ <- Either.cond(!p.dead, (), "你已经死了")
      _ <- Either.cond(p.cell.chebyshev(n.cell) <= Pace.NpcRange, (), "离得太远了")
      _ <- Either.cond(service(n.defn.service), (), s"${n.defn.name}不做这个")
    yield n

  def buy(w: World, p: Player, npc: ActorId, name: ItemName): Result =
    for
      n <- npcFor(w, p, npc, _ == MerchantService.Shop)
      _ <- Either.cond(n.defn.goods.exists(_.name == name), (), s"${n.defn.name}不卖这个")
      d <- w.catalog.item(name).toRight("数据表里没有这件物品")
      _ <- Either.cond(p.gold.value >= Pricing.buy(d), (), "金币不够")
      _ <- fits(p, d)
      it <- w.newItem(name).toRight("数据表里没有这件物品")
    yield
      addToBag(w, p, it)
      p.gold = p.gold - Gold(Pricing.buy(d))
      w.out.notice(p, s"买了 ${d.name.value}，花费 ${Pricing.buy(d)} 金币", Tone.Item)

  /** 卖给商店：只收这家店经营的种类。 */
  def sell(w: World, p: Player, npc: ActorId, uid: ItemUid, n: Option[Int] = None): Result =
    for
      shop <- npcFor(w, p, npc, _ == MerchantService.Shop)
      r    <- sellOff(w, p, uid, n, d =>
        Either.cond(shop.defn.itemModes.isEmpty || shop.defn.itemModes.contains(d.mode), (), s"${shop.defn.name}不收这种东西"))
    yield r

  /** 随时出售（#1178）：不用找 NPC，什么都收，价钱同商店。 */
  def quickSell(w: World, p: Player, uid: ItemUid, n: Option[Int] = None): Result =
    for
      _ <- Either.cond(!p.dead, (), "你已经死了")
      r <- sellOff(w, p, uid, n, _ => ok)
    yield r

  /**
   * 卖出身上（包裹或快捷栏，穿着的不算）一件的 n 个（None = 整叠），价钱 Pricing.sell(d, n)（护身符、药粉按次折算）。
   * accepts：收不收这种东西；一文不值的不收（免得白白没了）。
   */
  private def sellOff(w: World, p: Player, uid: ItemUid, n: Option[Int], accepts: ItemDef => Result): Result =
    for
      (_, it) <- carriedOrWhy(p, uid)
      d       <- defOf(w, it)
      _       <- accepts(d)
      _       <- Either.cond(Pricing.sell(d) > 0, (), s"${d.name.value}不值钱，卖不了")
      sold    <- takeOwned(w, p, uid, n)
    yield
      val price = Pricing.sell(d, sold.count)
      p.gold = p.gold + Gold(price)
      w.out.selfSound(p, Sounds.money)
      w.out.notice(p, s"卖出 ${label(sold)}，得到 $price 金币", Tone.Item)

  /** 存进仓库一件的 n 个（None = 整叠），并进同名一叠。 */
  def deposit(w: World, p: Player, npc: ActorId, uid: ItemUid, n: Option[Int] = None): Result =
    for
      _  <- npcFor(w, p, npc, _ == MerchantService.Storage)
      it <- takeOwned(w, p, uid, n)
    yield
      Inventory.store(w.catalog, p.storage, it)
      w.out.notice(p, s"保管了 ${label(it)}", Tone.Item)

  /** 从仓库取回一件的 n 个（None = 整叠）。 */
  def withdraw(w: World, p: Player, npc: ActorId, uid: ItemUid, n: Option[Int] = None): Result =
    for
      _  <- npcFor(w, p, npc, _ == MerchantService.Storage)
      it <- p.storage.find(_.uid == uid).toRight("仓库里没有这件物品")
      d  <- defOf(w, it)
      _  <- fits(p, d)
      got = Inventory.unstore(p.storage, uid, n, () => w.newUid()).get
    yield
      addToBag(w, p, got)
      w.out.notice(p, s"取回 ${label(got)}", Tone.Item)

  // ---------------- 其他 ----------------

  private def setSkillKey(p: Player, id: SkillId, key: Option[Int]): Result =
    for
      i <- Some(p.skills.indexWhere(_.id == id)).filter(_ >= 0).toRight("还没学会这个技能")
      _ <- Either.cond(key.forall(k => k >= 1 && k <= 8), (), "快捷键只能是 F1–F8")
      _ <- Either.cond(!Skill.isPassive(id) || key.isEmpty, (), "被动技能不用设快捷键")
    yield
      key.foreach(k => p.skills.indices.foreach(j => if p.skills(j).key.contains(k) then p.skills(j) = p.skills(j).copy(key = None)))
      p.skills(i) = p.skills(i).copy(key = key)

  /** 说话：附近的人看到；「!」开头全图喊话。 */
  private def say(w: World, p: Player, raw: String): Unit =
    val text = raw.trim.take(100)
    if text.startsWith("!") then
      val msg = text.drop(1).trim
      if msg.nonEmpty then w.players.foreach(w.out.tell(_, ServerMsg.Chat(p.charName, msg, ChatKind.Shout)))
    else if text.nonEmpty then w.out.nearby(p.cell, ServerMsg.Chat(p.charName, text, ChatKind.Say))
