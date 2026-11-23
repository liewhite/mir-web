package mir.client.ui

import mir.protocol.SessionCmd
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/**
 * 进游戏前的网页表单（index.html 里的 #loading）：加载进度、登录（HTTP，拿到 cookie 素材才给下载；网页表单浏览器能记密码）。
 * 选人、建角色是画布上的原版画面（ui.CharSelect）。
 */
final class Lobby(send: SessionCmd => Unit):
  import Lobby.*

  def status(text: String): Unit = Lobby.status(text)

  def fail(text: String): Unit =
    show()
    status(text)

  def show(): Unit = $[dom.HTMLElement]("#loading").classList.remove("hidden")
  def hide(): Unit = $[dom.HTMLElement]("#loading").classList.add("hidden")

  def loggedIn(account: String): Unit =
    status(s"$account，读取角色…")
    send(SessionCmd.ListChars)

object Lobby:
  private def $[T <: dom.Element](sel: String): T = dom.document.querySelector(sel).asInstanceOf[T]

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

  /** 加载页上的状态文字（进游戏前的各步、出错原因）。 */
  def status(text: String): Unit = $[dom.HTMLElement]("#status").textContent = text
