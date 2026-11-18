// :capture —— 纯 Kotlin / JVM，不引用任何 Android 类。
// 放在这里的东西：适配器合同类型（镜像 contracts/schemas/adapter.schema.json）、适配器接口、
// 采集状态机、采集协调器（Capture Coordinator）、Record 库的接口与转录流水线。
// 三端（鸿蒙 / iOS / Android）各自照这里的边界实现；平台差异只在适配器里。
// 它不依赖 :asr：转录经 TranscriptionAdapter 接口进来，:platform 负责把 :asr 的 LocalTranscriber 接上。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

// 仓库根目录（apps/android 的上两级）：测试直接读 contracts/ 下的原始文件，不用拷贝。
val repoRoot: File = rootProject.projectDir.resolve("../..").canonicalFile

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    // 假适配器与共用的 Record 库合同测试放在 testFixtures，:platform 与 :app 的测试复用同一份
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesApi(libs.junit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    systemProperty("auri.repo.root", repoRoot.path)
    // 合同输出目录；Python 验收测试用 -PauriContractOut=<目录> 指定，默认在 build/ 下
    systemProperty(
        "auri.contract.out",
        providers.gradleProperty("auriContractOut")
            .orElse(layout.buildDirectory.dir("contract-out/jvm").map { it.asFile.path })
            .get(),
    )
    // 合同文件是测试的输入：改了 contracts/ 下的 schema 或样本，缓存的测试结果必须作废
    inputs.files(fileTree(repoRoot.resolve("contracts")) { include("schemas/**", "fixtures/**") })
        .withPropertyName("contracts")
}
