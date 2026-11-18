// :platform —— Android 实现：控制适配器、采集适配器、录音存储、转录适配器、Record 库、前台服务。
// 依赖 :capture（接口与协调器）与 :asr（本地转录，Ticket 14）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.auri.platform"
    compileSdk = 35

    defaultConfig {
        // 与 :asr、探针一致；API 31 才有 setCommunicationDevice / getCommunicationDevice
        minSdk = 31
        // 与 :app 一致：引擎只带 arm64-v8a 的原生库
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // 模型适配器的测试直接读 apps/android/manifests/ 下真实的清单
            it.systemProperty("auri.manifests.dir", rootProject.projectDir.resolve("manifests").path)
        }
    }
}

dependencies {
    api(project(":capture"))
    implementation(project(":asr"))
    // 转录适配器与模型管理器的粘合（ActiveModel 在 :modelmgmt）；:platform 自己的清单仍然不声明 INTERNET
    implementation(project(":modelmgmt"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":capture")))

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(testFixtures(project(":capture")))
}

// The privacy and network-isolation guards scan compiled production classes in all five modules.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(
        ":app:compileDebugKotlin", ":app:compileDebugJavaWithJavac",
        ":modelmgmt:compileDebugKotlin", ":modelmgmt:compileDebugJavaWithJavac",
    )
}
