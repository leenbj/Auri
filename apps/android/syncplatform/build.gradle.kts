// :syncplatform —— Ticket 21 的 Android 实现：Keystore 加密的设备令牌、SQLite 待同步任务表、NSD（mDNS）发现、
// 网络状态回调、二维码取景（CameraX + ZXing）。网络与相机权限在这个模块的清单里声明（INTERNET 另一处是 :modelmgmt，下载模型）：
// :asr、:capture、:platform 的清单与代码都没有网络，NoNetworkCodeGuardTest 与 NoNetworkManifestTest 把这一点钉死。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.auri.syncplatform"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
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
        unitTests.all { it.useJUnit() }
    }
}

dependencies {
    api(project(":sync"))
    api(project(":platform"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":sync")))

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(testFixtures(project(":sync")))
}
