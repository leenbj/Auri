package app.auri.syncplatform.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android Keystore 里的 AES-256-GCM 密钥（给 AesGcmSecretBox 用，封装设备令牌）。
 *
 * 密钥不可导出：应用进程拿不到密钥的字节，只能让系统用它加解密；所以复制走 App 私有目录里的配对文件也打不开它。
 * IV 必须由密码库随机生成（setRandomizedEncryptionRequired），调用方不能指定，不会重复使用同一个 IV。
 * 没有要求“设备解锁才能用”：同步在锁屏时也要读令牌；令牌本身只对局域网里的这一台网关有用，并且可以在网关上撤销。
 */
object KeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"

    /** 取别名为 [alias] 的密钥；没有就生成。 */
    @Synchronized
    fun aesKey(alias: String): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
