import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val enginePin = rootProject.extra["enginePin"] as Properties

android {
    namespace = "app.auri.asr"
    compileSdk = 35

    defaultConfig {
        // 与 execution/p0-spikes/android-probe 一致；API 下限尚无 ADR，见 execution/acceptance/asr-android/README.md 的假设
        minSdk = 31
        // 目标手机与本机模拟器都是 arm64；只打包 arm64 可以把测试 APK 从约 100 MB 降到约 35 MB
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
            // 合同输出目录；Python 验收测试用 -PauriContractOut=<目录> 指定，默认在 build/ 下
            it.systemProperty(
                "auri.contract.out",
                providers.gradleProperty("auriContractOut")
                    .orElse(layout.buildDirectory.dir("contract-out/jvm").map { dir -> dir.asFile.path })
                    .get(),
            )
        }
    }
}

dependencies {
    // 坐标由 settings.gradle.kts 的本地仓库解析，字节来自 verifySherpaAar 校验过的副本
    implementation("pinned.k2fsa:${enginePin.getProperty("name")}:${enginePin.getProperty("version")}@aar")
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
