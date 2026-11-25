package mir.client.render

import mir.client.assets.{Assets, LoadCache, Loaded, MapData, Sprite, SpriteStore}
import mir.client.assets.MapData.{GroundBlock, UnitX, UnitY}
import mir.core.*
import mir.data.Catalog
import mir.model.{ActKind, Affliction, Aura, BodyTint, Cover, FieldKind, Mood}
import mir.protocol.*
import mir.rules.{ItemKind, PkStanding}
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.collection.mutable
import scala.concurrent.Future

/** 逻辑像素（地图坐标系，不含缩放）。 */
final case class Px(x: Double, y: Double)

/**
 * Canvas 2D 渲染：地面（预渲染大块）→ 48×32 平铺物件 → 按行画高物件、地上物品、角色 → 特效 → 名字/血条/飘字/气泡。
 * 坐标和画法照原版 PlayScn.pas：格子 48×32，角色锚点是所在格的左上角，图片按自带偏移画。照 TS 版 render/renderer.ts。
 */
final class Renderer(
    canvas: dom.HTMLCanvasElement,
    sprites: SpriteStore,
    assets: Assets,
    catalog: Catalog,
    onAssetFailure: (String, Throwable) => Unit = SpriteStore.warn
):
  import Renderer.*

  val ctx: dom.CanvasRenderingContext2D = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]

  private val grounds = LoadCache[String, dom.HTMLImageElement](SpriteStore.RetryAfterMs, onAssetFailure)
  /** 这一帧画出来的、可点中的角色（点怪锁定用） */
  private var hits = Vector.empty[Hit[Sprite]]
  private val tinter = Tinter()
  private val silhouettes = Silhouettes()
  private var dpr  = 1.0

  var camX = 0.0
  var camY = 0.0
  /** 画面逻辑像素大小（不含缩放） */
  var width  = 800.0
  var height = 600.0
  var zoom   = 1.0
  /** 人物在画面纵向的位置：原版底栏高，人物略偏上 */
  var centerY = 0.47

  /** 画布铺满窗口；zoom 是地图缩放（CSS 像素 / 逻辑像素）。 */
  def resize(cssW: Double, cssH: Double, zoom: Double): Unit =
    dpr = math.min(2.0, if dom.window.devicePixelRatio > 0 then dom.window.devicePixelRatio else 1.0)
    this.zoom = zoom
    width = math.round(cssW / zoom).toDouble
    height = math.round(cssH / zoom).toDouble
    canvas.width = math.round(cssW * dpr).toInt
    canvas.height = math.round(cssH * dpr).toInt
    canvas.style.width = s"${cssW}px"
    canvas.style.height = s"${cssH}px"
    scaleTo(zoom)

  /** 把画布坐标切到「1 单位 = scale 个 CSS 像素」：地图用 zoom，界面用自己的缩放。 */
  def scaleTo(scale: Double): Unit =
    ctx.setTransform(dpr * scale, 0, 0, dpr * scale, 0, 0)
    ctx.imageSmoothingEnabled = false

  def toLogical(cssX: Double, cssY: Double): Px = Px(cssX / zoom, cssY / zoom)

  def cellAt(p: Px): Cell = Cell(math.floor((p.x + camX) / UnitX).toInt, math.floor((p.y + camY) / UnitY).toInt)

  /** 逻辑像素下点中的角色（不算 exclude，即自己）：先按身体图像素、再按正中 40×70、最后按 slop 放宽，见 Picking。 */
  def actorAt(p: Px, slop: Double, exclude: ActorId): Option[ActorId] =
    Picking.pick(hits, p.x, p.y, slop, exclude)(silhouettes.solid)

  // ---------------- 预下载 ----------------

  /** 进图前把周围一屏的地面和物件先下载好，免得一进来画面缺块。 */
  def preloadAround(map: MapData, center: Cell, progress: (Int, Int) => Unit): Future[Unit] =
    val rx = math.ceil(width / UnitX / 2).toInt + 2
    val ry = math.ceil(height / UnitY / 2).toInt + 2
    val objects = mutable.HashMap.empty[String, mutable.Set[Int]]
    for
      y <- center.y - ry to center.y + ry + LongRows
      x <- center.x - rx - WideCols to center.x + rx
      o <- map.objectAt(x, y)
    do objects.getOrElseUpdate(o.lib, mutable.Set.empty) ++= (0 until math.max(1, o.aniFrames)).map(o.index + _)
    val bw = GroundBlock * UnitX
    val bh = GroundBlock * UnitY
    val cx = center.x * UnitX
    val cy = center.y * UnitY
    val blocks = for
      by <- math.floor((cy - height) / bh).toInt to math.floor((cy + height) / bh).toInt
      bx <- math.floor((cx - width) / bw).toInt to math.floor((cx + width) / bw).toInt
      if bx >= 0 && by >= 0
    yield
      val key = groundKey(map.asset, bx, by)
      grounds.settled(key)(loadGround(key))
    val jobs  = objects.toList.map((lib, set) => sprites.preload(lib, set)) ++ blocks
    var done  = 0
    progress(0, jobs.size)
    Future.sequence(jobs.map(_.andThen { _ =>
      done += 1
      progress(done, jobs.size)
    })).map(_ => ())

  private def groundKey(asset: String, bx: Int, by: Int): String = s"$asset/${bx}_$by"

  /** 下载一块地面（由 grounds 缓存：同一块同时只下一份，失败过一会儿再下）。 */
  private def loadGround(key: String): Future[dom.HTMLImageElement] = assets.image(s"ground/$key.webp")

  private def ground(asset: String, bx: Int, by: Int): Option[dom.HTMLImageElement] =
    val key = groundKey(asset, bx, by)
    grounds.get(key)(loadGround(key)) match
      case Loaded.Ready(img) => Some(img)
      case _                 => None

  // ---------------- 主绘制 ----------------

  /** 角色在世界中的像素位置（含走路插值）。 */
  def actorPixel(a: ActorView, now: Millis): Px =
    a.action.from match
      case Some(from) if a.action.kind.moving =>
        val t = math.min(1.0, math.max(0.0, (now - a.action.startedAt).toDouble / math.max(1, a.action.duration)))
        Px((from.x + (a.cell.x - from.x) * t) * UnitX, (from.y + (a.cell.y - from.y) * t) * UnitY)
      case _ => Px(a.cell.x * UnitX.toDouble, a.cell.y * UnitY.toDouble)

  def draw(s: Scene): Unit =
    scaleTo(zoom)
    val now = s.server
    val me  = s.actors.find(_.id == s.me)
    me.foreach { m =>
      val p = actorPixel(m, now)
      camX = math.round(p.x + UnitX / 2 - width / 2).toDouble
      camY = math.round(p.y + UnitY / 2 - height * centerY).toDouble
    }
    ctx.fillStyle = "#000"
    ctx.fillRect(0, 0, width, height)
    drawGround(s.map)

    val left     = math.floor(camX / UnitX).toInt
    val top      = math.floor(camY / UnitY).toInt
    val right    = math.ceil((camX + width) / UnitX).toInt
    val bottom   = math.ceil((camY + height) / UnitY).toInt
    val aniCount = (s.local / 100).toLong

    val actorsByRow = s.actors.groupBy(a => math.round(actorPixel(a, now).y / UnitY).toInt)
    val itemsByRow  = s.ground.groupBy(_.cell.y)
    val fieldsByRow = s.fields.groupBy(_.cell.y)

    // 第一遍：48×32 的平铺物件（地面装饰）
    for
      y <- top - 1 to bottom + 1
      x <- left - 1 to right + 1
      o <- s.map.objectAt(x, y) if !o.blend
      sp <- sprites.sprite(o.lib, o.frameAt(aniCount)) if sp.w == UnitX && sp.h == UnitY
    do blit(sp, x * UnitX - camX, (y + 1) * UnitY - sp.h - camY)

    // 第二遍：按行画高物件 + 物品 + 角色
    val newHits = Vector.newBuilder[Hit[Sprite]]
    for y <- top - 1 to bottom + LongRows do
      for
        x <- left - WideCols to right + 2
        o <- s.map.objectAt(x, y)
        sp <- sprites.sprite(o.lib, o.frameAt(aniCount))
      do
        val sx = x * UnitX - camX
        if o.blend then lighter(blit(sp, sx + sp.ox - 2, y * UnitY - camY + sp.oy - 68))
        else if sp.w != UnitX || sp.h != UnitY then blit(sp, sx, (y + 1) * UnitY - sp.h - camY)
      if y <= bottom + 2 then
        itemsByRow.getOrElse(y, Nil).foreach(drawGroundItem)
        fieldsByRow.getOrElse(y, Nil).foreach(drawField(_, s.local))
        // 尸体先画
        actorsByRow.getOrElse(y, Nil).toList.sortBy(a => !a.dead).foreach { a =>
          drawActor(a, s, y).foreach(newHits += _)
        }
    hits = newHits.result()

    drawEffects(s)
    drawOverlays(s)

  private def blit(sp: Sprite, x: Double, y: Double): Unit =
    if x <= width && y <= height && x + sp.w >= 0 && y + sp.h >= 0 then
      ctx.drawImage(sp.img, sp.sx, sp.sy, sp.w, sp.h, math.round(x).toDouble, math.round(y).toDouble, sp.w, sp.h)

  /** 画一帧，有染色就画染好的（中毒、麻痹）。 */
  private def blitTinted(sp: Sprite, tint: Option[BodyTint], x: Double, y: Double): Unit =
    tint.flatMap(tinter(sp, _)) match
      case Some(c) =>
        if x <= width && y <= height && x + sp.w >= 0 && y + sp.h >= 0 then
          ctx.drawImage(c, 0, 0, sp.w, sp.h, math.round(x).toDouble, math.round(y).toDouble, sp.w, sp.h)
      case None => blit(sp, x, y)

  private def lighter(draw: => Unit): Unit =
    ctx.globalCompositeOperation = "lighter"
    draw
    ctx.globalCompositeOperation = "source-over"

  private def drawGround(map: MapData): Unit =
    val bw  = GroundBlock * UnitX
    val bh  = GroundBlock * UnitY
    val bx0 = math.floor(camX / bw).toInt
    val by0 = math.floor(camY / bh).toInt
    val bx1 = math.floor((camX + width) / bw).toInt
    val by1 = math.floor((camY + height) / bh).toInt
    for
      by <- by0 - 1 to by1 + 1
      bx <- bx0 - 1 to bx1 + 1
      if bx >= 0 && by >= 0 && bx * GroundBlock < map.width && by * GroundBlock < map.height
    do
      // 四周多一圈只为提前下载；多画 1 像素，避免缩放后块与块之间露缝
      val img     = ground(map.asset, bx, by)
      val visible = bx >= bx0 && bx <= bx1 && by >= by0 && by <= by1
      if visible then img.foreach(i => ctx.drawImage(i, bx * bw - camX, by * bh - camY, bw + 1.0, bh + 1.0))

  private def drawGroundItem(g: GroundView): Unit =
    val look = g.thing match
      case GroundThing.Coins(n) => Some(Frames.goldLook(n.value))
      case GroundThing.Item(n, _) => catalog.item(n).map(_.look)
    look.flatMap(sprites.sprite("dnitems", _)).foreach { sp =>
      val cx = g.cell.x * UnitX - camX + UnitX / 2
      val cy = g.cell.y * UnitY - camY + UnitY / 2
      blit(sp, math.round(cx - sp.w / 2.0).toDouble, math.round(cy - sp.h / 2.0).toDouble)
    }

  /** 地上持续的法术（原版 ClEvent.pas：帧每 20 毫秒进一；火墙 1630 起 6 张、两帧换一张，困魔咒光圈 1390 起 10 张；都是叠加混合）。 */
  private def drawField(f: FieldView, local: Double): Unit =
    val tick = (local / 20).toLong
    val idx = f.kind match
      case FieldKind.Fire        => 1630 + ((tick / 2) % 6).toInt
      case FieldKind.HolyCurtain => 1390 + (tick % 10).toInt
    sprites.sprite("magic", idx).foreach { sp =>
      lighter(blit(sp, f.cell.x * UnitX - camX + sp.ox, f.cell.y * UnitY - camY + sp.oy))
    }

  // ---------------- 角色 ----------------

  private def drawActor(a: ActorView, s: Scene, row: Int): Option[Hit[Sprite]] =
    val now = s.server
    val p   = actorPixel(a, now)
    val dx  = math.round(p.x - camX).toDouble
    val dy  = math.round(p.y - camY).toDouble
    val ghost = a.id == s.me && s.meInvisible
    if ghost then ctx.globalAlpha = 0.45
    val body = a.look match
      case h: Look.Human    => drawHuman(a, h, now, dx, dy)
      case m: Look.Monster  => drawMonster(a, m, now, dx, dy)
      case Look.Npc(ref) =>
        val off = catalog.merchant(ref).fold(0)(m => Frames.npcOffset(m.appearance))
        val sp  = sprites.sprite("npc", off + ((s.local / 200).toLong % 4).toInt)
        sp.foreach(b => blit(b, dx + b.ox, dy + b.oy))
        sp
    ctx.globalAlpha = 1
    // 魔法盾：谁开着都画（原版 HumActor.pas 的 STATE_BUBBLEDEFENCEUP，#1297）
    if a.auras(Aura.MagicShield) && !a.dead then
      sprites.sprite("magic", 3890 + ((s.local / 100).toLong % 3).toInt).foreach(sp => lighter(blit(sp, dx + sp.ox, dy + sp.oy)))
    body.filter(_ => !a.dead).map(b => Hit(a.id, dx + b.ox, dy + b.oy, b.w, b.h, row, b))

  private def drawHuman(a: ActorView, h: Look.Human, now: Millis, dx: Double, dy: Double): Option[Sprite] =
    val frame  = Frames.human(a.action, a.dir, now, a.dead)
    val sex    = h.sex.code
    val dress  = h.dress.flatMap(catalog.item).fold(0)(_.shape) * 2 + sex
    val body   = sprites.sprite("hum", dress * Frames.HumanFrames + frame).orElse(sprites.sprite("hum", sex * Frames.HumanFrames + frame))
    val weapon = h.weapon.flatMap(catalog.item).flatMap(w => sprites.sprite("weapon", (w.shape * 2 + sex) * Frames.HumanFrames + frame))
    val hair   = Option.when(h.hair > 0)(()).flatMap(_ => sprites.sprite("hair", (h.hair * 2 + sex) * Frames.HumanFrames + frame))
    val front  = Frames.weaponInFront(h.sex, frame)
    // 原版 HumActor.DrawChr：身子和头发染色，武器不染
    val tint   = Affliction.tint(a.afflictions).filter(_ => !a.dead)
    def put(sp: Option[Sprite], t: Option[BodyTint]): Unit = sp.foreach(x => blitTinted(x, t, dx + x.ox, dy + x.oy))
    if !front then put(weapon, None)
    put(body, tint)
    put(hair, tint)
    if front then put(weapon, None)
    // 剑术刀光
    val act = a.action
    if act.kind == ActKind.Heavy && now - act.startedAt < act.duration then
      act.hit.flatMap(Frames.swordLight).foreach { base =>
        val f = math.min(5, ((now - act.startedAt).toDouble / act.duration * 6).toInt)
        sprites.sprite("magic", base + a.dir.code * 10 + f).foreach(sp => lighter(blit(sp, dx + sp.ox, dy + sp.oy)))
      }
    body

  private def drawMonster(a: ActorView, m: Look.Monster, now: Millis, dx: Double, dy: Double): Option[Sprite] =
    // 埋在地下的怪看不见（钻回去的动画放完才算埋好）
    if m.cover == Cover.Buried && a.action.kind != ActKind.DigDown then None
    else
      for
        d  <- catalog.monster(m.kind)
        t  <- catalog.actions(d)
        f   = Frames.monster(t, mir.data.MonsterLook.of(d), a.action, a.dir, now, a.dead, m.cover)
        sp <- sprites.sprite(d.lib, d.offset + f)
      yield
        blitTinted(sp, Affliction.tint(a.afflictions).filter(_ => !a.dead), dx + sp.ox, dy + sp.oy)
        sp

  // ---------------- 特效 ----------------

  private def drawEffects(s: Scene): Unit =
    val now = s.server
    for
      e  <- s.fx.effects
      p  <- effectPos(e, s)
      f   = math.min(e.frames - 1, (math.max(0L, now - e.startedAt) / math.max(1, e.frameTime) % math.max(1, e.frames)).toInt)
      sp <- sprites.sprite(e.lib, e.start + f)
    do
      val x = math.round(p.x - camX + sp.ox).toDouble
      val y = math.round(p.y - camY + sp.oy).toDouble
      if e.blend then lighter(blit(sp, x, y)) else blit(sp, x, y)

  private def effectPos(e: EffectView, s: Scene): Option[Px] =
    val target = e.at match
      case Anchor.OnActor(id) => s.actors.find(_.id == id).map(actorPixel(_, s.server))
      case Anchor.OnCell(c)   => Some(Px(c.x * UnitX.toDouble, c.y * UnitY.toDouble))
    target.map { t =>
      e.from.fold(t) { from =>
        val k  = math.min(1.0, (s.server - e.startedAt).toDouble / math.max(1, e.duration))
        val fx = from.x * UnitX.toDouble
        val fy = from.y * UnitY.toDouble
        Px(fx + (t.x - fx) * k, fy + (t.y - fy) * k)
      }
    }

  // ---------------- 名字、血条、飘字、气泡 ----------------

  private def drawOverlays(s: Scene): Unit =
    ctx.font = Font
    ctx.textAlign = "center"
    ctx.textBaseline = "bottom"
    val byId = s.actors.map(a => a.id -> a).toMap
    s.actors.filter(!_.dead).foreach { a =>
      val p = actorPixel(a, s.server)
      val x = math.round(p.x - camX + UnitX / 2).toDouble
      val y = math.round(p.y - camY).toDouble
      if x >= -50 && x <= width + 50 && y >= -50 && y <= height + 120 then
        val targeted = s.target.contains(a.id)
        if targeted then
          ctx.strokeStyle = "#ff5a3a"
          ctx.lineWidth = 2
          ctx.beginPath()
          ctx.ellipse(x, y + UnitY / 2, 22, 10, 0, 0, math.Pi * 2)
          ctx.stroke()
        nameOf(a, targeted, s.options).foreach((name, color) => text(name, x, y - 66, color))
        if s.options.showHpBars && !a.look.isInstanceOf[Look.Npc] then
          val ratio = if a.maxHp.value <= 0 then 0.0 else math.max(0.0, math.min(1.0, a.hp.value.toDouble / a.maxHp.value))
          ctx.fillStyle = "rgba(0,0,0,.6)"
          ctx.fillRect(x - 16, y - 62, 32, 4)
          ctx.fillStyle = a.look match
            case Look.Monster(_, _, None, _) => "#e74c3c"
            case _                        => "#2ecc71"
          ctx.fillRect(x - 16, y - 62, math.round(32 * ratio).toDouble, 4)
    }
    if s.options.showItemNames then
      s.ground.foreach { g =>
        val x = g.cell.x * UnitX - camX + UnitX / 2
        val y = g.cell.y * UnitY - camY + 4
        if x >= -40 && x <= width + 40 && y >= -20 && y <= height + 20 then
          val (name, color) = g.thing match
            case GroundThing.Coins(n) => (s"金币 ${n.value}", "#ffe08a")
            case GroundThing.Item(n, _) if s.upgrades.contains(g.id) => (n.value, UpgradeItemColor)
            case GroundThing.Item(n, Some(_)) => (n.value, SpecialItemColor)
            case GroundThing.Item(n, None) =>
              val equip = catalog.item(n).map(ItemKind.of).exists(_.isInstanceOf[ItemKind.Equip])
              (n.value, if equip then "#ffd76b" else "#dddddd")
          text(name, x, y, color)
      }
    // 说话气泡
    s.fx.bubbles.foreach { b =>
      byId.get(b.actor).foreach { a =>
        val p = actorPixel(a, s.server)
        bubble(b.text, p.x - camX + UnitX / 2, p.y - camY - 84)
      }
    }
    // 飘字
    ctx.font = FloatFont
    s.fx.floaters.foreach { f =>
      byId.get(f.actor).foreach { a =>
        val p = actorPixel(a, s.server)
        val k = (s.local - f.born) / Transients.FloatMs
        text(f.text, p.x - camX + UnitX / 2, p.y - camY - 72 - k * 30, f.tone.color)
      }
    }

  private def nameOf(a: ActorView, targeted: Boolean, o: ViewOptions): Option[(String, String)] = a.look match
    case h: Look.Human => Some(h.name -> Renderer.pkColor(h.pk))
    case Look.Npc(ref) => catalog.merchant(ref).map(_.name -> "#7fdcff")
    case Look.Monster(kind, _, pet, mood) =>
      // 名字颜色：宝宝绿、发狂红（原版 0xF9）、被定住蓝紫（原版 0x7D，MonsterObject.GetChrColor）
      val color = (pet, mood) match
        case (Some(_), _)        => "#9cff8a"
        case (None, Mood.Crazy)  => "#ff4040"
        case (None, Mood.Seized) => "#a8a0ff"
        case (None, Mood.Calm)   => "#ffffff"
      Option.when(targeted || o.showMonsterNames || mood != Mood.Calm)(catalog.displayName(kind).value -> color)

  private def text(str: String, x: Double, y: Double, color: String): Unit =
    ctx.fillStyle = "#000"
    ctx.fillText(str, x + 1, y + 1)
    ctx.fillStyle = color
    ctx.fillText(str, x, y)

  /** 说话气泡：最多两行，每行约 12 个字。 */
  private def bubble(str: String, x: Double, y: Double): Unit =
    ctx.font = Font
    val lines = str.grouped(12).take(2).toList
    val w     = lines.map(l => ctx.measureText(l).width).maxOption.getOrElse(0.0) + 10
    val h     = lines.size * 15 + 6
    ctx.fillStyle = "rgba(0,0,0,.6)"
    ctx.fillRect(x - w / 2, y - h, w, h)
    lines.zipWithIndex.foreach((l, i) => text(l, x, y - h + 18 + i * 15, "#ffffff"))

object Renderer:
  /** 高物件可能从屏幕下方很远的格子往上画 */
  val LongRows = 22
  val WideCols = 10
  val Font      = "12px \"PingFang SC\",\"Microsoft YaHei\",sans-serif"
  val FloatFont = "bold 14px sans-serif"
  /** 极品的名字颜色（地上、说明标题，#1191）：亮青色，和普通装备的金黄、杂物的白分开 */
  val SpecialItemColor = "#3cf0ff"
  /** 比身上同部位那件强的装备（#1200）：粉紫色；既是极品又是提升时用它（更值得捡） */
  val UpgradeItemColor = "#ff7cf5"

  /** 玩家名字颜色按 PK 身份（#1225，原版 btPKFlagNameColor $2F 灰、btPKLevel1NameColor $FB 黄、btPKLevel2NameColor $F9 红） */
  def pkColor(s: PkStanding): String = s match
    case PkStanding.Clean   => "#ffffff"
    case PkStanding.Brawler => "#a8a8a8"
    case PkStanding.Yellow  => "#ffff00"
    case PkStanding.Red     => "#ff4040"

