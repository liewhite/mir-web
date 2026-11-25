package mir.client.ui

import mir.client.input.Pt
import mir.model.*
import mir.protocol.Look
import mir.rules.{Rates, Recovery}

/** 状态窗的 4 页（原版顺序，左右箭头循环翻）。 */
enum StatePage:
  case Equip, Stats, Ability, Magic

/**
 * 状态窗（照 FState.pas DStateWin，TS 版 ui/classic/classic.ts）：装备、属性、能力、技能 4 页。
 * 底栏「状态」开装备页、「技能」开技能页；点技能图标设快捷键（KeyDialog）。
 */
final class StateWindow(ui: ClassicUi) extends Window:
  import StateWindow.*
  import ui.{catalog, g}

  private var open      = false
  private var page      = StatePage.Equip
  private var magicPage = 0

  def isOpen: Boolean = open
  def close(): Unit   = open = false

  /** 底栏按钮：开着同一页就关，否则翻到这一页。 */
  def toggle(p: StatePage): Unit =
    if open && page == p then open = false
    else
      open = true
      page = p

  private def flip(by: Int): Unit = page = StatePage.fromOrdinal((page.ordinal + by + StatePage.values.length) % StatePage.values.length)

  def origin: Pt = Pt(ui.W - Width - ui.safe.right, 0)

  def draw(m: Me): Unit =
    val o = origin
    ui.area(Rect(o.x, o.y, Width, Height))
    g.img(Gfx.Main, 370, o.x, o.y)
    g.text(m.name, o.x + 116 - g.width(m.name) / 2, o.y + 23, "#ffe08a", bold = true)
    page match
      case StatePage.Equip => drawEquipPage(m, o)
      case StatePage.Stats =>
        val s    = m.stats.stats
        val vals = List(s.ac, s.mac, s.dc, s.mc, s.sc).map(r => s"${r.lo}-${r.hi}") ++
          List(s"${m.stats.hp.value}/${s.maxHp}", s"${m.stats.mp.value}/${s.maxMp}")
        vals.zipWithIndex.foreach((v, i) => g.text(v, o.x + 112, o.y + 99 + i * 20))
        infoTap(statRect)(statLines(m))
      case StatePage.Ability =>
        drawAbilityPage(m, o)
        infoTap(statRect)(statLines(m))
      case StatePage.Magic => drawMagicPage(m, o)
    ui.button(Button(Rect(o.x + 8, o.y + 39, 16, 23), () => close(), img = Some(371)))
    ui.button(Button(Rect(o.x + 7, o.y + 128, 24, 23), () => flip(-1), img = Some(373)))
    ui.button(Button(Rect(o.x + 7, o.y + 187, 24, 23), () => flip(1), img = Some(372)))

  def equipRect(slot: EquipSlot): Rect =
    val o = origin
    EquipRects(slot).moved(o.x, o.y)

  private def drawEquipPage(m: Me, o: Pt): Unit =
    // 手上拿着的那件不画在身上
    drawFigure(ui, o, m.sex, m.hair, m.view.worn.filterNot((slot, _) => ui.handFrom(ItemPlace.Worn(slot))))
    EquipSlot.values.foreach { slot =>
      val r = equipRect(slot)
      ui.itemHit(r)(ui.clickPlace(ItemPlace.Worn(slot), ui.client.view.worn.get(slot), r))
    }

  private def drawAbilityPage(m: Me, o: Pt): Unit =
    val st = m.stats
    val s  = st.stats
    g.img(Gfx.Main, 382, o.x + 38, o.y + 52)
    val bx = o.x + 58
    val by = o.y + 62
    val rows = List(
      ("经验", st.expToNext.fold("满级")(need => f"${100.0 * st.exp.value / math.max(1L, need.value)}%.2f%%"), false),
      ("准确", s.hit.toString, false),
      ("敏捷", s.agility.toString, false),
      ("魔法躲避", s"${s.magicAvoid}", false),
      ("毒物躲避", s"${s.poisonAvoid}", false),
      ("攻击间隔", s"${s.hitInterval}ms", false),
      ("体力恢复", s"${Recovery.hpPerTick(s)}/${Recovery.IntervalMs / 1000}秒", false),
      ("魔法恢复", s"${Recovery.mpPerTick(s)}/${Recovery.IntervalMs / 1000}秒", false),
      ("PK 值", st.pkPoints.toString, mir.rules.Pk.level(st.pkPoints) > 0)
    )
    rows.zipWithIndex.foreach { case ((k, v, bad), i) =>
      g.text(k, bx, by + 14 * i, "#c0c0c0")
      g.text(v, bx + 85, by + 14 * i, if bad then "#f00" else "#c0c0c0")
    }

  private def drawMagicPage(m: Me, o: Pt): Unit =
    g.img(Gfx.Main, 383, o.x + 38, o.y + 52)
    val bbx  = o.x + 38
    val bby  = o.y + 52
    val list = m.view.skills
    // 触屏没有悬停：点技能那一行弹说明（图标在上层，点图标仍是设快捷键）
    list.zipWithIndex.slice(magicPage * 5, magicPage * 5 + 5).foreach { (ls, i) =>
      catalog.skill(ls.id).foreach(d => infoTap(magicRow(i - magicPage * 5))(Describe.skillLines(catalog, d, ls, m.stats)))
    }
    list.zipWithIndex.slice(magicPage * 5, magicPage * 5 + 5).foreach { (ls, i) =>
      catalog.skill(ls.id).foreach { d =>
        val row  = i - magicPage * 5
        val y    = bby + 8 + row * 37
        val icon = Rect(bbx + 8, y, 31, 33)
        g.img("magicon", d.effect * 2, icon.x, icon.y)
        ui.hit(icon)(ui.keyDlg.open(ls.id))
        g.text(d.name, bbx + 48, y, "#c0c0c0")
        g.img(Gfx.Main, 112, bbx + 48, y + 15)
        g.img(Gfx.Main, 111, bbx + 74, y + 15)
        g.text(ls.level.toString, bbx + 64, y + 15, "#c0c0c0")
        g.text(if ls.level < 3 then s"${ls.train}/${Rates.trainNeed(d, ls.level)}" else "-", bbx + 94, y + 15, "#c0c0c0")
        ls.key.foreach(k => g.text(s"F$k", bbx + 145, y, "#ff0", bold = true))
      }
    }
    val pages = math.max(1, (list.size + 4) / 5)
    ui.button(Button(Rect(o.x + 213, o.y + 113, 12, 21), () => magicPage = math.max(0, magicPage - 1), img = Some(398), always = true))
    ui.button(Button(Rect(o.x + 213, o.y + 143, 12, 21), () => magicPage = math.min(pages - 1, magicPage + 1), img = Some(396), always = true))

  /** 属性页（第 2、3 页）上数值那一块：悬停或点它看说明 */
  private def statRect: Rect = Rect(origin.x + 40, 90, 180, 150)

  /** 触屏没有悬停：点这块弹出说明（不带按钮，点别处关掉）。电脑上靠悬停，不登记。 */
  private def infoTap(r: Rect)(lines: => List[Line]): Unit =
    if ui.touch then ui.hit(r)(ui.itemMenu.show(ItemMenu(lines, Nil, r)))

  /** 技能页第几行（屏幕上）的范围：悬停说明用 */
  private def magicRow(row: Int): Rect =
    val o = origin
    Rect(o.x + 46, o.y + 60 + row * 37, 170, 33)

  override def itemAt(m: Me, p: Pt): Option[ItemInstance] =
    Option.when(page == StatePage.Equip)(EquipSlot.values.collectFirst {
      case s if m.view.worn.contains(s) && equipRect(s).contains(p.x, p.y) => m.view.worn(s)
    }).flatten

  override def hover(m: Me, p: Pt): Option[List[Line]] = page match
    case StatePage.Stats | StatePage.Ability => Option.when(statRect.contains(p.x, p.y))(statLines(m))
    case StatePage.Magic =>
      m.view.skills.zipWithIndex.slice(magicPage * 5, magicPage * 5 + 5).collectFirst {
        case (ls, i) if magicRow(i - magicPage * 5).contains(p.x, p.y) && catalog.skill(ls.id).isDefined =>
          Describe.skillLines(catalog, catalog.skill(ls.id).get, ls, m.stats)
      }
    case StatePage.Equip => None

  private def statLines(m: Me): List[Line] =
    val mons = ui.client.actors.flatMap { a =>
      a.look match
        case Look.Monster(kind, _, None, _) if !a.dead => catalog.monster(kind)
        case _                                     => None
    }.toList
    val avgA = if mons.isEmpty then 15 else math.round(mons.map(_.speed).sum.toDouble / mons.size).toInt
    val avgH = if mons.isEmpty then 8 else math.round(mons.map(_.hit).sum.toDouble / mons.size).toInt
    Describe.statLines(m.name, m.job, m.stats, avgA, avgH)

object StateWindow:
  /** 窗口大小（底图 Main 370） */
  val Width  = 232.0
  val Height = 325.0

  /** 装备格（状态窗内坐标，FState.pas DSWNecklace 等） */
  val EquipRects: Map[EquipSlot, Rect] = Map(
    EquipSlot.Necklace  -> Rect(168, 87, 34, 31),
    EquipSlot.Helmet    -> Rect(115, 93, 18, 18),
    EquipSlot.BraceletR -> Rect(42, 176, 34, 31),
    EquipSlot.BraceletL -> Rect(168, 176, 34, 31),
    EquipSlot.RingR     -> Rect(42, 215, 34, 31),
    EquipSlot.RingL     -> Rect(168, 215, 34, 31),
    EquipSlot.Weapon    -> Rect(47, 80, 47, 87),
    EquipSlot.Dress     -> Rect(96, 122, 53, 112),
    // 腰带、靴子（#1311）：横向照 1.80 FState.pas DSWBelt、DSWBoots（84、126），
    // 1.80 的格子在 y=254，压在 1.76 底图下方那个框的边上，这里下移到框里（框内 y 268–315）
    EquipSlot.Belt      -> Rect(84, 276, 34, 31),
    EquipSlot.Boots     -> Rect(126, 276, 34, 31)
  )

  /** 原版图库里没有格子底图的装备位：自己画框（颜色取自人形图里的格子），空着时暗字写上是什么位置 */
  val SelfFramed: List[EquipSlot] = List(EquipSlot.Belt, EquipSlot.Boots)
  private val SlotFill   = "#080000"
  private val SlotStroke = "#636273"
  private val SlotHint   = "#4a4a55"

  /** 画在人形上的装备（其余画在格子里） */
  val OnFigure: Set[EquipSlot] = Set(EquipSlot.Dress, EquipSlot.Weapon, EquipSlot.Helmet)

  /**
   * 装备页的人形和身上各件（窗口左上角在 o）：自己的状态窗和查看别人装备的窗口（#1263）共用，
   * 照 FState.pas DStateWinDirectPaint / DUserState1DirectPaint：身子、头发，衣服武器头盔画在人形上，其余画在格子里。
   */
  def drawFigure(ui: ClassicUi, o: Pt, sex: Sex, hair: Int, worn: Map[EquipSlot, ItemInstance]): Unit =
    import ui.{catalog, g}
    g.img(Gfx.Main, if sex == Sex.Female then 377 else 376, o.x + 38, o.y + 52)
    val bbx     = o.x + 31
    val bby     = o.y + 96
    val hairIdx = (if sex == Sex.Female then 480 else 440) + hair
    g.sprite(Gfx.Main, hairIdx).foreach(h => g.img(Gfx.Main, hairIdx, bbx + h.ox, bby + h.oy))
    // 衣服、武器、头盔画在人形上（stateitem 图带偏移）
    List(EquipSlot.Dress, EquipSlot.Weapon, EquipSlot.Helmet).foreach { slot =>
      for
        it <- worn.get(slot)
        d  <- catalog.item(it.name)
        s  <- g.sprite("stateitem", d.look)
      do g.img("stateitem", d.look, bbx + s.ox, bby + s.oy)
    }
    SelfFramed.foreach { slot =>
      val r = EquipRects(slot).moved(o.x, o.y)
      g.frame(r, SlotFill, SlotStroke)
      if !worn.contains(slot) then g.text(slot.label, r.x + (r.w - g.width(slot.label)) / 2, r.y + (r.h - 12) / 2, SlotHint)
    }
    EquipSlot.values.filterNot(OnFigure).foreach { slot =>
      worn.get(slot).flatMap(i => catalog.item(i.name)).foreach(d => g.item(d.look, 1, EquipRects(slot).moved(o.x, o.y)))
    }
