package app.auri.sync.testing

import app.auri.sync.tls.SpkiPin
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * 测试用的“网关身份”：由 JDK 自带的 keytool 现场生成的 EC P-256 自签证书（与网关同样的算法），
 * 不入库任何密钥或证书文件（.gitignore 也不允许 *.p12 / *.jks / *.pem）。每个 JVM 里按名字缓存，生成一次约一秒。
 */
object TlsFixtures {
    const val PASSWORD = "changeit"

    class Identity(val alias: String, val keyStore: KeyStore, val certificate: X509Certificate, val file: File) {
        val pin: SpkiPin = SpkiPin.of(certificate)
    }

    private val cache = HashMap<String, Identity>()
    private val workDir: File by lazy {
        Files.createTempDirectory("auri-tls-fixtures").toFile().also { it.deleteOnExit() }
    }

    /** 两个互不相干的网关：A 是“已配对的那个”，B 是“冒充者/换了密钥的网关”。 */
    val gatewayA: Identity get() = identity("gateway-a", sanIp = "127.0.0.1")
    val gatewayB: Identity get() = identity("gateway-b", sanIp = "127.0.0.1")

    /** 证书里没有任何 SAN：身份只能靠公钥指纹，主机名/IP 核对是做不到的。 */
    val gatewayWithoutSan: Identity get() = identity("gateway-no-san", sanIp = null)

    /** 证书早就过期了（2020 年起只有一天有效期），但公钥是有效的：续签前的旧证书。 */
    val gatewayExpired: Identity get() = identity("gateway-expired", sanIp = "127.0.0.1", startDate = "2020/01/01 00:00:00", validityDays = 1)

    @Synchronized
    fun identity(alias: String, sanIp: String? = "127.0.0.1", startDate: String? = null, validityDays: Int = 3650): Identity =
        cache.getOrPut("$alias|$sanIp|$startDate|$validityDays") {
            val file = File(workDir, "$alias-${cache.size}.p12")
            val command = mutableListOf(
                keytool(), "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", "CN=$alias", "-validity", validityDays.toString(), "-keystore", file.path, "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD,
            )
            if (sanIp != null) command += listOf("-ext", "SAN=ip:$sanIp")
            if (startDate != null) command += listOf("-startdate", startDate)
            run(command)
            load(alias, file)
        }

    /** 用同一把私钥重新签发证书（网关的 renew-cert）：证书变了，公钥指纹不变。 */
    @Synchronized
    fun renewed(original: Identity, validityDays: Int = 3650): Identity {
        val file = File(workDir, "${original.alias}-renewed-${cache.size}.p12")
        original.file.copyTo(file)
        run(
            listOf(
                keytool(), "-selfcert", "-alias", original.alias, "-sigalg", "SHA256withECDSA", "-validity", validityDays.toString(),
                "-keystore", file.path, "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD,
            ),
        )
        return load(original.alias, file).also { cache["${original.alias}|renewed|${cache.size}"] = it }
    }

    private fun load(alias: String, file: File): Identity {
        val keyStore = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
        return Identity(alias, keyStore, keyStore.getCertificate(alias) as X509Certificate, file)
    }

    private fun keytool(): String = File(System.getProperty("java.home"), "bin/keytool").path

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool 失败：$output" }
    }
}
