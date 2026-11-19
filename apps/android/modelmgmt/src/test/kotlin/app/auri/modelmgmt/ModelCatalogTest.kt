package app.auri.modelmgmt

import app.auri.asr.manifest.ModelRefusal
import app.auri.asr.manifest.ModelRefusedException
import app.auri.asr.manifest.Redistribution
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 20 第 1 项：模型列表显示体积、所需空间、语言、许可条目与固定哈希。 */
class ModelCatalogTest {
    private val mib = 1024L * 1024
    private val realManifest = TestManifests.sensevoice()

    @Test
    fun `模型列表条目显示体积、所需空间、语言、许可条目与固定哈希`() {
        val info = ModelCatalog.fromJson(listOf(realManifest)).entries.single().info

        assertEquals("sensevoice-small-int8", info.modelId)
        assertEquals("2024-07-17+7d1efa2138a6", info.modelVersion)
        assertEquals(listOf("zh", "en", "ja", "ko", "yue"), info.languages)
        // 体积：下载的压缩包，与解出来的文件合计，是两个不同的数
        assertEquals(163_002_883L, info.archiveSizeBytes)
        assertEquals(239_233_841L + 315_894L, info.unpackedSizeBytes)
        // 所需空间 = 下载 + 解包 + 余量（余量是总量的 10%，不少于 64 MiB；这里总量约 402 MB，10% 约 40 MB，取下限）
        assertEquals(163_002_883L + 239_549_735L + 64 * mib, info.spaceNeededBytes)
        // 许可是一个能打开的条目，而且如实带着“能否再分发尚未确认”
        assertEquals("FunASR Model Open Source License v1.1", info.licenseName)
        assertEquals(
            "https://github.com/modelscope/FunASR/blob/58830eca4012644aac0c3218c3ccc7d98f003fda/MODEL_LICENSE",
            info.licenseUrl,
        )
        assertEquals(Redistribution.UNCONFIRMED, info.redistribution)
        assertTrue(info.licenseNote.isNotBlank())
        // 固定哈希：压缩包一份，每个文件一份
        assertEquals("7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e", info.archiveSha256)
        assertEquals(
            listOf(
                ModelFileInfo("model.int8.onnx", 239_233_841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
                ModelFileInfo("tokens.txt", 315_894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            ),
            info.files,
        )
    }

    @Test
    fun `所需空间等于下载加解包加余量，余量取总量的 10% 与 64 MiB 中较大的`() {
        // 小模型：总量 30 MB，10% 只有 3 MB，取 64 MiB 的下限
        assertEquals(10_000_000L + 20_000_000L + 64 * mib, SpaceRule.needed(10_000_000L, 20_000_000L))
        // 大模型：总量 3 GB，10% 是 300 MB，超过下限
        val gb = 1_000_000_000L
        assertEquals(1 * gb + 2 * gb + 300_000_000L, SpaceRule.needed(1 * gb, 2 * gb))
        // 两者的分界：总量刚好使 10% 等于 64 MiB 时，两条规则给出同一个余量
        assertEquals(64 * mib, SpaceRule.margin(320 * mib, 320 * mib))
    }

    @Test
    fun `已经下载的字节不再需要空间，但余量仍按完整大小算，且不会算出负数`() {
        val full = SpaceRule.needed(100_000_000L, 200_000_000L)

        assertEquals(full - 40_000_000L, SpaceRule.needed(100_000_000L, 200_000_000L, alreadyDownloaded = 40_000_000L))
        // 下载已经完整：只剩解包与余量
        assertEquals(200_000_000L + SpaceRule.margin(100_000_000L, 200_000_000L), SpaceRule.needed(100_000_000L, 200_000_000L, alreadyDownloaded = 100_000_000L))
        // 残片比压缩包还大是异常状态：按“全部已下载”算，不给出比解包还小的需求
        assertEquals(
            SpaceRule.needed(100_000_000L, 200_000_000L, alreadyDownloaded = 100_000_000L),
            SpaceRule.needed(100_000_000L, 200_000_000L, alreadyDownloaded = 999_000_000L),
        )
        assertEquals(full, SpaceRule.needed(100_000_000L, 200_000_000L, alreadyDownloaded = -5L))
    }

    @Test
    fun `目录支持多份清单，默认模型默认是第一份，也可以指定`() {
        val other = TestManifests.renamed(realManifest, "other-model", "1.0.0")

        val first = ModelCatalog.fromJson(listOf(realManifest, other))
        assertEquals(listOf("sensevoice-small-int8", "other-model"), first.entries.map { it.modelId })
        assertEquals("sensevoice-small-int8", first.defaultModelId)

        val second = ModelCatalog.fromJson(listOf(realManifest, other), defaultModelId = "other-model")
        assertEquals("other-model", second.default.modelId)
        assertEquals(realManifest, first.entry("sensevoice-small-int8")!!.manifestJson)
    }

    @Test
    fun `坏目录整个被拒绝：空目录、重复的模型、不存在的默认模型、不合格的清单`() {
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.fromJson(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.fromJson(listOf(realManifest, realManifest)) }
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.fromJson(listOf(realManifest), defaultModelId = "nope") }
        // 一份坏清单不能被悄悄跳过：用户只会看到“少了一个模型”
        val broken = assertThrows(ModelRefusedException::class.java) {
            ModelCatalog.fromJson(listOf(realManifest, realManifest.replace("\"manifest_version\": 1", "\"manifest_version\": 9")))
        }
        assertEquals(ModelRefusal.MANIFEST_INVALID, broken.refusal)
    }
}

/** 测试用的清单：直接读 apps/android/manifests/ 下真实的那份。 */
internal object TestManifests {
    fun sensevoice(): String =
        File(checkNotNull(System.getProperty("auri.manifests.dir")) { "缺少系统属性 auri.manifests.dir" }, "sensevoice-small-int8-2024-07-17.manifest.json")
            .readText()

    /** 同一份清单换个名字：只用来造“目录里有第二个模型”。 */
    fun renamed(json: String, modelId: String, version: String): String =
        json.replace("\"model_id\": \"sensevoice-small-int8\"", "\"model_id\": \"$modelId\"")
            .replace("\"model_version\": \"2024-07-17+7d1efa2138a6\"", "\"model_version\": \"$version\"")
}
