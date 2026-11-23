package mir.client.ui

import mir.core.CharId
import mir.model.{Job, Sex}
import mir.protocol.{CharSummary, SessionCmd}
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * 进游戏前的网页表单（index.html 里的 #loading）：登录（HTTP，拿到 cookie 素材才给下载）、选角色或建角色。
 * 原版风格的选人界面在 #1156 做，这里是能用的最简版。
 */
final class Lobby(send: SessionCmd => Unit, enter: CharId => Unit):
  import Lobby.*

  def status(text: String): Unit = Lobby.status(text)

  def fail(text: String): Unit =
    show()
    status(text)

  def show(): Unit = $[dom.HTMLElement]("#loading").classList.remove("hidden")
  def hide(): Unit = $[dom.HTMLElement]("#loading").classList.add("hidden")

  /** 没登录就显示登录表单，登录成功才返回。 */
  def ensureLogin(): Future[Unit] =
    dom.fetch("api/me", new dom.RequestInit { credentials = dom.RequestCredentials.`same-origin`; cache = dom.RequestCache.`no-store` })
      .toFuture
      .flatMap(r => if r.ok then Future.unit else askLogin())

  private def askLogin(): Future[Unit] =
    val form = $[dom.HTMLFormElement]("#login")
    form.classList.remove("hidden")
    status("")
    val done = Promise[Unit]()
    form.onsubmit = e =>
      e.preventDefault()
      val payload = js.JSON.stringify(js.Dynamic.literal(
        account = $[dom.HTMLInputElement]("#account").value.trim,
        password = $[dom.HTMLInputElement]("#password").value
      ))
      dom.fetch("api/login", new dom.RequestInit {
        method = dom.HttpMethod.POST
        credentials = dom.RequestCredentials.`same-origin`
        headers = js.Dictionary("content-type" -> "application/json")
        body = payload
      }).toFuture.foreach { r =>
        if r.ok then
          form.classList.add("hidden")
          done.trySuccess(()): Unit
        else $[dom.HTMLElement]("#login-error").textContent = if r.status == 429 then "试太多次了，过一会再试" else "账号或密码不对"
      }
    done.future

  def loggedIn(account: String): Unit =
    status(s"$account，选择角色")
    send(SessionCmd.ListChars)

  /** 列出角色（点名字进游戏）；不到两个时可以再建。 */
  def charList(chars: List[CharSummary]): Unit =
    show()
    val list = $[dom.HTMLElement]("#chars")
    list.classList.remove("hidden")
    list.innerHTML = ""
    chars.foreach { c =>
      val b = dom.document.createElement("button").asInstanceOf[dom.HTMLButtonElement]
      b.className = "char"
      b.textContent = s"${c.name}  ${c.job.label} ${c.sex.label}  ${c.level} 级"
      b.onclick = _ =>
        list.classList.add("hidden")
        $[dom.HTMLElement]("#create").classList.add("hidden")
        status("进入游戏…")
        enter(c.id)
      list.appendChild(b)
    }
    val form = $[dom.HTMLFormElement]("#create")
    if chars.size < Lobby.MaxChars then form.classList.remove("hidden") else form.classList.add("hidden")
    form.onsubmit = e =>
      e.preventDefault()
      val fd   = new dom.FormData(form)
      val name = fd.get("name").toString.trim
      if name.nonEmpty then
        val job = Job.values.find(_.code.toString == fd.get("job").toString).getOrElse(Job.Warrior)
        val sex = Sex.values.find(_.code.toString == fd.get("sex").toString).getOrElse(Sex.Male)
        send(SessionCmd.CreateChar(name, job, sex))

object Lobby:
  /** 每个账号最多两个角色（原版选人界面两个位） */
  val MaxChars = 2

  private def $[T <: dom.Element](sel: String): T = dom.document.querySelector(sel).asInstanceOf[T]

  /** 加载页上的状态文字（进游戏前的各步、出错原因）。 */
  def status(text: String): Unit = $[dom.HTMLElement]("#status").textContent = text
