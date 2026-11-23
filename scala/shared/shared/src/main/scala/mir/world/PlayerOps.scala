package mir.world

import mir.core.*
import mir.data.{ItemDef, MerchantService}
import mir.model.*
import mir.protocol.{GameCmd, ServerMsg, ChatKind, Tone}
import mir.rules.{ItemKind, Requirement, ScrollEffect, dressSex}

/**
 * 玩家指令的执行：移动、攻击、施法、物品、NPC 买卖存取、设置、说话。
 * 一切都按服务端的状态判定：没有的物品、不够的金币、够不着的 NPC 都拒绝，回 Refused 说明原因。
 * 物品逻辑照 TS 版 world.ts（useItem/equip/unequip/pickUp）和 ui/classic/classic.ts（挪位置、买卖、存取）移植。
 */
object PlayerOps:
  import World.*

  /** 跟 NPC 说话、买卖要站在几格以内 */
  val NpcRange = 6

  def handle(w: World, p: Player, cmd: GameCmd): Unit = cmd match
    case GameCmd.Walk(from, dir) => step(w, p, from, dir, run = false)
    case GameCmd.Run(from, dir)  => step(w, p, from, dir, run = true)
    case GameCmd.Turn(dir)       => if !p.dead then w.face(p, dir)
    case GameCmd.Attack(dir, target) =>
      if p.settings.stickyAttack then
        target.flatMap(w.actor).filter(w.isEnemy(p, _)).foreach(t => p.intent = Intent.Hunt(t.id, once = false))
      report(w, p, Combat.attack(w, p, dir, Slack))
    case GameCmd.Cast(skill, target, cell) =>
      report(w, p, Combat.cast(w, p, skill, SpellTarget(cell, target.flatMap(w.actor)), Slack))
    case GameCmd.SetSword(art, on) => Combat.setSword(w, p, art, on)
    case GameCmd.TalkTo(npc) =>
      npcFor(w, p, npc, _ => true).fold(w.refuse(p, _), n => w.tell(p, ServerMsg.NpcOpened(n.id, n.ref)))
    case GameCmd.PickUp =>
      if !p.dead then pickUp(w, p): Unit
    case GameCmd.Revive =>
      if p.dead then w.revivePlayer(p) else w.refuse(p, "你还活着")
    case GameCmd.Teleport(dest) =>
      // 传送菜单：任意地方直接传到目的地（用户 #1139 定，不要钱、不看地图禁传标记）
      w.catalog.teleport(dest) match
        case None              => w.refuse(p, "没有这个目的地")
        case Some(_) if p.dead => w.refuse(p, "你已经死了")
        case Some(t)           => w.transfer(p, Place(t.map, t.at))
    case GameCmd.UseItem(uid)           => result(w, p, useItem(w, p, uid))
    case GameCmd.Equip(uid, slot)       => result(w, p, equip(w, p, uid, slot))
    case GameCmd.Unequip(slot)          => result(w, p, unequip(w, p, slot, None))
    case GameCmd.MoveItem(uid, to)      => result(w, p, moveItem(w, p, uid, to))
    case GameCmd.DropItem(uid)          => result(w, p, dropItem(w, p, uid))
    case GameCmd.Buy(npc, item)         => result(w, p, buy(w, p, npc, item))
    case GameCmd.Sell(npc, uid)         => result(w, p, sell(w, p, npc, uid))
    case GameCmd.Deposit(npc, uid)      => result(w, p, deposit(w, p, npc, uid))
    case GameCmd.Withdraw(npc, uid)     => result(w, p, withdraw(w, p, npc, uid))
    case GameCmd.SetSkillKey(skill, key) => result(w, p, setSkillKey(p, skill, key))
    case GameCmd.UpdateSettings(s) =>
      p.settings = s.copy(autoHpPercent = s.autoHpPercent.max(0).min(100), autoMpPercent = s.autoMpPercent.max(0).min(100))
    case GameCmd.Say(text) => say(w, p, text)

  private def report(w: World, p: Player, o: Outcome): Unit = o match
    case Outcome.Refused(why) => w.refuse(p, why)
    case Outcome.Done | Outcome.Busy => ()

  /** 物品、NPC 操作：Left 是不行的原因。 */
  private type Result = Either[String, Unit]
  private val ok: Result = Right(())

  private def result(w: World, p: Player, r: Result): Unit = r.left.foreach(w.refuse(p, _))

  // ---------------- 移动 ----------------

  /** 原版做法：客户端带上起点格，对得上、走得通就回 MoveOk，否则回 MoveDenied 把他拉回服务端的位置。 */
  private def step(w: World, p: Player, from: Cell, dir: Dir, run: Boolean): Unit =
    val n     = if run then 2 else 1
    val clear = (1 to n).forall(k => w.canEnter(p.cell.step(dir, k)))
    if p.dead || from != p.cell || w.now + Slack < p.busyUntil || !clear then
      w.tell(p, ServerMsg.MoveDenied(p.cell, p.dir))
    else
      w.tell(p, ServerMsg.MoveOk)
      p.intent = Intent.Idle
      p.manualUntil = w.now + 1000
      w.move(p, dir, run): Unit

  // ---------------- 身上的物品 ----------------

  private def defOf(w: World, it: ItemInstance): Either[String, ItemDef] = w.catalog.item(it.name).toRight("数据表里没有这件物品")

  /** 包裹或快捷栏里的一件物品和它的位置。 */
  private def carried(p: Player, uid: ItemUid): Option[(ItemPlace, ItemInstance)] =
    p.bag.indices.collectFirst { case i if p.bag(i).exists(_.uid == uid) => ItemPlace.Bag(i) -> p.bag(i).get }
      .orElse(p.belt.indices.collectFirst { case i if p.belt(i).exists(_.uid == uid) => ItemPlace.Belt(i) -> p.belt(i).get })

  private def carriedOrWhy(p: Player, uid: ItemUid): Either[String, (ItemPlace, ItemInstance)] =
    carried(p, uid).toRight("包裹里没有这件物品")

  private def at(p: Player, place: ItemPlace): Option[ItemInstance] = place match
    case ItemPlace.Bag(i)     => p.bag.lift(i).flatten
    case ItemPlace.Belt(i)    => p.belt.lift(i).flatten
    case ItemPlace.Worn(slot) => p.worn.get(slot)

  private def put(p: Player, place: ItemPlace, it: Option[ItemInstance]): Unit = place match
    case ItemPlace.Bag(i)     => p.bag(i) = it
    case ItemPlace.Belt(i)    => p.belt(i) = it
    case ItemPlace.Worn(slot) => it.fold(p.worn.remove(slot).foreach(_ => ()))(p.worn(slot) = _)

  private def validPlace(place: ItemPlace): Boolean = place match
    case ItemPlace.Bag(i)  => i >= 0 && i < Capacity.bag
    case ItemPlace.Belt(i) => i >= 0 && i < Capacity.belt
    case ItemPlace.Worn(_) => true

  private def onBelt(k: ItemKind): Boolean = k match
    case ItemKind.Potion(_, _, _) | ItemKind.Scroll(_) => true
    case _                                             => false

  /** 放进包裹；满了返回 false。药品、卷轴优先放快捷栏空位。 */
  private[world] def addToBag(w: World, p: Player, it: ItemInstance): Boolean =
    val belt = w.catalog.item(it.name).map(ItemKind.of).exists(onBelt)
    val slot = Option.when(belt)(p.belt.indexOf(None)).filter(_ >= 0).map(ItemPlace.Belt(_))
      .orElse(Some(p.bag.indexOf(None)).filter(_ >= 0).map(ItemPlace.Bag(_)))
    slot.foreach(put(p, _, Some(it)))
    slot.isDefined

  private def fits(w: World, p: Player, d: ItemDef): Result =
    if w.bagWeight(p) + d.weight > p.stats.maxBagWeight then Left("包裹太重了，拿不动")
    else if !p.bag.contains(None) && !(onBelt(ItemKind.of(d)) && p.belt.contains(None)) then Left("包裹满了")
    else ok

  /** 捡起脚下的东西（金币直接加）。 */
  def pickUp(w: World, p: Player): Boolean =
    val here = w.groundItems.filter(_.cell == p.cell).toVector
    here.count { g =>
      g.loot match
        case Loot.Coins(amount) =>
          w.selfSound(p, Sounds.money)
          p.gold = p.gold + amount
          w.notice(p, s"捡到金币 ${amount.value}", Tone.Item)
          w.removeGround(g.id)
          true
        case Loot.Item(it) =>
          w.selfSound(p, Sounds.itemClick)
          defOf(w, it).flatMap(fits(w, p, _)) match
            case Left(why) =>
              w.notice(p, why, Tone.Bad)
              false
            case Right(()) =>
              addToBag(w, p, it)
              w.notice(p, s"捡到 ${it.name.value}", Tone.Item)
              w.removeGround(g.id)
              true
    } > 0

  /** 双击物品：喝药、读书、用卷轴、穿装备、拆药包。 */
  def useItem(w: World, p: Player, uid: ItemUid): Result =
    for
      _          <- Either.cond(!p.dead, (), "你已经死了")
      (place, it) <- carriedOrWhy(p, uid)
      d          <- defOf(w, it)
      r          <- ItemKind.of(d) match
        case ItemKind.Potion(hp, mp, instant) =>
          put(p, place, None)
          w.selfSound(p, Sounds.eatDrug)
          if instant then
            val h = math.max(0, math.min(hp, p.stats.maxHp - p.hp))
            p.hp += h
            p.mp += math.max(0, math.min(mp, p.stats.maxMp - p.mp))
            if h > 0 then w.nearby(p.cell, ServerMsg.Heal(p.id, h))
          else
            // 普通药：分 5 跳慢慢回
            (0 until 5).foreach { i =>
              w.after(i * 400L) {
                if !p.dead then
                  p.hp += math.max(0, math.min(math.ceil(hp / 5.0).toInt, p.stats.maxHp - p.hp))
                  p.mp += math.max(0, math.min(math.ceil(mp / 5.0).toInt, p.stats.maxMp - p.mp))
              }
            }
          ok
        case ItemKind.Bundle(of) =>
          // 包的 Shape = 里面那种药的 AniCount
          w.catalog.data.items.find(i => i.mode != 31 && i.ani == of) match
            case None => Left("这个包拆不开")
            case Some(one) =>
              val free = p.bag.count(_.isEmpty) - 1 + p.belt.count(_.isEmpty)
              if free < 6 then Left("包裹空位不够，拆包需要 6 个空位")
              else
                put(p, place, None)
                (1 to 6).foreach(_ => w.newItem(one.name).foreach(addToBag(w, p, _)))
                ok
        case ItemKind.Scroll(ScrollEffect.Town | ScrollEffect.Escape) =>
          put(p, place, None)
          w.transfer(p, p.home)
          ok
        case ItemKind.Scroll(ScrollEffect.Random) =>
          put(p, place, None)
          val rng = w.rngOf
          Iterator.fill(50)(Cell(rng.random(w.terrain.width), rng.random(w.terrain.height))).find(w.canEnter)
            .foreach(c => w.transfer(p, Place(w.mapId, c)))
          ok
        case ItemKind.Scroll(ScrollEffect.Other) => Left("这个暂时用不了")
        case ItemKind.Book(_, level) =>
          for
            skill <- w.catalog.skillsByName.get(d.name.value).toRight("没有这个技能")
            _     <- Either.cond(skill.job == p.job, (), "你的职业学不了这个技能")
            _     <- Either.cond(p.level >= level, (), s"需要 $level 级才能学")
            _     <- Either.cond(p.skill(skill.id).isEmpty, (), "已经学会了")
          yield
            put(p, place, None)
            val key = Option.unless(Combat.isPassive(skill.id))((1 to 8).find(k => !p.skills.exists(_.key.contains(k)))).flatten
            p.skills += LearnedSkill(skill.id, 0, 0, key)
            w.recalc(p)
            w.notice(p, s"学会了 ${skill.name}", Tone.Good)
        case ItemKind.Equip(_) | ItemKind.Amulet(_) => equip(w, p, uid, None)
        case ItemKind.Material | ItemKind.Other     => Left("这个东西不能直接使用")
    yield r

  private def canWear(p: Player, d: ItemDef): Result =
    val r    = Requirement.of(d)
    val have = Requirement.current(r, p.level, p.stats)
    if have < r.value then Left(s"需要${r.label} ${r.value}（你现在 $have）")
    else if dressSex(d).exists(_ != p.sex) then Left("性别不符")
    else ok

  /** 武器看腕力，其他看穿戴负重（换下来的那件不算）。 */
  private def strongEnough(w: World, p: Player, d: ItemDef, slot: EquipSlot): Result =
    if slot == EquipSlot.Weapon then
      Either.cond(d.weight <= p.stats.maxHandWeight, (), s"武器太重（${d.weight}），你的腕力 ${p.stats.maxHandWeight}")
    else
      val replaced = p.worn.get(slot).fold(0)(w.weight)
      Either.cond(w.wearWeight(p) - replaced + d.weight <= p.stats.maxWearWeight, (), s"穿不动了（负重上限 ${p.stats.maxWearWeight}）")

  /** 穿戴；prefer 指定左右手镯/戒指位。换下来的放回原处。 */
  def equip(w: World, p: Player, uid: ItemUid, prefer: Option[EquipSlot]): Result =
    for
      _          <- Either.cond(!p.dead, (), "你已经死了")
      (place, it) <- carriedOrWhy(p, uid)
      d          <- defOf(w, it)
      slots       = ItemKind.slots(ItemKind.of(d))
      _          <- Either.cond(slots.nonEmpty, (), "这个不能穿戴")
      _          <- canWear(p, d)
      _          <- strongEnough(w, p, d, slots.head)
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
      w.recalc(p)

  /** 脱下：放到指定的空格，或包裹第一个空格。 */
  def unequip(w: World, p: Player, slot: EquipSlot, into: Option[ItemPlace]): Result =
    for
      it   <- p.worn.get(slot).toRight("这个位置没有装备")
      dest <- into.orElse(Some(p.bag.indexOf(None)).filter(_ >= 0).map(ItemPlace.Bag(_))).toRight("包裹满了")
      _    <- Either.cond(at(p, dest).isEmpty, (), "请放到空格子里")
    yield
      p.worn.remove(slot)
      put(p, dest, Some(it))
      w.recalc(p)

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
              yield
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
      _ <- Either.cond(p.cell.chebyshev(n.cell) <= NpcRange, (), "离得太远了")
      _ <- Either.cond(service(n.defn.service), (), s"${n.defn.name}不做这个")
    yield n

  def buy(w: World, p: Player, npc: ActorId, name: ItemName): Result =
    for
      n <- npcFor(w, p, npc, _ == MerchantService.Shop)
      _ <- Either.cond(n.defn.goods.exists(_.name == name), (), s"${n.defn.name}不卖这个")
      d <- w.catalog.item(name).toRight("数据表里没有这件物品")
      _ <- Either.cond(p.gold.value >= d.price, (), "金币不够")
      _ <- fits(w, p, d)
      it <- w.newItem(name).toRight("数据表里没有这件物品")
    yield
      addToBag(w, p, it)
      p.gold = p.gold - Gold(d.price)
      w.notice(p, s"买了 ${d.name.value}，花费 ${d.price} 金币", Tone.Item)

  def sell(w: World, p: Player, npc: ActorId, uid: ItemUid): Result =
    for
      n          <- npcFor(w, p, npc, _ == MerchantService.Shop)
      (place, it) <- carriedOrWhy(p, uid)
      d          <- defOf(w, it)
      _          <- Either.cond(n.defn.itemModes.isEmpty || n.defn.itemModes.contains(d.mode), (), s"${n.defn.name}不收这种东西")
    yield
      put(p, place, None)
      val price = d.price / 2
      p.gold = p.gold + Gold(price)
      w.notice(p, s"卖出 ${d.name.value}，得到 $price 金币", Tone.Item)

  def deposit(w: World, p: Player, npc: ActorId, uid: ItemUid): Result =
    for
      _          <- npcFor(w, p, npc, _ == MerchantService.Storage)
      (place, it) <- carriedOrWhy(p, uid)
    yield
      put(p, place, None)
      p.storage += it
      w.notice(p, s"保管了 ${it.name.value}", Tone.Item)

  def withdraw(w: World, p: Player, npc: ActorId, uid: ItemUid): Result =
    for
      _  <- npcFor(w, p, npc, _ == MerchantService.Storage)
      i  <- Some(p.storage.indexWhere(_.uid == uid)).filter(_ >= 0).toRight("仓库里没有这件物品")
      it  = p.storage(i)
      d  <- defOf(w, it)
      _  <- fits(w, p, d)
    yield
      p.storage.remove(i)
      addToBag(w, p, it)
      w.notice(p, s"取回 ${it.name.value}", Tone.Item)

  // ---------------- 其他 ----------------

  private def setSkillKey(p: Player, id: SkillId, key: Option[Int]): Result =
    for
      i <- Some(p.skills.indexWhere(_.id == id)).filter(_ >= 0).toRight("还没学会这个技能")
      _ <- Either.cond(key.forall(k => k >= 1 && k <= 8), (), "快捷键只能是 F1–F8")
      _ <- Either.cond(!Combat.isPassive(id) || key.isEmpty, (), "被动技能不用设快捷键")
    yield
      key.foreach(k => p.skills.indices.foreach(j => if p.skills(j).key.contains(k) then p.skills(j) = p.skills(j).copy(key = None)))
      p.skills(i) = p.skills(i).copy(key = key)

  /** 说话：附近的人看到；「!」开头全图喊话。 */
  private def say(w: World, p: Player, raw: String): Unit =
    val text = raw.trim.take(100)
    if text.startsWith("!") then
      val msg = text.drop(1).trim
      if msg.nonEmpty then w.players.foreach(w.tell(_, ServerMsg.Chat(p.charName, msg, ChatKind.Shout)))
    else if text.nonEmpty then w.nearby(p.cell, ServerMsg.Chat(p.charName, text, ChatKind.Say))
