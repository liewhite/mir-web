package mir.server

import mir.protocol.*
import zio.json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.util.concurrent.{CompletionStage, LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec

/** 测试用的 HTTP 小工具。 */
final class Http(port: Int):
  val client: HttpClient = HttpClient.newHttpClient()
  def uri(path: String): URI = URI.create(s"http://127.0.0.1:$port$path")

  def get(path: String, headers: (String, String)*): HttpResponse[String] =
    val b = HttpRequest.newBuilder(uri(path)).GET()
    headers.foreach((k, v) => b.header(k, v))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  def post(path: String, body: String, headers: (String, String)*): HttpResponse[String] =
    val b = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body))
    headers.foreach((k, v) => b.header(k, v))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  def delete(path: String, headers: (String, String)*): HttpResponse[String] =
    val b = HttpRequest.newBuilder(uri(path)).DELETE()
    headers.foreach((k, v) => b.header(k, v))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  /** 登录，返回 cookie（「名=值」）。 */
  def login(account: String, password: String): Option[String] =
    val r = post("/api/login", Map("account" -> account, "password" -> password).toJson)
    Option.when(r.statusCode == 200)(r.headers.firstValue("set-cookie").get.split(';').head)

/** 一个机器人客户端：WebSocket 连上，收到的消息排队，按条件等。 */
final class Bot(http: Http, cookie: String):
  val inbox = LinkedBlockingQueue[ServerMsg]()
  @volatile var closed = false
  /** 服务端关连接时带的关闭码（#1290：停服重启是 1012） */
  @volatile var closeCode: Option[Int] = None

  private val ws: WebSocket = http.client.newWebSocketBuilder()
    .header("Cookie", cookie)
    .buildAsync(URI.create(http.uri("/ws").toString.replace("http:", "ws:")), new WebSocket.Listener:
      private val buf = StringBuilder()
      override def onText(w: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
        buf.append(data)
        if last then
          buf.toString.fromJson[ServerMsg].fold(e => throw AssertionError(s"$e: $buf"), inbox.add)
          buf.clear()
        w.request(1)
        null
      override def onClose(w: WebSocket, code: Int, reason: String): CompletionStage[?] =
        closeCode = Some(code)
        closed = true
        null
    ).get(5, TimeUnit.SECONDS)

  def send(cmd: ClientCmd): Unit = ws.sendText((cmd: ClientCmd).toJson, true).get(5, TimeUnit.SECONDS): Unit

  /** 收到了还没被 await 取走的消息 */
  private val pending = scala.collection.mutable.ArrayBuffer.empty[ServerMsg]

  /** 等第一条满足 f 的消息（早先收到、还没被取走的也算），取走它，别的留着。 */
  def await[A](what: String, timeoutMs: Long = 5000)(f: PartialFunction[ServerMsg, A]): A =
    val deadline = System.currentTimeMillis() + timeoutMs
    @tailrec def loop(): A =
      val i = pending.indexWhere(f.isDefinedAt)
      if i >= 0 then f(pending.remove(i))
      else
        val left = deadline - System.currentTimeMillis()
        Option(inbox.poll(math.max(1, left), TimeUnit.MILLISECONDS)) match
          case Some(m) =>
            pending += m
            loop()
          case None => throw AssertionError(s"等不到：$what（收到过 ${pending.takeRight(8).mkString("; ")}）")
    loop()

  def drop(): Unit = ws.abort()
