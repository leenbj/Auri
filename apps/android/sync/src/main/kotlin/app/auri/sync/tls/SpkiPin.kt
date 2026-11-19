package app.auri.sync.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * 网关的身份：TLS 证书里公钥（SubjectPublicKeyInfo 的 DER 编码）的 SHA-256（ADR-0018、pairing.schema.json 的 public_key_sha256）。
 * 绑定公钥而不是整张证书：网关用同一把密钥续签证书时，指纹不变，已配对的手机不必重新配对。
 * 比较用常量时间的 [MessageDigest.isEqual]。
 */
class SpkiPin private constructor(private val digest: ByteArray) {
    /** 64 位小写十六进制。 */
    val hex: String = digest.joinToString("") { "%02x".format(it) }

    fun matches(certificate: X509Certificate): Boolean = MessageDigest.isEqual(digest, spkiSha256(certificate))

    override fun equals(other: Any?) = other is SpkiPin && MessageDigest.isEqual(digest, other.digest)

    override fun hashCode() = hex.hashCode()

    // 指纹不是秘密，但日志里只需要认得出是哪一个
    override fun toString() = "SpkiPin(${hex.take(8)}…)"

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        /** 只接受合同规定的写法（64 位小写十六进制）；别的一律 null。 */
        fun parse(hex: String): SpkiPin? =
            if (HEX64.matches(hex)) SpkiPin(ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }) else null

        fun of(certificate: X509Certificate): SpkiPin = SpkiPin(spkiSha256(certificate))

        // X509Certificate.getPublicKey().getEncoded() 就是 SubjectPublicKeyInfo 的 DER（格式名 "X.509"）
        private fun spkiSha256(certificate: X509Certificate): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
    }
}
