package app.auri.capture.calibration

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.testing.RepoContracts
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Headset Profile 的 Kotlin 类型必须“逐字段、逐取值”镜像 contracts/schemas/headset-profile.schema.json。
 * 证据：合同自带的样本（从仓库里直接读，不是拷贝）——有效样本能解码、再编码后与原样本完全一致；无效样本必须被拒绝。
 * 这是 E2：它证明类型与合同一致，不证明任何耳机的行为。
 */
class HeadsetProfileContractTest {
    private fun fixtures(kind: String): List<Pair<File, JsonElement>> =
        RepoContracts.contractsDir.resolve("fixtures/messages/$kind")
            .listFiles { f -> f.name.startsWith("headset-profile.") && f.name.endsWith(".json") }!!
            .sorted()
            .map { it to Json.parseToJsonElement(it.readText(Charsets.UTF_8)).jsonObject.getValue("instance") }

    @Test
    fun `有效样本能解码，再编码与原样本完全一致`() {
        val all = fixtures("valid")
        assertEquals("合同里有两个有效的 Headset Profile 样本", 2, all.size)
        all.forEach { (file, instance) ->
            val decoded = HeadsetProfile.fromJson(instance.toString())
            assertEquals(file.name, instance, Json.parseToJsonElement(decoded.toJson()))
        }
    }

    @Test
    fun `无效样本在解码时被拒绝：没有证据的支持、模拟证据的支持、原始双击映射、音量键开始、缺少能力、蓝牙优先`() {
        val all = fixtures("schema-invalid")
        assertEquals("合同里有六个无效的 Headset Profile 样本", 6, all.size)
        all.forEach { (file, instance) ->
            try {
                HeadsetProfile.fromJson(instance.toString())
            } catch (expected: IllegalArgumentException) {
                return@forEach // SerializationException 与构造校验都是 IllegalArgumentException
            }
            fail("${file.name} 本应被拒绝，却解码成功了")
        }
    }

    @Test
    fun `兼容结论必须有 E3 以上的证据和证据标识，E2 只能挂在没测的能力上`() {
        listOf(null, EvidenceLevel.E0, EvidenceLevel.E1, EvidenceLevel.E2).forEach { level ->
            try {
                CapabilityRecord(CompatStatus.SUPPORTED_FOREGROUND, level, listOf("run/1"))
                fail("$level 不够：兼容结论要真机证据")
            } catch (expected: IllegalArgumentException) {
            }
        }
        try {
            CapabilityRecord(CompatStatus.UNSUPPORTED, EvidenceLevel.E3, emptyList())
            fail("没有证据标识的结论")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(CompatStatus.UNSUPPORTED, CapabilityRecord(CompatStatus.UNSUPPORTED, EvidenceLevel.E3, listOf("run/1")).status)
        // 没测的能力可以带 E2 证据（说明在模拟器上跑过），也可以什么都没有
        assertTrue(CapabilityRecord(CompatStatus.NOT_TESTED, EvidenceLevel.E2, listOf("run/emulator")).isUntested)
        assertTrue(CapabilityRecord.untested.isUntested)
        assertFalse(CapabilityRecord(CompatStatus.PARTIAL, EvidenceLevel.E4, listOf("run/1")).isUntested)
    }

    @Test
    fun `档案转成运行时映射：标识、版本、映射原样带过去，音量键不能开始录音`() {
        val profile = HeadsetProfile(
            profileId = "android-test", profileVersion = 3, manufacturerHint = null, modelHint = "FreeClip 2", firmwareConstraint = null,
            platform = ProfilePlatform.ANDROID, rawGestureVisible = false,
            commandMapping = mapOf(PlatformEvent.PLAY to BusinessCommand.TOGGLE, PlatformEvent.NEXT to BusinessCommand.IGNORE),
            capabilities = HeadsetCapabilities.allUntested,
        )
        val control = profile.controlProfile()
        assertEquals("android-test", control.profileId)
        assertEquals(3, control.profileVersion)
        assertEquals(BusinessCommand.TOGGLE, control.commandFor(PlatformEvent.PLAY))
        assertEquals("映射里没有的事件一律忽略", BusinessCommand.IGNORE, control.commandFor(PlatformEvent.PAUSE))
        try {
            profile.copy(commandMapping = mapOf(PlatformEvent.VOLUME_CHANGE to BusinessCommand.START))
            fail("音量键只能停止或忽略（ADR-0014）")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            profile.copy(commandMapping = emptyMap())
            fail("映射不能为空")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            profile.copy(inputPolicy = "bluetooth_preferred")
            fail("不得回退手机麦克风")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
