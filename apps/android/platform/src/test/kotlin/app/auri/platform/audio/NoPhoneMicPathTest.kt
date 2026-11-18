package app.auri.platform.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 严格蓝牙输入（FR-REC-001、ADR-002）：Android 实现里没有任何向系统请求手机麦克风的路径。
 * 行为上由 BluetoothCaptureAdapterTest 证明（没有蓝牙输入就什么都不打开）；这里扫源码，防止以后有人悄悄加一条“回退”。
 * 扫的是 :platform 的全部主源码，而不只是某一个文件。
 */
class NoPhoneMicPathTest {
    private val sources: Map<String, String> = File("src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.name to code(it.readText()) }

    /** 去掉注释，免得文档里写到“MediaRecorder”“手机麦克风”被当成代码。 */
    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun filesContaining(token: String) = sources.filterValues { token in it }.keys.sorted()

    @Test
    fun `全模块只有一处创建录音，且在 AndroidAudioSystem 里`() {
        assertTrue("应当扫到源码", sources.size >= 8)
        assertEquals(listOf("AndroidAudioSystem.kt"), filesContaining("AudioRecord.Builder"))
        assertEquals(emptyList<String>(), filesContaining("AudioRecord("))
        assertEquals(emptyList<String>(), filesContaining("MediaRecorder("))
    }

    @Test
    fun `录音源只用 VOICE_COMMUNICATION：没有 MIC、CAMCORDER、DEFAULT、识别、未处理等别的源`() {
        val sourceTokens = sources.values.flatMap { Regex("AudioSource\\.\\w+").findAll(it).map { m -> m.value }.toList() }.toSet()

        assertEquals(setOf("AudioSource.VOICE_COMMUNICATION"), sourceTokens)
    }

    @Test
    fun `录音一建好就必须把蓝牙设备设为偏好设备，设不上就放弃，不会留下默认输入的录音`() {
        val open = sources.getValue("AndroidAudioSystem.kt").substringAfter("override fun openRecorder").substringBefore("private fun inputInfo")

        assertTrue(open.contains("setPreferredDevice(info)"))
        assertTrue("设不上就释放并报错", open.contains("record.release()") && open.contains("error("))
        assertTrue("偏好设备在返回录音之前设置", open.indexOf("setPreferredDevice") < open.indexOf("return AndroidRecorder"))
        assertEquals(listOf("AndroidAudioSystem.kt"), filesContaining("setPreferredDevice"))
        assertEquals(listOf("AndroidAudioSystem.kt"), filesContaining("setCommunicationDevice"))
    }

    @Test
    fun `手机内置麦克风这个类型只出现在路由归类里，从不作为请求的目标`() {
        // BUILTIN_MIC 只在 AudioSystem.kt：常量定义与 routeTypeOf（用来如实报告“系统说它在用手机麦克风”）
        assertEquals(listOf("AudioSystem.kt"), filesContaining("BUILTIN_MIC"))
        assertEquals(emptyList<String>(), filesContaining("TYPE_BUILTIN_MIC"))
        assertEquals(emptyList<String>(), filesContaining("setPreferredDevice(null"))
        assertEquals(emptyList<String>(), filesContaining("clearPreferredDevice"))
    }

    @Test
    fun `能打开录音的接口方法只接受蓝牙输入`() {
        val open = AudioSystem::class.java.methods.single { it.name == "openRecorder" }

        assertEquals(listOf(BluetoothInput::class.java, app.auri.capture.adapter.PcmFormat::class.java), open.parameterTypes.toList())
        assertEquals(
            setOf("bluetoothInputs", "requestCommunicationRoute", "clearCommunicationRoute", "requestAudioFocus", "openRecorder"),
            AudioSystem::class.java.methods.map { it.name }.toSet(),
        )
    }
}
