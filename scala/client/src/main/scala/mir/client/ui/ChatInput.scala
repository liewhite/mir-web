package mir.client.ui

import org.scalajs.dom

/**
 * 聊天输入框：画布上打不了字（手机要弹系统键盘），用一个网页输入框盖在底栏聊天区上。
 * 回车发出（「!」开头是全图喊话）并收起；Esc 或点别处收起。
 */
final class ChatInput(say: String => Unit):
  private val input: dom.HTMLInputElement =
    val el = dom.document.createElement("input").asInstanceOf[dom.HTMLInputElement]
    el.id = "chat"
    el.maxLength = ChatInput.MaxLength
    el.placeholder = "说话（回车发送，! 开头全图喊话）"
    el.setAttribute("enterkeyhint", "send")
    el.setAttribute("autocomplete", "off")
    el.classList.add("hidden")
    dom.document.body.appendChild(el)
    el

  input.addEventListener(
    "keydown",
    (e: dom.KeyboardEvent) =>
      // 打字时的按键不给游戏（F 键、数字键、空格都是快捷键）
      e.stopPropagation()
      e.key match
        case "Enter" =>
          e.preventDefault()
          val text = input.value.trim
          if text.nonEmpty then say(text)
          close()
        case "Escape" => close()
        case _        => ()
  )
  input.addEventListener("blur", (_: dom.Event) => close())

  def isOpen: Boolean = !input.classList.contains("hidden")

  /** 在 (x, y) 处（CSS 像素）打开，宽 w、高 h。 */
  def open(x: Double, y: Double, w: Double, h: Double): Unit =
    val st = input.style
    st.left = s"${x}px"
    st.top = s"${y}px"
    st.width = s"${w}px"
    st.height = s"${math.max(h, ChatInput.MinHeightCss)}px"
    input.value = ""
    input.classList.remove("hidden")
    input.focus()

  def close(): Unit =
    input.classList.add("hidden")
    input.blur()

object ChatInput:
  val MaxLength = 100
  /** 手机上底栏缩得很小，输入框至少这么高才点得到、看得清 */
  val MinHeightCss = 28.0
