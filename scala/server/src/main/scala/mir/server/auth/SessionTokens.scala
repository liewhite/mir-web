package mir.server.auth

import mir.server.AccountId

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.mutable

/**
 * 登录会话 cookie：`a.<账号编号>.<到期毫秒>.<HMAC-SHA256>`，服务端不存会话。
 * 要让全部会话失效就换 MIR_SESSION_SECRET。
 */
final class SessionTokens(secret: String, ttlMs: Long = SessionTokens.DefaultTtlMs):
  require(secret.length >= 16, "MIR_SESSION_SECRET 至少 16 个字符")
  private val key = SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256")

  private def sign(payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(key)
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(payload.getBytes(UTF_8)))

  def issue(account: AccountId, now: Long): String =
    val payload = s"a.${account.value}.${now + ttlMs}"
    s"$payload.${sign(payload)}"

  /** 签名对、没过期，返回账号编号。 */
  def verify(token: String, now: Long): Option[AccountId] = token.split('.') match
    case Array("a", id, exp, sig) =>
      val ok = MessageDigest.isEqual(sig.getBytes(UTF_8), sign(s"a.$id.$exp").getBytes(UTF_8))
      for
        _   <- Option.when(ok)(())
        e   <- exp.toLongOption if e > now
        acc <- id.toLongOption
      yield AccountId(acc)
    case _ => None

object SessionTokens:
  val Days: Int          = 90
  val DefaultTtlMs: Long = Days * 86_400_000L
  val Cookie: String     = "mir_sess"

/** 登录失败限流：每个 IP 10 分钟内最多失败 10 次（与旧服务端一样）。 */
final class LoginLimiter(maxFails: Int = 10, windowMs: Long = 600_000L):
  private val fails = mutable.Map.empty[String, List[Long]]

  private def recent(ip: String, now: Long): List[Long] =
    val l = fails.getOrElse(ip, Nil).filter(now - _ < windowMs)
    if l.isEmpty then fails.remove(ip) else fails(ip) = l
    l

  def blocked(ip: String, now: Long): Boolean = synchronized(recent(ip, now).size >= maxFails)

  def failed(ip: String, now: Long): Unit = synchronized(fails(ip) = now :: recent(ip, now))
