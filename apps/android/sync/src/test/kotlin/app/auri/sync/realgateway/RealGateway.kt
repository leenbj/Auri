package app.auri.sync.realgateway

import app.auri.sync.gateway.GatewayClient
import app.auri.sync.gateway.GatewayResult
import app.auri.sync.http.PinnedHttpsClient
import app.auri.sync.pairing.GatewayAddress
import app.auri.sync.tls.SpkiPin
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 命令行运行的结果。 */
class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
    fun orFail(what: String): CliResult {
        check(exitCode == 0) { "$what 失败（退出码 $exitCode）：${stderr.take(400)}" }
        return this
    }
}

/**
 * 真实的 Python 网关进程：services/gateway 里的 `python -m auri_gateway serve`，自签 TLS，临时数据目录，随机端口，
 * 不广播 mDNS，只听本机回环。不是任何 mock：入库、幂等、令牌、配对码、吊销都是网关自己的代码。
 *
 * 解释器是仓库的 `.venv/bin/python`（Gradle 任务 `:sync:realGatewayTest` 传进来，找不到就直接失败，不会悄悄跳过）。
 * 网关的源码用 PYTHONPATH 指到 services/gateway，和 tests/integration 的 harness 完全同一种启动方式。
 */
class RealGateway(val dataDir: File, val port: Int = freePort()) : AutoCloseable {
    private val python = File(requiredProperty("auri.python"))
    private val gatewaySource = File(requiredProperty("auri.gateway.src"))
    private val repoRoot = File(requiredProperty("auri.repo.root"))
    private val log = File(dataDir.parentFile, "gateway-$port-${System.nanoTime()}.log")
    private var process: Process? = null

    init {
        check(python.canExecute()) { "找不到仓库的 Python 环境 ${python.path}：先按 CLAUDE.md 建 .venv（真实网关测试不会用系统 python 凑数）" }
        check(File(gatewaySource, "auri_gateway/__main__.py").isFile) { "找不到网关源码 ${gatewaySource.path}" }
        dataDir.mkdirs()
    }

    /** 网关到现在为止打出的日志（stdout 与 stderr 合在一起）。 */
    fun logText(): String = if (log.isFile) log.readText() else ""

    /**
     * 网关证书里公钥的指纹，由测试自己从网关写在磁盘上的证书文件算出来（不是问网关要的）：
     * 配对载荷里的 public_key_sha256 必须和它一致，启动时也用它确认“真的是 TLS 在服务”。
     */
    val pin: SpkiPin get() = SpkiPin.of(certificate())

    fun start(): RealGateway {
        check(process == null) { "网关已经在跑" }
        val builder = ProcessBuilder(
            python.path, "-m", "auri_gateway", "serve",
            "--data-dir", dataDir.path, "--host", "127.0.0.1", "--port", port.toString(), "--no-mdns",
        )
        environment(builder)
        builder.directory(repoRoot)
        builder.redirectErrorStream(true)
        builder.redirectOutput(log)
        val started = builder.start()
        process = started
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            check(started.isAlive) { "网关启动时退出了（退出码 ${started.exitValue()}）：${logText().take(600)}" }
            if (serving()) return this
            Thread.sleep(100)
        }
        stop()
        error("网关 30 秒内没有开始提供 HTTPS 服务（127.0.0.1:$port）：${logText().take(600)}")
    }

    /** 停掉网关（SIGTERM，等 5 秒，不行再强杀）。数据目录原样留着，可以用同一个目录再 [start]。 */
    fun stop() {
        val running = process ?: return
        process = null
        running.destroy()
        if (!running.waitFor(5, TimeUnit.SECONDS)) {
            running.destroyForcibly()
            running.waitFor(5, TimeUnit.SECONDS)
        }
    }

    override fun close() = stop()

    /** 在网关上生成一个一次性配对码，原样返回命令行打印的 JSON 载荷（pairing.schema.json#/$defs/payload）。 */
    fun pairingPayloadJson(advertisedPort: Int = port, host: String = "127.0.0.1"): String =
        cli("pair", "--data-dir", dataDir.path, "--host", host, "--port", advertisedPort.toString(), "--ttl-seconds", "300", "--json")
            .orFail("pair --json").stdout.trim()

    /** 同上，但是命令行默认的样子：几行说明加一个终端二维码（这才是人真正会拿手机去扫的东西）。 */
    fun pairingTerminalOutput(advertisedPort: Int = port, host: String = "127.0.0.1"): String =
        cli("pair", "--data-dir", dataDir.path, "--host", host, "--port", advertisedPort.toString(), "--ttl-seconds", "300")
            .orFail("pair").stdout

    fun cli(vararg args: String, timeoutSeconds: Long = 60): CliResult {
        val out = File.createTempFile("auri-cli-out", ".txt", dataDir.parentFile)
        val err = File.createTempFile("auri-cli-err", ".txt", dataDir.parentFile)
        try {
            val builder = ProcessBuilder(listOf(python.path, "-m", "auri_gateway") + args)
            environment(builder)
            builder.directory(repoRoot)
            builder.redirectOutput(out)
            builder.redirectError(err)
            val running = builder.start()
            if (!running.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                error("网关命令 ${args.first()} 超过 $timeoutSeconds 秒还没结束")
            }
            return CliResult(running.exitValue(), out.readText(), err.readText())
        } finally {
            out.delete()
            err.delete()
        }
    }

    /** 对网关数据库只读地查一条 SQL（用 Python 自带的 sqlite3，只读打开）。每行每列是字符串或 null。 */
    fun query(sql: String, vararg params: String): List<List<String?>> {
        val program = """
import json, sqlite3, sys
db, sql, params = sys.argv[1], sys.argv[2], json.loads(sys.argv[3])
conn = sqlite3.connect("file:" + db + "?mode=ro", uri=True)
print(json.dumps([[None if v is None else str(v) for v in row] for row in conn.execute(sql, params).fetchall()], ensure_ascii=False))
"""
        val out = File.createTempFile("auri-sql-out", ".txt", dataDir.parentFile)
        try {
            val builder = ProcessBuilder(
                python.path, "-c", program, File(dataDir, "gateway.db").path, sql,
                JsonArray(params.map(::JsonPrimitive)).toString(),
            )
            environment(builder)
            builder.redirectOutput(out)
            builder.redirectErrorStream(true)
            val running = builder.start()
            check(running.waitFor(30, TimeUnit.SECONDS)) { "查询网关数据库超时" }
            val text = out.readText()
            check(running.exitValue() == 0) { "查询网关数据库失败：${text.take(400)}" }
            return Json.parseToJsonElement(text.trim()).jsonArray.map { row ->
                row.jsonArray.map { cell -> if (cell is JsonNull) null else cell.jsonPrimitive.contentOrNull }
            }
        } finally {
            out.delete()
        }
    }

    fun count(sql: String, vararg params: String): Int = query(sql, *params).single().single()!!.toInt()

    /** 网关登记的设备（list-devices --json）。 */
    fun devices(): List<JsonObject> =
        Json.parseToJsonElement(cli("list-devices", "--data-dir", dataDir.path, "--json").orFail("list-devices").stdout)
            .jsonObject.getValue("devices").jsonArray.map { it.jsonObject }

    /** 证书文件已经写好，并且经过固定了这把公钥的真实 TLS 通道，/health 答了 ok。 */
    private fun serving(): Boolean = try {
        val client = GatewayClient(PinnedHttpsClient(pin))
        runBlocking { client.health(GatewayAddress("127.0.0.1", port)) } is GatewayResult.Success
    } catch (_: Exception) {
        false // 证书文件还没写完整、端口还没开：再等
    }

    private fun certificate(): X509Certificate =
        File(dataDir, "tls/gateway.cert.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private fun environment(builder: ProcessBuilder) {
        val env = builder.environment()
        val existing = env["PYTHONPATH"]
        env["PYTHONPATH"] = if (existing.isNullOrEmpty()) gatewaySource.path else "${gatewaySource.path}:$existing"
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUNBUFFERED"] = "1"
    }

    companion object {
        fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        private fun requiredProperty(name: String): String =
            System.getProperty(name) ?: error("缺少系统属性 $name：真实网关测试要用 ./gradlew :sync:realGatewayTest 跑，不能直接当普通单测跑")
    }
}
