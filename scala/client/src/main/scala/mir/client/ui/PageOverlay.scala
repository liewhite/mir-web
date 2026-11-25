package mir.client.ui

import org.scalajs.dom

import scala.scalajs.js

/**
 * 在游戏画面上盖一层整屏的网页（留言板、更新日志，#1306）。
 * 以前是新标签页打开，页面上的「回游戏」跳到首页又开了一个游戏，落在选人画面；
 * 手机上加到主屏幕的游戏只有一个窗口，更回不去。现在页面嵌在游戏里，游戏一直连着，
 * 页面里点「回游戏」（web/pages.js 发 close-page 消息）或 Esc 就收起，回到原来的画面。
 * 全局只有一层：整个页面生命周期里建一次，换场景、换角色都沿用。
 */
object PageOverlay:
  /** 页面通知游戏收起时发的消息（web/pages.js 里同一个字符串） */
  val CloseMessage = "close-page"

  private lazy val frame: dom.HTMLIFrameElement =
    val el = dom.document.createElement("iframe").asInstanceOf[dom.HTMLIFrameElement]
    el.id = "page-overlay"
    el.classList.add("hidden")
    dom.document.body.appendChild(el)
    dom.window.addEventListener(
      "message",
      (e: dom.MessageEvent) =>
        if e.origin == dom.window.location.origin && isClose(e.data) then close()
    )
    el

  private def isClose(data: Any): Boolean =
    js.typeOf(data) == "object" && data != null &&
      data.asInstanceOf[js.Dynamic].selectDynamic("mir").asInstanceOf[Any] == CloseMessage

  def isOpen: Boolean = !frame.classList.contains("hidden")

  /** 打开 page（每次都重新加载，看到最新的帖子和日志）。 */
  def open(page: WebPage): Unit =
    frame.src = page.file
    frame.title = page.title
    frame.classList.remove("hidden")
    frame.focus()

  def close(): Unit =
    frame.classList.add("hidden")
    frame.src = "about:blank"
    dom.window.focus()
