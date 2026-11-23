package mir.client.ui

import mir.core.SkillId
import mir.protocol.GameCmd

/** 技能快捷键对话框（照 FState.pas DKeySelDlg）：状态窗技能页点图标打开，选 F1–F8 或「无」。 */
final class KeyDialog(ui: ClassicUi) extends Window:
  import KeyDialog.*
  import ui.{catalog, g}

  private var skill = Option.empty[SkillId]

  def isOpen: Boolean         = skill.isDefined
  def close(): Unit           = skill = None
  def open(id: SkillId): Unit = skill = Some(id)

  def draw(m: Me): Unit = for
    id <- skill
    d  <- catalog.skill(id)
  do
    val (sw, sh) = g.size(Gfx.Main, 229).getOrElse((376, 179))
    val o        = ui.dialogOrigin(sw, sh)
    ui.area(Rect(o.x, o.y, sw, sh))
    g.img(Gfx.Main, 229, o.x, o.y)
    g.img("magicon", d.effect * 2, o.x + 52, o.y + 29)
    g.text(d.name, o.x + 100, o.y + 40, "#fff", bold = true)
    val current = m.view.skills.find(_.id == id).flatMap(_.key)
    KeyXs.zipWithIndex.foreach { (bx, i) =>
      val k = i + 1
      val r = Rect(o.x + bx, o.y + 83, 32, 30)
      g.img(Gfx.Main, 232 + i * 2 + (if current.contains(k) then 1 else 0), r.x, r.y)
      ui.hit(r)(setKey(id, Some(k)))
    }
    g.img(Gfx.Main, 230, o.x + 299, o.y + 83)
    ui.hit(Rect(o.x + 299, o.y + 83, 40, 30))(setKey(id, None))
    ui.button(Button(Rect(o.x + 222, o.y + 131, 80, 34), () => close(), img = Some(62)))

  private def setKey(id: SkillId, key: Option[Int]): Unit =
    ui.send(GameCmd.SetSkillKey(id, key))
    close()

object KeyDialog:
  /** F1-F8 按钮的横坐标 */
  val KeyXs = Vector(34, 66, 98, 130, 171, 203, 235, 267)
