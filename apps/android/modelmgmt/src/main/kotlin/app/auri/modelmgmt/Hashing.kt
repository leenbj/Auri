package app.auri.modelmgmt

import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

/**
 * 文件的 SHA-256。[checkpoint] 每读完一块调一次，让调用方在长文件上随时能中途停下来（暂停、取消）：它抛什么，这里就原样往外抛。
 */
@Throws(IOException::class)
internal fun sha256Hex(file: File, checkpoint: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20)
    file.inputStream().use { input ->
        while (true) {
            checkpoint()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
