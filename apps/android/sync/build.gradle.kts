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
    // 合同输出目录；Python 验收测试用 -PauriSyncOut=<目录> 指定，默认在 build/ 下
    systemProperty(
        "auri.sync.out",
        providers.gradleProperty("auriSyncOut")
            .orElse(layout.buildDirectory.dir("contract-out/jvm").map { it.asFile.path })
            .get(),
    )
    // 合同文件是测试的输入：改了 contracts/ 下的 schema 或样本，缓存的测试结果必须作废
    inputs.files(fileTree(repoRoot.resolve("contracts")) { include("schemas/**", "fixtures/**") })
        .withPropertyName("contracts")
}
