package app.auri.capture.calibration

import app.auri.capture.contract.BusinessCommand.IGNORE
import app.auri.capture.contract.BusinessCommand.START
import app.auri.capture.contract.BusinessCommand.STOP
import app.auri.capture.contract.BusinessCommand.TOGGLE
import app.auri.capture.contract.CaptureState.ARMED
import app.auri.capture.contract.CaptureState.FINALIZING
import app.auri.capture.contract.CaptureState.RECORDING
import app.auri.capture.contract.CaptureState.ROUTING
import app.auri.capture.contract.ContractJson
import app.auri.capture.contract.ControlSource
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ControlConfig
import app.auri.capture.control.ControlProfile
import app.auri.capture.control.HeadsetEventNormalizer
import app.auri.capture.control.MediaKind
import app.auri.capture.control.NormalizeResult
import app.auri.capture.control.RawMediaInput
import app.auri.capture.machine.CaptureEvent
import app.auri.capture.testing.SequentialIds
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把控制这条线的产出写成合同形状的 JSON 文件，交给 Python 的 control_contract_check.py 用真实的 contracts/schemas/ 校验
 * （execution/acceptance/android-control/test_control_contract_output.py 会调用本测试再校验）。
 *
 * 写出的只有两类，都是 E2：
 *  - 归一化后的控制事件（真实的归一化器处理一串模拟的媒体按键），对照 adapter.schema.json 的 normalized_control_event；
 *  - 校准向导产出的 Headset Profile：一轮没有任何观察的；以及在单测 / 模拟器环境下跑完整一轮的（只能带 E2 证据，没有兼容结论）。
 * 没有写“真机 + 真实耳机”的档案：那个结论只能来自真机，单测不能替它产出文件。
 *
 * 输出目录是 Gradle 系统属性 auri.contract.out 的同级目录 control-<名字>，不与 Ticket 16 的采集输出混在一起。
 */
class ControlContractOutputTest {
    private val outRoot: File = File(checkNotNull(System.getProperty("auri.contract.out")) { "auri.contract.out 没有设置：请经 Gradle 运行" }).let {
        File(it.parentFile, "control-" + it.name)
    }

    private val pretty = Json { prettyPrint = true }

    private fun scenarioDir(name: String): File = File(outRoot, name).also {
        it.deleteRecursively()
        it.mkdirs()
    }

    private fun File.write(name: String, text: String) = resolve(name).writeText(text + "\n", Charsets.UTF_8)

    private fun reformat(text: String): String = pretty.encodeToString(JsonObject.serializer(), Json.parseToJsonElement(text) as JsonObject)

    private fun noObservationProfile(setting: EvidenceSetting): HeadsetProfile {
        val wizard = CalibrationWizard("cal-no-observations", setting).also { it.start() }
        repeat(4) { wizard.next() }
        return wizard.buildProfile(profileIdFor(null), 1, null)!!
    }

    /** 完整的一轮：每一步都“成功”。观察是造出来的，环境不是真机，所以档案里只能是 E2。 */
    private fun fullRunProfile(setting: EvidenceSetting): HeadsetProfile {
        val wizard = CalibrationWizard("cal-e2-full-run", setting).also { it.start() }
        val feed = Feed(wizard)
        feed.ignoredGesture(PlatformEvent.TOGGLE)
        wizard.next()
        feed.gesture(PlatformEvent.SCREEN_BUTTON, START, ROUTING, source = ControlSource.SCREEN_CONTROL)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING)
        feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED)
        wizard.next()
        feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING)
        wizard.next()
        wizard.onScreenLockChanged(true)
        feed.gesture(PlatformEvent.TOGGLE, TOGGLE, FINALIZING, locked = true)
        feed.other(CaptureEvent.FINALIZE_COMPLETE, ARMED, locked = true)
        feed.gesture(PlatformEvent.TOGGLE, TOGGLE, ROUTING, locked = true)
        feed.other(CaptureEvent.ROUTE_VERIFIED, RECORDING, locked = true)
        wizard.onScreenLockChanged(false)
        wizard.next()
        return wizard.buildProfile(profileIdFor("FreeClip 2"), 1, "FreeClip 2")!!
    }

    private fun write(name: String, profile: HeadsetProfile) {
        scenarioDir(name).write("headset-profile.json", reformat(profile.toJson()))
    }

    @Test
    fun `没有任何观察的一轮校准，在每种环境下都是五项未测试`() {
        EvidenceSetting.entries.forEach { setting ->
            val profile = noObservationProfile(setting)
            assertTrue(profile.capabilities.all.all { it == CapabilityRecord.untested })
            write("no-observations-${setting.label}", profile)
        }
    }

    @Test
    fun `单测与模拟器环境下完整的一轮：只有 E2 证据，没有兼容结论`() {
        listOf(EvidenceSetting.UNIT_TEST, EvidenceSetting.EMULATOR, EvidenceSetting.UNCONFIRMED).forEach { setting ->
            val profile = fullRunProfile(setting)
            assertTrue(profile.capabilities.all.all { it.isUntested })
            assertEquals(4, profile.capabilities.all.count { it.evidenceLevel == EvidenceLevel.E2 })
            write("e2-full-run-${setting.label}", profile)
        }
    }

    @Test
    fun `真实的归一化器把模拟的媒体按键变成合同里的控制事件，来源如实，序号递增`() {
        val normalizer = HeadsetEventNormalizer(SequentialIds(3), ControlConfig())
        val profile = ControlProfile.generic()
        // 名字 -> (输入, 期望的来源, 期望的命令)；系统确认来自外接设备才是 headset_remote，没说清就是 unknown_remote
        val inputs = listOf(
            Triple("headset-hook-external", RawMediaInput(MediaKind.HEADSET_HOOK, rawId = "key:79:a:0:t:1200", issuedAtNs = 1_200_000_000L, fromExternalDevice = true, receivedAtNs = 1_210_000_000L), ControlSource.HEADSET_REMOTE to TOGGLE),
            Triple("play-unknown-source", RawMediaInput(MediaKind.PLAY, rawId = null, issuedAtNs = null, fromExternalDevice = null, receivedAtNs = 5_000_000_000L), ControlSource.UNKNOWN_REMOTE to START),
            Triple("stop-key-is-pause", RawMediaInput(MediaKind.STOP, rawId = "key:86:a:0:t:9000", issuedAtNs = 9_000_000_000L, fromExternalDevice = true, receivedAtNs = 9_003_000_000L), ControlSource.HEADSET_REMOTE to STOP),
            Triple("next-is-ignored", RawMediaInput(MediaKind.NEXT, rawId = "key:87:a:0:t:20000", issuedAtNs = 20_000_000_000L, fromExternalDevice = true, receivedAtNs = 20_001_000_000L), ControlSource.HEADSET_REMOTE to IGNORE),
        )
        val dir = scenarioDir("events")
        inputs.forEachIndexed { index, (name, input, expected) ->
            val event = (normalizer.normalize(input, profile) as NormalizeResult.Emit).event
            assertEquals(name, expected, event.source to event.command)
            assertEquals("$name：序号从 0 起每个事件加一", index.toLong(), event.sequence)
            dir.write("normalized-control-event-$name.json", reformat(ContractJson.encode(event)))
        }
        assertEquals(inputs.size, dir.listFiles()!!.size)
    }
}
