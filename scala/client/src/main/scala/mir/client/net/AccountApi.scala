package mir.client.net

import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.concurrent.Future
import scala.scalajs.js

/**
 * 账号的 HTTP 接口（服务端 HttpServer）：查登录没有、登录、注册、退出。登录和注册成功都由服务端设会话 cookie。
 * 失败给 Left(给玩家看的话)。
 */
object AccountApi:
  /** 失败时给玩家看什么：固定的一句，或服务端在 `{error}` 里写的原因（注册的格式不对、重名）。 */
  private enum Why:
    case Says(text: String)
    case FromServer

  def loggedIn(): Future[Boolean] =
    dom.fetch("api/me", init(dom.HttpMethod.GET, None)).toFuture.map(_.ok)

  /** 退出账号：服务端清会话 cookie。 */
  def logout(): Future[Unit] =
    dom.fetch("api/logout", init(dom.HttpMethod.POST, None)).toFuture.map(_ => ())

  def login(account: String, password: String): Future[Either[String, Unit]] =
    post("api/login", account, password) {
      case 429 => Why.Says("试太多次了，过一会再试")
      case _   => Why.Says("账号或密码不对")
    }

  /** 注册，成功即已登录。 */
  def register(account: String, password: String): Future[Either[String, Unit]] =
    post("api/register", account, password) {
      case 400 | 409 => Why.FromServer
      case 429       => Why.Says("注册太频繁，过一会再试")
      case _         => Why.Says("注册没成功，稍后再试")
    }

  private def init(m: dom.HttpMethod, payload: Option[String]): dom.RequestInit = new dom.RequestInit {
    method = m
    credentials = dom.RequestCredentials.`same-origin`
    cache = dom.RequestCache.`no-store`
    headers = js.Dictionary("content-type" -> "application/json")
    body = payload.fold[js.UndefOr[dom.BodyInit]](js.undefined)(p => p)
  }

  private def post(path: String, account: String, password: String)(why: Int => Why): Future[Either[String, Unit]] =
    val payload = js.JSON.stringify(js.Dynamic.literal(account = account, password = password))
    dom.fetch(path, init(dom.HttpMethod.POST, Some(payload))).toFuture
      .flatMap { r =>
        if r.ok then Future.successful(Right(()))
        else
          why(r.status) match
            case Why.Says(text) => Future.successful(Left(text))
            case Why.FromServer =>
              r.json().toFuture
                .map(j => Left(j.asInstanceOf[js.Dynamic].error.asInstanceOf[js.UndefOr[String]].getOrElse("注册没成功")))
                .recover(_ => Left("注册没成功"))
      }
      .recover(_ => Left("连不上服务器，稍后再试"))
