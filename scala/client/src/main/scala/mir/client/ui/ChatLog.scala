package mir.client.ui

/**
 * 聊天栏的记录和翻看位置（#1364，照原版 DrawScrn.pas 的 ChatStrs / ChatBoardTop）：消息进来时按聊天框宽度折好行再存，
 * 最多留 keep 行，底栏一次显示 view 行。
 *
 * 翻看位置记成「离最底下往上翻了几行」（back）：0 就是跟着最新的走；往回翻着时新消息进来，back 跟着加，
 * 看着的那几行不动（原版只在看着最底下时才跟随），同时记下有几条没看的新消息，翻回最底下清零。
 * 纯逻辑，不碰画布和 DOM；量字宽由调用方给（wrap 的 measure）。
 */
final class ChatLog(keep: Int, val view: Int):
  private var all    = Vector.empty[ChatLine]
  private var back   = 0
  private var unseen = 0

  /** 全部留着的行（已折好） */
  def rows: Vector[ChatLine] = all

  /** 往回翻了几行（0 = 在最底下，跟着新消息走） */
  def scrolledBack: Int = back

  /** 往回翻着时进来、还没看的新消息条数 */
  def unseenCount: Int = unseen

  /** 现在聊天框里显示的几行（上到下） */
  def visible: Vector[ChatLine] =
    val end = all.size - back
    all.slice(math.max(0, end - view), end)

  /** 进来一条消息（折好的几行）。 */
  def add(lines: Vector[ChatLine]): Unit = if lines.nonEmpty then
    all = (all ++ lines).takeRight(keep)
    if back > 0 then
      back += lines.size
      unseen += 1
    back = clamp(back)

  /** 翻 n 行：正数往回（更早的），负数往新的；翻到最底下就清掉「新消息」。 */
  def scroll(n: Int): Unit =
    back = clamp(back + n)
    if back == 0 then unseen = 0

  /** 往回翻一屏（原版 PageUp）。 */
  def pageUp(): Unit = scroll(view)

  /** 往新的翻一屏（原版 PageDown）。 */
  def pageDown(): Unit = scroll(-view)

  /** 回到最底下，接着跟新消息走。 */
  def toBottom(): Unit = scroll(-back)

  /** 最多往回翻到最早那行在最上面 */
  private def clamp(b: Int): Int = math.max(0, math.min(b, all.size - view))

object ChatLog:
  /** 超过宽度的一行按字折成几行（measure 量字宽）；空串算一行。 */
  def wrap(text: String, w: Double, measure: String => Double): Vector[String] =
    if measure(text) <= w then Vector(text)
    else
      text.foldLeft(Vector("")) { (acc, c) =>
        val next = acc.last + c
        if measure(next) > w && acc.last.nonEmpty then acc :+ c.toString else acc.init :+ next
      }

  /**
   * 拖动、滚轮翻聊天栏：把一段段的移动（界面像素）攒起来，每满 step 像素翻一行，余下的留着接着攒
   * （触控板一次只给几个像素，不攒就翻不动）。
   */
  final class Steps(step: Double):
    private var acc = 0.0

    /** 移动 d 像素，返回这次该翻几行（正负随 d） */
    def move(d: Double): Int =
      acc += d
      val n = (acc / step).toInt
      acc -= n * step
      n

    def reset(): Unit = acc = 0
