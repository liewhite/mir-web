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

/**
 * 按键（IP 或账号）限次数：窗口内记满 max 次就挡住，直到最早那次滑出窗口。
 * 登录记失败次数（10 分钟 10 次，与旧服务端一样）、注册记尝试次数（RateLimiter.register）、客户端报错和看不懂的指令记日志次数。
 */
final class RateLimiter(max: Int, windowMs: Long):
  private val hits = mutable.Map.empty[String, List[Long]]

  private def recent(ip: String, now: Long): List[Long] =
    val l = hits.getOrElse(ip, Nil).filter(now - _ < windowMs)
    if l.isEmpty then hits.remove(ip) else hits(ip) = l
    l

  def blocked(ip: String, now: Long): Boolean = synchronized(recent(ip, now).size >= max)

  def hit(ip: String, now: Long): Unit = synchronized(hits(ip) = now :: recent(ip, now))

  /** 没被挡住就记一次并返回 true；已满返回 false（不记）。 */
  def take(key: String, now: Long): Boolean = synchronized {
    val l = recent(key, now)
    if l.size >= max then false
    else
      hits(key) = now :: l
      true
  }

object RateLimiter:
  /** 登录：10 分钟内失败 10 次 */
  def login(): RateLimiter = RateLimiter(10, 600_000L)
  /** 注册：1 小时内试 10 次（成功失败都算），挡住脚本批量开号 */
  def register(): RateLimiter = RateLimiter(10, 3_600_000L)
  /** 客户端报错上报（按账号）：1 小时 30 条，防刷日志 */
  def clientErrorsPerAccount(): RateLimiter = RateLimiter(30, 3_600_000L)
  /** 客户端报错上报（按 IP）：1 小时 60 条 */
  def clientErrorsPerIp(): RateLimiter = RateLimiter(60, 3_600_000L)
  /** 看不懂的指令记日志（按账号）：1 分钟 5 条 */
  def badCommands(): RateLimiter = RateLimiter(5, 60_000L)
