package mir.client.ui

import org.scalajs.dom

/** 问一次数量：标题、这一叠一共几个、选 n 个时的说明（如「得到 120 金币」）、确定后做什么。 */
final case class QuantityAsk(title: String, max: Int, note: Int => Option[String], ok: Int => Unit)

/**
 * 数量框（#1159，照 TS 版 ui/classic/quantity.ts）：卖、存、取一叠东西时问要几个，默认全部。原版不叠物品，没有这个窗，
 * 画成和传送窗同色调的面板。−10 −1 +1 +10 调数，「1 个」「一半」「全部」一键设；点数字可以直接输入；最下面取消、确定。
 */
final class QuantityPanel(ui: ClassicUi):
  import QuantityPanel.*
  import ui.g

  private var ask   = Option.empty[QuantityAsk]
  private var value = 1

  def isOpen: Boolean = ask.isDefined

  def show(a: QuantityAsk): Unit =
    ask = Some(a)
    value = a.max

  def close(): Unit = ask = None

  private def set(n: Int): Unit = ask.foreach(a => value = math.max(1, math.min(a.max, n)))

  def draw(): Unit = ask.foreach { a =>
    val s = math.min(ui.ctlScale, math.min((ui.W - 8) / Width, (ui.H - 8) / Height))
    val o = ui.panels.dialogOrigin(Width * s, Height * s)
    def toUi(r: Rect) = Rect(o.x + r.x * s, o.y + r.y * s, r.w * s, r.h * s)
    ui.area(toUi(Rect(0, 0, Width, Height)))
    val field = Rect(Pad, 32, 110, 22)
    def row(y: Double, items: List[(String, () => Unit, String)]): List[(Rect, String, () => Unit, String)] =
      val bw = (Width - Pad * 2 - Gap * (items.size - 1)) / items.size
      items.zipWithIndex.map { case ((label, run, color), i) => (Rect(Pad + i * (bw + Gap), y, bw, BtnH), label, run, color) }
    val yellow  = "#ffe08a"
    val buttons =
      row(62, List(("−10", () => set(value - 10), yellow), ("−1", () => set(value - 1), yellow), ("+1", () => set(value + 1), yellow), ("+10", () => set(value + 10), yellow))) ++
        row(62 + BtnH + Gap, List(("1 个", () => set(1), yellow), ("一半", () => set((a.max + 1) / 2), yellow), ("全部", () => set(a.max), yellow))) ++
        row(62 + (BtnH + Gap) * 2, List(
          ("取消", () => close(), yellow),
          ("确定", () => { val n = value; close(); a.ok(n) }, "#9cff8a")
        ))
    g.scaled(o.x, o.y, s) {
      g.frame(Rect(0, 0, Width, Height), "rgba(16,11,6,.96)", "#8a6a3a")
      g.text(a.title, Pad, 10, yellow, bold = true)
      g.frame(field, "#000", "#c9a060")
      g.text(s"$value / ${a.max}", field.x + 6, field.y + 5, "#fff", bold = true)
      a.note(value).foreach(n => g.text(n, field.x + field.w + 8, field.y + 5, "#ffd96b"))
      buttons.foreach { (r, label, _, color) =>
        g.frame(r, if ui.isPressed(toUi(r)) then "#7a5a2a" else "#3a2a16", "#c9a060")
        g.text(label, r.x + (r.w - g.width(label)) / 2, r.y + (r.h - 14) / 2 + 1, color, bold = true)
      }
    }
    // 点数字直接输入（电脑上方便）
    ui.button(Button(toUi(field), () => typeNumber(a)))
    buttons.foreach((r, _, run, _) => ui.button(Button(toUi(r), run)))
  }

  private def typeNumber(a: QuantityAsk): Unit =
    Option(dom.window.prompt(s"${a.title}：输入数量（1–${a.max}）", value.toString)).flatMap(_.trim.toIntOption).foreach(set)

object QuantityPanel:
  val Width  = 252.0
  val Pad    = 10.0
  val BtnH   = 28.0
  val Gap    = 6.0
  val Height = 62 + (BtnH + Gap) * 3 + Pad - Gap
