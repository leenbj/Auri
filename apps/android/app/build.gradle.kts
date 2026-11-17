plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
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

dependencies {
    implementation(project(":asr"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
