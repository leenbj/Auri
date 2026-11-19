package app.auri.sync.pairing

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 把明文封成只有本机密钥才能打开、且改动一个字节就会被发现的密文。 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** 打不开（被改过、被截断、密钥不对或已丢失）返回 null，不抛异常：调用方按“需要重新配对”处理。 */
    fun open(sealed: ByteArray): ByteArray?
}

/**
 * AES-256-GCM 带认证加密。密钥由 [key] 提供：在 Android 上是 Keystore 里不可导出的密钥（见 :syncplatform 的 KeystoreKeys），
 * 测试里是内存密钥；封装格式与全部逻辑只有这一份。
 *
 * 密文格式：`版本(1 字节) | IV(12 字节，每次由密码库随机生成) | 密文+认证标签`。
 * [purpose] 作为附加认证数据绑定用途：拿别的用途的密文冒充配对记录是打不开的。
 */
class AesGcmSecretBox(private val key: () -> SecretKey, purpose: String) : SecretBox {
    private val aad = "auri-secretbox|$purpose".toByteArray(Charsets.UTF_8)

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "密码库没有给出 $IV_BYTES 字节的随机 IV" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size < 1 + IV_BYTES + TAG_BYTES || sealed[0] != VERSION) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            null // 认证失败、密钥不对、Keystore 里的密钥已失效，都落在这里
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
