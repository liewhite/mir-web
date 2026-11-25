package mir.server.http

import zio.http.*

/** JSON 回应（不缓存）。 */
private[http] object JsonReply:
  def apply(status: Status, body: String): Response =
    Response(status = status, body = Body.fromString(body))
      .addHeader("content-type", "application/json")
      .addHeader("cache-control", "no-store")

  val unauthorized: Response = apply(Status.Unauthorized, """{"error":"login required"}""")
