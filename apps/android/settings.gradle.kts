import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 引擎 AAR 不走任何公共仓库：根工程的 verifySherpaAar 任务把“已校验哈希”的字节写到
// build/pinned-engine/，下面这个本地仓库只从该目录取。没有通过校验就没有文件，解析依赖会失败（fail closed）。
// exclusiveContent 保证这个坐标只能来自本地仓库，公共仓库上就算有同名包也不会被取到（防依赖混淆）。
val enginePin = Properties().apply {
    rootDir.resolve("engine/sherpa-onnx.properties").inputStream().use { load(it) }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                ivy {
                    name = "pinnedEngine"
                    url = rootDir.resolve("build/pinned-engine").toURI()
                    patternLayout { artifact("[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("pinned.k2fsa", enginePin.getProperty("name")) }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "AuriAndroid"
include(":asr")
include(":capture")
include(":modelmgmt")
include(":platform")
include(":sync")
include(":syncplatform")
include(":app")
