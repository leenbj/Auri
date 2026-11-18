package app.auri.sync.pairing

import app.auri.sync.gateway.Wire
import app.auri.sync.tls.SpkiPin
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 配对记录存成一个文件，**整个记录**（令牌、公钥指纹、标识、地址）一起用 [SecretBox] 封成密文：
 * 令牌因此从不以明文落盘，而且指纹也得到完整性保护（改不了指纹就骗不过固定公钥）。
 *
 * 保存 = 写临时文件、刷盘、原子改名：断电或被杀时留下的要么是旧记录要么是新记录。
 * 读不出来（被改动、被截断、密钥丢了、内容不合格）= [PairingState.Lost]，调用方要求用户重新配对；绝不带着半截数据继续用。
 */
class FilePairingStore(
    private val file: File,
    private val box: SecretBox,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PairingStore {
    private val mutex = Mutex()
    private val tmp = File(file.parentFile, file.name + ".tmp")

    override suspend fun load(): PairingState = mutex.withLock {
        withContext(io) {
            if (!file.exists()) return@withContext PairingState.NotPaired
            val plain = try {
                box.open(file.readBytes())
            } catch (_: IOException) {
                null
            }
            plain?.let(::decode)?.let { PairingState.Paired(it) } ?: PairingState.Lost
        }
    }

    override suspend fun save(gateway: PairedGateway) {
        mutex.withLock {
            withContext(io) {
                val sealed = box.seal(encode(gateway))
                file.parentFile.mkdirs()
                FileOutputStream(tmp).use {
                    it.write(sealed)
                    it.fd.sync()
                }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            withContext(io) {
                file.delete()
                tmp.delete()
            }
        }
    }

    @Serializable
    private class Stored(
        val version: Int,
        @SerialName("gateway_id") val gatewayId: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("pin_sha256") val pinSha256: String,
        val addresses: List<StoredAddress>,
        @SerialName("last_good_address") val lastGoodAddress: StoredAddress?,
        val token: String,
        @SerialName("paired_at") val pairedAt: String,
        @SerialName("needs_user") val needsUser: String?,
    )

    @Serializable
    private class StoredAddress(val host: String, val port: Int)

    private fun encode(gateway: PairedGateway): ByteArray = JSON.encodeToString(
        Stored.serializer(),
        Stored(
            version = VERSION,
            gatewayId = gateway.gatewayId,
            deviceId = gateway.deviceId,
            pinSha256 = gateway.pin.hex,
            addresses = gateway.addresses.map { StoredAddress(it.host, it.port) },
            lastGoodAddress = gateway.lastGoodAddress?.let { StoredAddress(it.host, it.port) },
            token = gateway.token.reveal(),
            pairedAt = gateway.pairedAt.toString(),
            needsUser = gateway.needsUser?.name,
        ),
    ).toByteArray(Charsets.UTF_8)

    /** 解码并逐项校验；任何一项不合格就整条作废（返回 null）。 */
    private fun decode(plain: ByteArray): PairedGateway? {
        val stored = try {
            JSON.decodeFromString(Stored.serializer(), plain.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (stored.version != VERSION) return null
        if (!Wire.UUID.matches(stored.gatewayId) || !Wire.UUID.matches(stored.deviceId)) return null
        val pin = SpkiPin.parse(stored.pinSha256) ?: return null
        val token = DeviceToken.parse(stored.token) ?: return null
        val addresses = stored.addresses.map { it.toAddress() ?: return null }
        if (addresses.isEmpty()) return null
        val lastGood = stored.lastGoodAddress?.let { it.toAddress() ?: return null }
        val needsUser = stored.needsUser?.let { name -> NeedsUser.entries.firstOrNull { it.name == name } ?: return null }
        if (!Wire.TIMESTAMP.matches(stored.pairedAt)) return null
        val pairedAt = try {
            OffsetDateTime.parse(stored.pairedAt).toInstant()
        } catch (_: DateTimeParseException) {
            return null
        }
        return PairedGateway(stored.gatewayId, stored.deviceId, pin, addresses, lastGood, token, pairedAt, needsUser)
    }

    private fun StoredAddress.toAddress(): GatewayAddress? =
        if (GatewayAddress.isValidHost(host) && GatewayAddress.isValidPort(port)) GatewayAddress(host, port) else null

    private companion object {
        const val VERSION = 1
        val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
            encodeDefaults = true
        }
    }
}
