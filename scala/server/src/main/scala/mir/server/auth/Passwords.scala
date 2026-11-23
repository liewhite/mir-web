package mir.server.auth

import org.bouncycastle.crypto.generators.SCrypt

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.HexFormat

/**
 * 密码哈希：scrypt（N=16384, r=8, p=1, 32 字节，与旧 Node 服务端 `scryptSync` 默认参数一致），
 * 存成 `scrypt$<盐 hex>$<哈希 hex>`。
 */
object Passwords:
  private val N = 16384
  private val R = 8
  private val P = 1
  private val Len = 32
  private val hex = HexFormat.of()
  private val random = SecureRandom()

  def hash(password: String): String =
    val salt = new Array[Byte](16)
    random.nextBytes(salt)
    s"scrypt$$${hex.formatHex(salt)}$$${hex.formatHex(derive(password, salt, Len))}"

  /** 存的格式不对也当作不匹配（不抛错）。 */
  def verify(password: String, stored: String): Boolean = stored.split('$') match
    case Array("scrypt", saltHex, hashHex) =>
      val want = hex.parseHex(hashHex)
      MessageDigest.isEqual(want, derive(password, hex.parseHex(saltHex), want.length))
    case _ => false

  private def derive(password: String, salt: Array[Byte], len: Int): Array[Byte] =
    SCrypt.generate(password.getBytes(UTF_8), salt, N, R, P, len)
