package mir.client

import mir.core.Build
import org.scalajs.dom

import scala.scalajs.js
import scala.util.control.NonFatal

/**
 * 把玩家浏览器里的报错上报给服务端（`POST api/client-error`，#1228），服务端打进日志，巡检据此发现手机上的 bug。
 * 来源：没人接的异常（window error）、没人接的 Promise 失败、页面里的脚本/样式加载失败、主循环和启动时接住的异常（`caught`）。
 *
 * 同一条消息本页只报一次，每页最多 `MaxPerPage` 条；上报本身失败就算了（不重试、不再报错，免得兜圈子）。
 * 本地模式（?local）不装：没有服务端可报。
 */
object ErrorReport:
  val MaxPerPage = 20
  private val MaxStackLines = 8

  private val sent   = scala.collection.mutable.Set.empty[String]
  private var active = false
  /** 当时在玩的角色名（客户端建好后由 Main 用 playingAs 接上） */
  private var character: () => Option[String] = () => None

  /** 报错时从哪里取当前角色名 */
  def playingAs(who: () => Option[String]): Unit = character = who

  def install(): Unit =
    active = true
    // 捕获阶段才收得到 <script>/<link>/<img> 的加载失败（它们不冒泡）
    dom.window.addEventListener(
      "error",
      (e: dom.Event) =>
        // ErrorEvent 在 scalajs-dom 里是 trait，不能模式匹配，直接用 JS 的 instanceof
        if js.special.instanceof(e, js.Dynamic.global.ErrorEvent) then
          val ee = e.asInstanceOf[dom.ErrorEvent]
          send(ee.message, stackOf(ee.asInstanceOf[js.Dynamic].error))
        else loadFailure(e.target)
      ,
      useCapture = true
    )
    dom.window.addEventListener(
      "unhandledrejection",
      (e: dom.Event) =>
        val reason = e.asInstanceOf[js.Dynamic].reason
        send(s"未处理的 Promise 失败：${describe(reason)}", stackOf(reason))
    )

  /** 代码里接住、但不该发生的异常（主循环、启动）。 */
  def caught(where: String, e: Throwable): Unit =
    dom.console.error(s"$where 出错", e.toString)
    send(s"$where 出错：$e", e.getStackTrace.iterator.map(_.toString).take(MaxStackLines).mkString("\n"))

  /** 其他值得服务端知道的怪事（如看不懂的服务端消息）；按 message 去重，detail 只附在后面。 */
  def notice(message: String, detail: String): Unit = send(s"$message ｜ $detail", "", Some(message))

  private def loadFailure(target: dom.EventTarget): Unit = target match
    case s: dom.HTMLScriptElement => send(s"脚本加载失败：${s.src}", "")
    case l: dom.HTMLLinkElement   => send(s"样式加载失败：${l.href}", "")
    case i: dom.HTMLImageElement  => send(s"图片加载失败：${i.src}", "")
    case _                        => ()

  /** dedupe：按什么去重（默认整条消息） */
  private def send(message: String, stack: String, dedupe: Option[String] = None): Unit =
    val key = dedupe.getOrElse(message).take(200)
    if active && sent.size < MaxPerPage && !sent.contains(key) then
      sent += key
      try
        val payload = js.JSON.stringify(
          js.Dynamic.literal(
            message = message.take(1000),
            stack = stack.linesIterator.take(MaxStackLines).map(_.take(300)).mkString("\n"),
            char = character().getOrElse(""),
            version = Build.commit
          )
        )
        dom.fetch(
          "api/client-error",
          new dom.RequestInit {
            method = dom.HttpMethod.POST
            credentials = dom.RequestCredentials.`same-origin`
            headers = js.Dictionary("content-type" -> "application/json")
            body = payload
            keepalive = true
          }
        ).`catch`((_: Any) => ()): Unit
      catch case NonFatal(_) => ()

  /** JS 错误对象的 stack（Scala 异常用它的 getStackTrace） */
  private def stackOf(err: js.Dynamic): String =
    if js.isUndefined(err) || err == null then ""
    else
      (err: Any) match
        case t: Throwable => t.getStackTrace.iterator.map(_.toString).take(MaxStackLines).mkString("\n")
        case _ =>
          val s = err.stack
          // Chrome 的 stack 第一行是「类型: 消息」，消息里已有，只留帧（Chrome「at …」、Safari/Firefox「函数@地址」）
          if js.typeOf(s) == "string" then
            s.asInstanceOf[String].linesIterator.dropWhile(l => !l.trim.startsWith("at ") && !l.contains("@")).mkString("\n")
          else ""

  private def describe(v: js.Dynamic): String =
    if js.isUndefined(v) || v == null then String.valueOf(v)
    else
      (v: Any) match
        case t: Throwable => t.toString
        case _ =>
          val m = v.message
          if js.typeOf(m) == "string" then m.asInstanceOf[String] else js.Dynamic.global.String(v).asInstanceOf[String]
