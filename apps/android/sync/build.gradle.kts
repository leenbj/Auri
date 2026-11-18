// :sync —— 纯 Kotlin / JVM，不引用任何 Android 类（Ticket 21：配对与同步）。
// 放在这里的东西：配对载荷校验、固定公钥的 TLS 与 HTTP 客户端、网关接口（GatewayApi）、网关定位（已记录地址 → mDNS）、
// 同步状态机、同步引擎（待同步任务、退避、结果不确定时先查询再决定重发）、submission 组装、同步状态的汇总。
// Android 的实现（Keystore、SQLite 任务表、NSD、网络回调、相机取景）在 :syncplatform。
// 这是 App 里唯一可以碰网络的模块（:capture、:asr、:platform 都没有网络代码，NoNetworkCodeGuardTest 逐个类核对）。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

// 仓库根目录（apps/android 的上两级）：测试直接读 contracts/ 下的原始文件，不用拷贝。
val repoRoot: File = rootProject.projectDir.resolve("../..").canonicalFile

dependencies {
    api(project(":capture"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // 二维码解码（Apache-2.0，精确版本，见 libs.versions.toml）
    implementation(libs.zxing.core)

    testFixturesApi(testFixtures(project(":capture")))
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesApi(libs.junit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    // 真实网关测试（要起 Python 网关进程）单独跑：./gradlew :sync:realGatewayTest
    exclude("**/realgateway/**")
    systemProperty("auri.repo.root", repoRoot.path)
    // 合同文件是测试的输入：改了 contracts/ 下的 schema 或样本，缓存的测试结果必须作废
    inputs.files(fileTree(repoRoot.resolve("contracts")) { include("schemas/**", "fixtures/**") })
        .withPropertyName("contracts")
}

// 真实网关测试：起 services/gateway 里的 Python 网关进程（自签 TLS、临时数据目录、随机端口），用 :sync 的真实代码与它对话。
// 要仓库的 .venv（CLAUDE.md 里的 pip install），所以不进 :sync:test；找不到解释器就直接失败，不会悄悄跳过。每次都跑，不用缓存。
// -Pauri.python=…：变异检查在没有 .venv 的快照里跑，解释器由真实工作树传进来；平时用仓库自己的 .venv
val gatewayPython: File = (findProperty("auri.python") as String?)?.let(::File) ?: repoRoot.resolve(".venv/bin/python")
tasks.register<Test>("realGatewayTest") {
    group = "verification"
    description = "对着真实的 Python 网关进程跑配对、入库、幂等、结果不确定、网关换机/吊销（E2，不是真机）"
    val unitTests = sourceSets.getByName("test")
    testClassesDirs = unitTests.output.classesDirs
    classpath = unitTests.runtimeClasspath
    useJUnit()
    include("**/realgateway/**")
    systemProperty("auri.repo.root", repoRoot.path)
    systemProperty("auri.python", gatewayPython.path)
    systemProperty("auri.gateway.src", repoRoot.resolve("services/gateway").path)
    // 契约样本与网关源码都是输入：改了它们必须重跑
    inputs.files(fileTree(repoRoot.resolve("contracts")) { include("schemas/**", "fixtures/**") }).withPropertyName("contracts")
    inputs.files(fileTree(repoRoot.resolve("services/gateway")) { include("auri_gateway/**/*.py", "auri_gateway/**/*.sql") }).withPropertyName("gatewaySource")
    outputs.upToDateWhen { false }
    doFirst { check(gatewayPython.canExecute()) { "找不到 ${gatewayPython.path}：先建仓库的 Python 环境（见 CLAUDE.md“命令”一节）" } }
    testLogging { events("passed", "failed", "skipped") }
}
