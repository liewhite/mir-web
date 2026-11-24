package mir.client.ui

import org.scalajs.dom

/**
 * 聊天输入框：画布上打不了字（手机要弹系统键盘），用一个网页输入框盖在底栏聊天区上。
 * 回车发出（「!」开头是全图喊话，「!!」开头队伍聊天，「/名字 」开头私聊）并收起；Esc 或点别处收起。
 * 也用来问别的一行字（组队输名字）：open 时给提示、预填和回车后做什么。
 */
final class ChatInput(say: String => Unit):
  private var done: String => Unit = say

  private val input: dom.HTMLInputElement =
    val el = dom.document.createElement("input").asInstanceOf[dom.HTMLInputElement]
    el.id = "chat"
    el.maxLength = ChatInput.MaxLength
    el.placeholder = ChatInput.ChatHint
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
          if text.nonEmpty then done(text)
          close()
        case "Escape" => close()
        case _        => ()
  )
  input.addEventListener("blur", (_: dom.Event) => close())

  def isOpen: Boolean = !input.classList.contains("hidden")

  /** 在 (x, y) 处（CSS 像素）打开，宽 w、高 h；hint 是空着时的提示，prefill 预填，回车（不空）后交给 onEnter。 */
  def open(x: Double, y: Double, w: Double, h: Double, hint: String = ChatInput.ChatHint, prefill: String = "", onEnter: String => Unit = say): Unit =
    done = onEnter
    input.placeholder = hint
    val st = input.style
    st.left = s"${x}px"
    st.top = s"${y}px"
    st.width = s"${w}px"
    st.height = s"${math.max(h, ChatInput.MinHeightCss)}px"
    input.value = prefill
    input.classList.remove("hidden")
    input.focus()
    input.setSelectionRange(prefill.length, prefill.length) // 光标放到预填的字后面（Safari 聚焦时会放到最前）

  def close(): Unit =
    input.classList.add("hidden")
    input.blur()

object ChatInput:
  val ChatHint  = "说话（回车发送，! 开头全图喊话，!! 队伍聊天，/名字 空格 私聊）"
  val MaxLength = 100
  /** 手机上底栏缩得很小，输入框至少这么高才点得到、看得清 */
  val MinHeightCss = 28.0
