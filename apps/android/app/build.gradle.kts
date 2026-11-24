plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.auri.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.auri.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // 模型清单随应用打包（只有清单，没有权重）；引擎固定信息给仪器测试对照原生库的实际版本
        getByName("main").assets.srcDir("../manifests")
        getByName("androidTest").assets.srcDir("../engine")
    }

    testOptions {
        unitTests {
            // 生成 test_config.properties，里面有合并后的清单路径：用来断言应用没有 INTERNET 权限
            isIncludeAndroidResources = true
            all { it.useJUnit() }
        }
    }
}

// NoNetworkManifestTest 读清单合并报告。不把它声明成输入的话，报告变了而合并后的清单本身没变时（比如某个库多声明了一个同样的权限），
// Gradle 会把测试判成“最新”而不跑，变异验证实测过。
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn("processDebugMainManifest")
    inputs.file(layout.buildDirectory.file("outputs/logs/manifest-merger-debug-report.txt")).withPropertyName("manifestMergerReport")

    // ReleaseVariantTest（评审 F14）要看 release 版编译出来的类：测试前先把它们编译好，并声明为输入，类变了测试就要重跑。
    dependsOn("compileReleaseKotlin", "compileReleaseJavaWithJavac")
    inputs.files(layout.buildDirectory.dir("tmp/kotlin-classes/release")).withPropertyName("releaseKotlinClasses")
    inputs.files(layout.buildDirectory.dir("intermediates/javac/release/compileReleaseJavaWithJavac/classes")).withPropertyName("releaseJavaClasses")
}

dependencies {
    implementation(project(":asr"))
    implementation(project(":capture"))
    implementation(project(":modelmgmt"))
    implementation(project(":platform"))
    implementation(project(":syncplatform"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":capture")))
    testImplementation(testFixtures(project(":sync"))) // Ticket 22：目的地视图模型的测试用假网关、内存发送账

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(testFixtures(project(":capture")))
}
