package mir.client.ui

import mir.client.assets.Assets
import mir.client.net.AccountApi
import mir.protocol.SessionCmd
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.concurrent.Future

/**
 * 进游戏前的加载页（index.html 里的 #loading）：加载进度、出错原因；没登录时先进原版登录画面（ui.LoginScene，能注册）。
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

  /** 没登录就进登录画面（加载页先收起），登录或注册成功、石门开完才返回。 */
  def ensureLogin(): Future[Unit] =
    AccountApi.loggedIn().flatMap { ok =>
      if ok then Future.unit
      else
        $[dom.HTMLElement]("#loading").classList.add("hidden")
        for
          assets <- Assets.load()
          _      <- LoginScene($[dom.HTMLCanvasElement]("#game"), assets).run()
        yield $[dom.HTMLElement]("#loading").classList.remove("hidden")
    }

  /** 加载页上的状态文字（进游戏前的各步、出错原因）。 */
  def status(text: String): Unit = $[dom.HTMLElement]("#status").textContent = text
