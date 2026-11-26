package mir.client.ui

import mir.client.input.{ControlMode, Joystick, Pt}
import mir.core.*
import mir.model.{Affliction, BodyTint, ItemInstance, ItemPlace, Job}
import mir.protocol.{GameCmd, GroupView, Look, SocialCmd}
import mir.data.Span
import mir.rules.{Rates, Skill, SpellRules}

/**
 * 常驻的 HUD：原版 800×251 底栏（照 FState.pas DBottom，TS 版 ui/classic/layout.ts），
 * 叠在底栏上方的触屏操作件（摇杆、攻击键、F1–F8 技能环，TS 版 controls.ts；原版鼠标操作 #1353 不画）、锁定对象信息卡，左上角「传送」键。
 */
final class Board(ui: ClassicUi):
  import Board.*
  import ui.g

  private def boardX: Double = math.round((ui.W - Width) / 2).toDouble
  private def boardY: Double = ui.H - Height

  /** 快捷栏 6 格 */
  def beltRects: Vector[Rect] = Vector.tabulate(6)(i => Rect(boardX + 285 + 43 * i, boardY + 59, 32, 29))

  /** 鼠标下快捷栏里的物品（悬停说明） */
  def itemAt(m: Me, p: Pt): Option[ItemInstance] =
    beltRects.indices.collectFirst { case i if beltRects(i).contains(p.x, p.y) => m.view.belt.lift(i).flatten }.flatten

  /** 底栏左下角当摇杆用的一块：血/魔球连同上方透明装饰，宽到聊天框左边（这块原版没有可点的东西） */
  def orbStickRect: Rect = Rect(boardX, boardY, OrbStickWidth, Height)

  /** 聊天框（底栏中间黑底那块的上部，9 行） */
  def chatRect: Rect = Rect(boardX + 208, ui.H - 130, 387, ChatLines * 12)

  def draw(m: Me): Unit =
    drawBottom(m)
    // 叠在底栏上方：左边摇杆、右边攻击键（底栏上沿 40 像素是透明的装饰，往上再让 16 像素）
    val top = ui.H - Height - 16
    val s   = ui.ctlScale
    drawTarget(m, ui.safe.top + 6)
    ui.controls match
      case ControlMode.Touch =>
        drawStick(Pt(ui.safe.left + Joystick.Radius * s + 30, top - Joystick.Radius * s))
        val attack = Pt(ui.W - ui.safe.right - AttackR * s - 30, top - AttackR * s)
        drawAttack(attack)
        drawSkills(m, attack)
      case ControlMode.Classic => ()
    drawTopKeys()

  // ---------------- 底栏 ----------------

  private def drawBottom(m: Me): Unit =
    val bx = boardX
    val by = boardY
    val st = m.stats
    val s  = st.stats
    ui.area(Rect(bx, by + 40, Width, Height - 40))
    ui.area(Rect(bx, by, 190, 40))
    ui.area(Rect(bx + 610, by, 190, 40))
    // 左下角血/魔球一块（到聊天框左边）按下就是摇杆：满地物品和怪时左半屏一按就命中它们、摇杆起不来（用户 #1278）；原版鼠标操作没有摇杆
    if ui.controls == ControlMode.Touch then ui.hits.stick(orbStickRect)
    // 下半部分不透明（原版 Draw(..., FALSE)），上半部分透明叠在地图上
    g.rect(bx + 208, by + 120, 387, 124, "#000")
    g.img(Gfx.Main, 1, bx, by)
    g.img(Gfx.Main, 12, bx + 748, by + 79) // 白天（不做昼夜，用户 11-23）
    val hp = st.hp.value
    if m.job == Job.Warrior && st.level < 28 then
      // 战士 28 级前只有血柱
      g.size(Gfx.Main, 5).foreach { (fw, fh) =>
        g.imgPart(Gfx.Main, 5, bx + 38, by + 90, 0, 0, fw - 2, fh)
        val top = math.round(fh.toDouble / s.maxHp * (s.maxHp - hp)).toDouble
        g.imgPart(Gfx.Main, 6, bx + 38, by + 90 + top, 0, top, fw - 2, fh - top)
      }
    else drawOrb(m, bx + 40, by + 91)
    // 血蓝数值（原版没有，为了看得清楚）
    val hpText = s"$hp/${s.maxHp}"
    val mpText = s"${st.mp.value}/${s.maxMp}"
    g.text(hpText, bx + 86 - g.width(hpText) / 2, by + 186, "#fff", bold = true)
    g.text(mpText, bx + 86 - g.width(mpText) / 2, by + 200, "#9fc6ff", bold = true)
    // 地图名和自己的坐标（原版 DrawScrn.pas：左下角 8, SCREENHEIGHT-20）
    val title = m.view.map.flatMap(ui.catalog.map).fold("")(_.title)
    val here  = m.actor.cell
    g.text(s"$title ${here.x}:${here.y}".trim, bx + 8, ui.H - 20, "#fff", bold = true)
    g.text(st.level.toString, bx + 660, ui.H - 104, "#fff", bold = true)
    g.size(Gfx.Main, 7).foreach { (bw, bh) =>
      val er = st.exp.value.toDouble / math.max(1L, st.expToNext.value)
      g.imgPart(Gfx.Main, 7, bx + 666, ui.H - 73, 0, 0, math.round(bw * math.min(1, er)).toDouble, bh)
      // 原版这里还有一条负重条；不限重量后不画
    }
    // 聊天、系统消息；点聊天框说话，点私聊那行就私聊回对方（原版点聊天行填「/名字 」）
    val cr = chatRect
    val lines = ui.chat.takeRight(ChatLines).flatMap(l => wrap(l.text, cr.w).map(t => l.copy(text = t))).takeRight(ChatLines)
    ui.hit(cr)(ui.openChat())
    lines.zipWithIndex.foreach { (l, i) =>
      if l.bg != "transparent" then g.rect(cr.x, cr.y + i * 12, g.width(l.text), 12, l.bg)
      g.text(l.text, cr.x, cr.y + i * 12, l.fg)
      l.replyTo.foreach(peer => ui.hit(Rect(cr.x, cr.y + i * 12, cr.w, 12))(ui.whisperTo(peer)))
    }
    // 功能按钮：状态、包裹、技能、设置
    ui.button(Button(Rect(bx + 643, by + 61, 24, 24), () => ui.run(UiAction.State(StatePage.Equip)), img = Some(8)))
    ui.button(Button(Rect(bx + 682, by + 41, 24, 22), () => ui.run(UiAction.Bag), img = Some(9)))
    ui.button(Button(Rect(bx + 722, by + 21, 24, 23), () => ui.run(UiAction.State(StatePage.Magic)), img = Some(10)))
    settingsButton(Rect(bx + 764, by + 11, 24, 23))
    // 快捷栏（只放药和卷轴，1-6 键或点一下就用）
    beltRects.zipWithIndex.foreach { (r, i) =>
      val place = ItemPlace.Belt(i)
      val it    = m.view.belt.lift(i).flatten
      it.filterNot(_ => ui.handFrom(place)).foreach { i =>
        ui.catalog.item(i.name).foreach(d => g.item(d.look, i.count, r))
        if ui.deal.offers(i.uid) then g.rect(r.x, r.y, r.w, r.h, DealWindow.Dim)
      }
      g.text((i + 1).toString, r.x + 13, r.y + 19, "#fff", bold = true)
      ui.itemHit(r)(beltClicked(i, r))
    }
    // 底栏小按钮：小地图、交易（#1222）、行会（还没做，只画样子）、组队、好友、退出
    ui.button(Button(Rect(bx + 219, by + 104, 28, 13), () => ui.run(UiAction.MiniMap), img = Some(130), always = true))
    ui.button(Button(Rect(bx + 249, by + 104, 28, 13), () => ui.run(UiAction.Trade), img = Some(132), always = true))
    ui.button(Button(Rect(bx + 279, by + 104, 28, 13), () => ui.message("还没有这个功能"), img = Some(134), always = true))
    ui.button(Button(Rect(bx + 309, by + 104, 28, 13), () => ui.run(UiAction.Group), img = Some(128), always = true))
    friendsButton(Rect(bx + 339, by + 104, 28, 13))
    ui.button(Button(Rect(bx + 530, by + 104, 28, 13), () => ui.logout(), img = Some(136), always = true))
    ui.button(Button(Rect(bx + 560, by + 104, 28, 13), () => ui.logout(), img = Some(138), always = true))

  /**
   * 设置按钮：原版这个位置是声音开关（底栏图上画着喇叭），用户 11-24 嫌联想不到设置，
   * 在喇叭上盖一块同色圆底、画齿轮；颜色取自原版按钮（平时暗蓝底灰蓝图案，按下亮蓝底白图案）。
   */
  private def settingsButton(r: Rect): Unit =
    ui.button(Button(r, () => ui.run(UiAction.Settings)))
    val down       = ui.isPressed(r)
    val (cx, cy)   = (r.x + 12.5, r.y + 11.5)
    g.circle(cx, cy, 9.5, if down then "#104284" else "#0c2142")
    g.gear(cx, cy, 7.5, if down then "#fffbf0" else "#6070a8")

  /**
   * 好友按钮（#1221）：后来的原版客户端把好友键放在组队键右边第二格（DBotFriend），1.76 素材里没有这张图；
   * 放在组队键紧右边，照旁边小按钮的颜色画一个写「友」的键。
   */
  private def friendsButton(r: Rect): Unit =
    ui.button(Button(r, () => ui.run(UiAction.Friends)))
    val down = ui.isPressed(r)
    g.frame(r, if down then "#104284" else "#0c2142", "#3a4a78")
    g.text("友", r.x + (r.w - g.width("友")) / 2, r.y + 0.5, if down then "#fffbf0" else "#8090c0")

  /** 折好的行（每帧都画，量字宽不便宜，记住） */
  private val wrapped = scala.collection.mutable.HashMap.empty[String, Vector[String]]

  /** 超过宽度的一行按字折成几行。 */
  private def wrap(text: String, w: Double): Vector[String] =
    if wrapped.size > 200 then wrapped.clear()
    wrapped.getOrElseUpdate(
      text,
      if g.width(text) <= w then Vector(text)
      else
        text.foldLeft(Vector("")) { (acc, c) =>
          val next = acc.last + c
          if g.width(next) > w && acc.last.nonEmpty then acc :+ c.toString else acc.init :+ next
        }
    )

  /**
   * 点快捷栏：拿着东西就放上去；包裹关着时点一下就用；包裹开着时和包裹格子一样
   * （电脑拿起来能放回包裹、双击用；手机弹菜单）。
   */
  private def beltClicked(i: Int, r: Rect): Unit =
    val place = ItemPlace.Belt(i)
    val there = ui.client.view.belt.lift(i).flatten
    if ui.hand.isEmpty && !ui.bag.isOpen then there.foreach(it => ui.send(GameCmd.UseItem(it.uid)))
    else ui.clickPlace(place, there, r)

  /** 原版血蓝球（左半血、右半蓝，按比例从上往下空）。 */
  private def drawOrb(m: Me, x: Double, y: Double): Unit = g.size(Gfx.Main, 4).foreach { (w, h) =>
    val s    = m.stats.stats
    val half = w / 2
    val top  = math.round(h.toDouble / s.maxHp * (s.maxHp - m.stats.hp.value)).toDouble
    g.imgPart(Gfx.Main, 4, x, y + top, 0, top, half - 1, h - top)
    val mtop = math.round(h.toDouble / math.max(1, s.maxMp) * (s.maxMp - m.stats.mp.value)).toDouble
    g.imgPart(Gfx.Main, 4, x + half + 1, y + mtop, half + 1, mtop, w - half - 2, h - mtop)
  }

  /** 左上角一排键（TopKeys：传送菜单 #1139、留言板 #1280、更新日志）；按触屏操作件的倍数放大。 */
  private def drawTopKeys(): Unit =
    val s = ui.ctlScale
    TopKeys.zipWithIndex.foreach { case ((label, action), i) =>
      val r = Rect(ui.safe.left + 6 + i * (TopKeyW + 6) * s, ui.safe.top + 6, TopKeyW * s, TopKeyH * s)
      g.scaled(r.x, r.y, s) {
        g.frame(Rect(0, 0, TopKeyW, TopKeyH), if ui.isPressed(r) then "rgba(90,70,30,.9)" else "rgba(0,0,0,.6)", "#c9a060")
        g.text(label, (TopKeyW - g.width(label)) / 2, 8, "#ffe08a", bold = true)
      }
      ui.area(r)
      ui.button(Button(r, () => ui.run(action)))
    }

  // ---------------- 触屏操作件（用户 #1140） ----------------

  /**
   * 锁定对象的信息卡：怪是名字、等级、攻防、经验（倍率后），中了毒或麻痹再加一行；宝宝绿框；别的玩家是名字、职业、灰/黄/红名
   * （#1225；安全区外打得到时红框，提醒攻击键会打他），下面一排键：组队（#1220：没组队「组队」、自己是队长时「加入小组」/「踢出小组」，队员没有）、
   * 「私聊」（聊天框填好「/名字 」）、「加好友」/「删好友」（#1221）、「交易」（#1222）、「装备」（#1263，看他身上穿的）。都有血条，右上角 × 取消锁定。
   */
  private def drawTarget(m: Me, top: Double): Unit =
    for
      id   <- ui.client.locked
      a    <- ui.client.actor(id)
      base <- targetCard(a.look, m.actor.id)
    do
      val side = a.look match
        case _: Look.Human => base.copy(friend = !ui.client.canAttack(a))
        case _             => base
      val card = side.copy(lines = side.lines ++ afflictionLine(a.afflictions))
      val sickColor = Affliction.tint(a.afflictions).map(tintColor)
      val x = math.round((ui.W - TargetW) / 2).toDouble
      val r = Rect(x, top, TargetW, 37 + 15 * card.lines.size)
      ui.area(r)
      g.frame(r, "rgba(10,7,3,.8)", if card.friend then "#5a8a5a" else "#8a3a2a")
      g.text(card.title, x + 6, top + 5, if card.friend then "#9cff8a" else "#ffe08a", bold = true)
      val ratio = math.max(0, math.min(1, a.hp.value.toDouble / math.max(1, a.maxHp.value)))
      g.rect(x + 6, top + 22, TargetW - 12, 10, "#300")
      g.rect(x + 6, top + 22, math.round((TargetW - 12) * ratio).toDouble, 10, if card.friend then "#2ecc71" else "#d33")
      val hp = s"${a.hp.value}/${a.maxHp.value}"
      g.text(hp, x + (TargetW - g.width(hp)) / 2, top + 21, "#fff", bold = true)
      // 中毒、麻痹那行（有的话是最后一行）按身子的染色上色
      card.lines.zipWithIndex.foreach { (line, i) =>
        val color = if i >= base.lines.size then sickColor.getOrElse("#c0c0c0") else if i == 0 then "#ddd" else "#c0c0c0"
        g.text(line, x + 6, top + 37 + 15 * i, color)
      }
      val close = Rect(x + TargetW - 30, top, 30, 22)
      g.text("×", close.x + 11, close.y + 4, "#f88", bold = true)
      ui.button(Button(close, () => ui.ctl.unlock()))
      a.look match
        case h: Look.Human if h.name != m.name => drawPlayerKeys(m, a.id, h.name, Rect(x, r.y + r.h + 2, TargetW, 24))
        case _                                 => ()

  /** 锁定别的玩家时信息卡下面的一排键，平分 row 的宽。 */
  private def drawPlayerKeys(m: Me, id: ActorId, other: String, row: Rect): Unit =
    val isFriend = m.view.friends.exists(_.name == other)
    val keys =
      groupAction(m.view.group, m.name, other).map((label, cmd) => label -> (() => ui.social(cmd))).toList ++
        List(
          "私聊" -> (() => ui.whisperTo(other)),
          (if isFriend then "删好友" -> (() => ui.social(SocialCmd.RemoveFriend(other)))
           else "加好友" -> (() => ui.social(SocialCmd.AddFriend(other)))),
          // 交易（#1222）：对方也对我点了就打开交易窗口
          "交易" -> (() => ui.send(GameCmd.DealTry(id))),
          // 看他身上的装备（#1263，原版 Ctrl+右键点人）
          "装备" -> (() => ui.send(GameCmd.Inspect(id)))
        )
    // 各键宽 = 字宽 + 平分剩下的空（5 个键时「踢出小组」这种长字也放得下）
    val gap   = 3.0
    val texts = keys.map((label, _) => g.width(label))
    val pad   = (row.w - gap * (keys.size - 1) - texts.sum) / keys.size
    keys.zip(texts).foldLeft(row.x) { case (x, ((label, act), tw)) =>
      drawCardKey(Rect(x, row.y, tw + pad, row.h), label, act)
      x + tw + pad + gap
    }: Unit

  /** 锁定玩家时信息卡下面的组队键：能做什么由自己的组队状态决定（队员不能拉人踢人，没有键）。 */
  private def groupAction(group: GroupView, me: String, other: String): Option[(String, SocialCmd)] =
    if group.members.isEmpty then Some("组队" -> SocialCmd.CreateGroup(other))
    else if !group.isLeader(me) then None
    else if group.has(other) then Some("踢出小组" -> SocialCmd.RemoveMember(other))
    else Some("加入小组" -> SocialCmd.AddMember(other))

  private def drawCardKey(r: Rect, label: String, act: () => Unit): Unit =
    ui.area(r)
    g.frame(r, if ui.isPressed(r) then "rgba(90,70,30,.9)" else "rgba(10,7,3,.8)", "#c9a060")
    g.text(label, r.x + (r.w - g.width(label)) / 2, r.y + 6, "#ffe08a", bold = true)
    ui.button(Button(r, act))

  /** 锁定卡上中毒、麻痹那一行（没有就不加） */
  private def afflictionLine(as: Set[Affliction]): List[String] =
    val names = Affliction.values.toList.filter(as).map {
      case Affliction.GreenPoison => "绿毒"
      case Affliction.RedPoison   => "红毒"
      case Affliction.Paralyzed   => "麻痹"
    }
    Option.when(names.nonEmpty)("中了：" + names.mkString("、")).toList

  private def tintColor(t: BodyTint): String = t match
    case BodyTint.Green => "#7cff6a"
    case BodyTint.Red   => "#ff7a6a"
    case BodyTint.Gray  => "#d0d0d0"

  /**
   * 信息卡的标题和文字行；野怪按怪物表显示攻防和经验，宝宝显示服务端发来的实际攻防（#1232，召唤时按技能等级和道术放大过），
   * 自己召唤的再提醒属性在召唤那一刻定下。
   */
  private def targetCard(look: Look, me: ActorId): Option[TargetCard] = look match
    case h: Look.Human => Some(TargetCard(s"${h.name}  ${h.job.label}", friend = true, Describe.pkStandingLine(h.pk).toList))
    case m: Look.Monster =>
      ui.catalog.monster(m.kind).map { d =>
        def stats(dc: Span, ac: Span, mac: Span) = s"攻击 ${dc.lo}-${dc.hi}  防御 ${ac.lo}-${ac.hi}  魔防 ${mac.lo}-${mac.hi}"
        val name = s"${ui.catalog.displayName(d.name).value}  ${d.level} 级"
        m.pet match
          case Some(pet) =>
            val resummon = pet.master == me && SpellRules.isSummoned(m.kind)
            TargetCard(
              s"$name（宝宝）",
              friend = true,
              stats(pet.dc, pet.ac, pet.mac) :: Option.when(resummon)("属性召唤时定下，技能升级后要重召").toList
            )
          case None =>
            val tag = if d.undead > 0 then "（不死系）" else ""
            TargetCard(s"$name$tag", friend = false, List(stats(d.dc, d.ac, d.mac), s"经验 ${d.exp.toLong * Rates.exp}"))
      }
    case _: Look.Npc => None

  private def drawStick(home: Pt): Unit =
    val js = ui.joystick
    val s  = ui.ctlScale
    js.radius = Joystick.Radius * s
    val base = if js.active then js.base else home
    val knob = if js.active then js.knob else base
    g.circle(base.x, base.y, js.radius, if js.active then "rgba(0,0,0,.4)" else "rgba(0,0,0,.35)", Some("rgba(216,162,74,.85)"))
    g.circle(knob.x, knob.y, 22 * s, if js.active then "rgba(216,162,74,.8)" else "rgba(216,162,74,.55)", Some("#d8a24a"))
    if !js.active then g.text("摇杆", base.x - g.width("摇杆") / 2, base.y - 6, "#fff", bold = true)
    // 电脑端在底盘上下左右标 W/S/A/D，提示键盘也能走（用户 11-26 #1344；键盘走路见 #1189）；触屏不标
    if !ui.touch then
      val off = js.radius * 0.72
      List(("W", 0.0, -off), ("S", 0.0, off), ("A", -off, 0.0), ("D", off, 0.0)).foreach { (k, dx, dy) =>
        g.text(k, base.x + dx - g.width(k) / 2, base.y + dy - 6, "#ffe08a", bold = true)
      }

  private def drawAttack(c: Pt): Unit =
    val ar = AttackR * ui.ctlScale
    val r  = Rect(c.x - ar, c.y - ar, ar * 2, ar * 2)
    g.circle(c.x, c.y, ar, if ui.isPressed(r) then "rgba(140,40,20,.85)" else "rgba(60,20,10,.7)", Some("#d8a24a"))
    g.text("攻击", c.x - g.width("攻击") / 2, c.y - 6, "#ffe08a", bold = true)
    ui.area(r)
    ui.button(Button(r, () => ui.ctl.attack()))

  /** F1–F8 按键位摆在攻击键左上的两圈；只显示设了快捷键的技能，取消快捷键即消失（#1164）。 */
  private def drawSkills(m: Me, attack: Pt): Unit =
    val s     = ui.ctlScale
    val sr    = SkillR * s
    val now   = ui.client.serverNow
    val all   = ui.ctl.activeSkills
    val byKey = all.flatMap(l => l.key.filter(k => k >= 1 && k <= 8).map(_ -> l)).toMap
    (1 to 8).foreach { k =>
      byKey.get(k).foreach { ls =>
        ui.catalog.skill(ls.id).foreach { d =>
          val ring = Rings(if k <= 4 then 0 else 1) * s
          val ang  = Angles((k - 1) % 4) * math.Pi / 180
          val cx   = attack.x + ring * math.cos(ang)
          val cy   = attack.y + ring * math.sin(ang)
          val r    = Rect(cx - sr, cy - sr, sr * 2, sr * 2)
          g.circle(cx, cy, sr, if ui.isPressed(r) then "rgba(90,70,30,.9)" else "rgba(0,0,0,.6)", Some("#8a6a3a"))
          g.imgCenter("magicon", d.effect * 2, r)
          val cd = m.view.cooldowns.get(ls.id).fold(0L)(t => t.value - now.value)
          if cd > 0 then
            g.circle(cx, cy, sr, "rgba(0,0,0,.6)")
            val t = if cd < 10000 then f"${cd / 1000.0}%.1f" else (cd / 1000).toString
            g.text(t, cx - g.width(t) / 2, cy - 6, "#fff", bold = true)
          else if m.stats.mp.value < Skill.manaCost(d, ls.level) then g.circle(cx, cy, sr, "rgba(0,0,80,.45)")
          g.text(s"F$k", r.x - 2, r.y - 4, "#ff0", bold = true)
          ui.area(r)
          ui.button(Button(r, () => ui.ctl.castSkill(ls.id), onHold = Some(() =>
            ui.itemMenu.show(ItemMenu(Describe.skillLines(ui.catalog, d, ls, m.stats), Nil, r))
          )))
        }
      }
    }

object Board:
  val Width     = 800.0
  val Height    = 251.0
  val ChatLines = 9
  /** 左下角摇杆区宽度：聊天框从 208 起，留 8 像素不挨着 */
  val OrbStickWidth = 200.0
  val AttackR   = 38.0
  val SkillR    = 22.0
  /** 技能键离攻击键中心的距离：F1-F4 内圈，F5-F8 外圈 */
  val Rings  = Vector(92.0, 152.0)
  val Angles = Vector(180.0, 210.0, 240.0, 270.0)
  val TargetW = 210.0
  /** 左上角一排键（原版没有）：「留言」「更新」新标签页打开网页，游戏不断 */
  val TopKeys: List[(String, UiAction)] = List(
    "传送" -> UiAction.Teleport,
    "留言" -> UiAction.OpenPage(WebPage.MessageBoard),
    "更新" -> UiAction.OpenPage(WebPage.Changelog)
  )
  val TopKeyW = 52.0
  val TopKeyH = 28.0

  /** 锁定信息卡的内容：friend 是自己人（绿框绿血条），lines 是血条下面的几行 */
  final case class TargetCard(title: String, friend: Boolean, lines: List[String])
