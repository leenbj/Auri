// :modelmgmt —— 本地转录模型的目录、下载（可暂停、可续传）、校验与原子启用（Ticket 20）。
// 这是整个应用里唯一允许出现网络代码的模块，所以 INTERNET 权限只在它的清单里声明：
// 它不依赖 :capture / :platform，看不到任何音频、文字或 Record 类型，也没有任何通道把它们交给网络
// （NetworkIsolationTest 扫四个生产模块的类文件，保证网络 API 只出现在这里；音频与文字的离线证据见 execution/acceptance/android-models/README.md）。
// 依赖 :asr 只为复用 ModelManifest 与 ModelVerifier：下载下来的文件必须由同一个校验器放行，而不是另写一套。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.auri.modelmgmt"
    compileSdk = 35

    defaultConfig {
        // 与 :asr 一致
        minSdk = 31
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
            // 测试直接读 apps/android/manifests/ 下真实的清单，不拷贝
            it.systemProperty("auri.manifests.dir", rootProject.projectDir.resolve("manifests").path)
            // 真实下载只在显式要求时才跑：./gradlew :modelmgmt:testDebugUnitTest -PauriRealDownload=<缓存目录>
            providers.gradleProperty("auriRealDownload").orNull?.let { dir -> it.systemProperty("auri.real.download.dir", dir) }
        }
    }
}

dependencies {
    // ModelManifest / ModelVerifier 出现在本模块的公开接口里
    api(project(":asr"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // 清单里的包是 .tar.bz2：JDK 与 Android 都没有 bzip2 解码，所以需要它。
    // commons-io 与 commons-lang3 是它的必需依赖，在这里写成精确版本（与它的 POM 一致），不让构建悄悄解析成别的版本。
    // 三个 jar 的 SHA-256 固定在 DependencyPinTest 里。
    implementation(libs.commons.compress)
    implementation(libs.commons.io)
    implementation(libs.commons.lang3)

    testImplementation(libs.junit)
}
