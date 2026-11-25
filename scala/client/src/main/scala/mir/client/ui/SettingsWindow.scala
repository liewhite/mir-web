package mir.client.ui

import mir.model.PickupFloor
import mir.protocol.GameCmd

/**
 * 便利设置（原版靠外挂的功能内置成开关，画在原版对话框底图 204 上）：玩法类发 UpdateSettings 存角色上，
 * 画面声音类存本机（SettingRow）。分「通用」和战士、法师、道士自动技能几页（SettingTab）。底部列出键位（UiAction.Help）。
 */
final class SettingsWindow(ui: ClassicUi) extends ToggleWindow:
  import ui.g

  /** 档位滑条上相邻两挡隔多宽 */
  private val TierGap = 56.0

  /** 当前分页（通用 / 战士 / 法师 / 道士） */
  private var tab: SettingTab = SettingTab.General

  def draw(m: Me): Unit =
    val (sw, sh) = g.size(Gfx.Main, 204).getOrElse((608, 327))
    val o        = ui.dialogOrigin(sw, sh)
    ui.area(Rect(o.x, o.y, sw, sh))
    g.img(Gfx.Main, 204, o.x, o.y)
    g.rect(o.x + 50, o.y + 6, 70, 20, "#2a2a2a")
    g.text("便利设置", o.x + 56, o.y + 10, "#ffe08a", bold = true)
    // 分页标签，排在标题右边
    SettingRow.tabs.map(_._1).zipWithIndex.foreach { (t, i) =>
      val r = Rect(o.x + 140 + i * 62, o.y + 6, 56, 20)
      g.rect(r.x, r.y, r.w, r.h, if t == tab then "#6a4a1a" else "#2a2a2a")
      g.text(t.label, r.x + 14, r.y + 4, if t == tab then "#ffe08a" else "#c0c0c0", bold = t == tab)
      ui.hit(r) { tab = t }
    }
    val rowH = 19.0
    val mid  = (rowH - 14) / 2
    val rows = SettingRow.tabs.collectFirst { case (t, rows) if t == tab => rows }.getOrElse(Nil).filter(_.shown(m.view.settings))
    rows.zipWithIndex.foreach { (row, i) =>
      val ly = o.y + 40 + i * rowH
      /** 画一个勾选框加文字，点整段切换；返回占了多宽。 */
      def checkAt(x: Double, label: String, on: Boolean, width: Double)(flip: => Unit): Double =
        val box = Rect(x, ly + mid, 14, 14)
        g.rect(box.x, box.y, box.w, box.h, "#000")
        g.rect(box.x + 1, box.y + 1, box.w - 2, box.h - 2, "#4a3a20")
        if on then g.text("✓", box.x + 2, box.y + 1, "#ff0", bold = true)
        g.text(label, x + 22, ly + mid + 1)
        val w = width.max(22 + g.width(label) + 10)
        ui.hit(Rect(box.x, ly, w, rowH - 1))(flip)
        w
      def check(on: Boolean)(flip: => Unit): Unit =
        checkAt(o.x + 30, row.label, on, math.min(400, sw - 40))(flip): Unit
      row match
        case SettingRow.GameToggle(_, get, set) =>
          val s = m.view.settings
          check(get(s))(ui.send(GameCmd.UpdateSettings(set(s, !get(s)))))
        case SettingRow.GameChecks(_, boxes) =>
          // 总开关下面的分类，缩进一格、一行排开
          val s  = m.view.settings
          val x0 = o.x + 52
          g.text(row.label, x0, ly + mid + 1)
          boxes.foldLeft(x0 + g.width(row.label) + 4) { (x, b) =>
            x + checkAt(x, b.label, b.get(s), 0)(ui.send(GameCmd.UpdateSettings(b.set(s, !b.get(s)))))
          }: Unit
        case SettingRow.GameTier(_) =>
          // 档位滑条（#1301）：一条轨道上几个挡，点哪挡滑块就到哪；选中的挡往右亮起来（该档及以上）
          val s     = m.view.settings
          val x0    = o.x + 52
          val tx    = x0 + g.width(row.label) + 12
          val cy    = ly + rowH / 2
          val stops = PickupFloor.values.toList
          val at    = stops.indexOf(s.pickupTier).max(0)
          def sx(i: Int): Double = tx + i * TierGap
          g.text(row.label, x0, ly + mid + 1)
          // 轨道画在相邻两挡的文字之间（不压字）
          (0 until stops.size - 1).foreach { i =>
            val x1 = sx(i) + 9 + g.width(stops(i).label) + 3
            g.rect(x1, cy - 1, sx(i + 1) - 7 - x1, 3, if i >= at then "#ffb040" else "#5a4a30")
          }
          stops.zipWithIndex.foreach { (floor, i) =>
            val on = i >= at
            g.circle(sx(i), cy, if i == at then 6 else 3, if i == at then "#ffe08a" else if on then "#ffb040" else "#5a4a30", Some("#000"))
            g.text(floor.label, sx(i) + 9, ly + mid + 1, if on then "#ffe08a" else "#909090", bold = i == at)
            ui.hit(Rect(sx(i) - 8, ly, TierGap, rowH - 1))(ui.send(GameCmd.UpdateSettings(s.copy(pickupTier = floor))))
          }
          g.text(if at == 0 then "（什么装备都捡）" else "及以上（比身上强的照捡）", sx(stops.size - 1) + TierGap - 12, ly + mid + 1, "#c0c0c0")
        case SettingRow.LocalToggle(_, get, set) =>
          val o2 = ui.options
          check(get(o2))(ui.env.saveOptions(set(o2, !get(o2))))
        case SettingRow.GamePercent(_, get, set) =>
          val s  = m.view.settings
          val v  = get(s)
          val vx = o.x + 360
          g.text(row.label, o.x + 52, ly + mid + 1)
          g.text("◀", vx, ly + mid + 1, "#ff0", bold = true)
          g.text(s"$v%", vx + 22, ly + mid + 1, "#fff", bold = true)
          g.text("▶", vx + 62, ly + mid + 1, "#ff0", bold = true)
          ui.hit(Rect(vx - 4, ly, 20, rowH - 1))(ui.send(GameCmd.UpdateSettings(set(s, SettingRow.stepPercent(v, up = false)))))
          ui.hit(Rect(vx + 58, ly, 20, rowH - 1))(ui.send(GameCmd.UpdateSettings(set(s, SettingRow.stepPercent(v, up = true)))))
    }
    g.text(UiAction.Help, o.x + 30, o.y + sh - 40, "#c0c0c0")
    ui.button(Button(Rect(o.x + 514, o.y + 287, 80, 34), () => close(), img = Some(361)))
    ui.button(Button(Rect(o.x + 584, o.y + 6, 16, 23), () => close(), img = Some(64)))
