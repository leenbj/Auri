package app.auri.platform.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 严格蓝牙输入（FR-REC-001、ADR-002）：Android 实现里没有任何向系统请求手机麦克风的路径。
 * 行为上由 BluetoothCaptureAdapterTest 证明；这里还扫四个生产模块的编译类文件与允许类的源码和字节码。
 */
class NoPhoneMicPathTest {
    private val sources: MutableMap<String, String> = File("src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.name to code(it.readText()) }.toMutableMap()

    /** 去掉注释，免得文档里写到“MediaRecorder”“手机麦克风”被当成代码。 */
    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun filesContaining(token: String) = sources.filterValues { token in it }.keys.sorted()
    private fun restricted(bytes: ByteArray): Boolean {
        val constants = bytes.toString(Charsets.ISO_8859_1)
        return "android/media/AudioRecord" in constants || "android/media/MediaRecorder" in constants
    }

    @Test
    fun `compiled production modules restrict AudioRecord to the Bluetooth implementation`() {
        val modules = listOf("capture", "asr", "platform", "app")
        val allowed = setOf(
            "app/auri/platform/audio/AndroidAudioSystem.class",
            "app/auri/platform/audio/AndroidAudioSystem\$AndroidRecorder.class",
        )
        for (module in modules) {
            val build = File("../$module/build")
            val roots = if (module == "capture") listOf(File(build, "classes/kotlin/main"), File(build, "classes/java/main")) else listOf(
                File(build, "tmp/kotlin-classes/debug"),
                File(build, "intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
            )
            val classes = roots.filter { it.isDirectory }.flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "class" }.map { root to it }.toList()
            }
            assertTrue("compiled classes missing for $module", classes.isNotEmpty())
            val unexpected = classes.filter { (root, file) ->
                restricted(file.readBytes()) && (module != "platform" || file.relativeTo(root).invariantSeparatorsPath !in allowed)
            }.map { it.second.path }
            assertEquals("unexpected audio capture reference in $module", emptyList<String>(), unexpected)
        }
    }

    @Test
    fun `production sources cannot hide inlined audio source constants behind aliases`() {
        val forbidden = Regex("\\b(AudioRecord|MediaRecorder)\\b")
        val unexpected = listOf("capture", "asr", "platform", "app").flatMap { module ->
            File("../$module/src/main").walkTopDown().filter { file ->
                file.isFile && file.extension in setOf("kt", "java") &&
                    !(module == "platform" && file.invariantSeparatorsPath.endsWith("/app/auri/platform/audio/AndroidAudioSystem.kt")) &&
                    forbidden.containsMatchIn(code(file.readText()))
            }.map { it.path }.toList()
        }
        assertEquals(emptyList<String>(), unexpected)
        val allowed = sources.getValue("AndroidAudioSystem.kt")
        assertFalse("aliases could hide other audio sources", Regex("(?m)^\\s*import\\s+android\\.media\\.[^\\n]+\\s+as\\s+").containsMatchIn(allowed))
    }

    @Test
    fun `bytecode guard catches a compiled Kotlin import alias`() {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("app/auri/platform/audio/AliasRecorderExample.class")).use { it.readBytes() }
        assertTrue(restricted(bytes))
    }

    @Test
    fun `bytecode guard catches an aliased MediaRecorder with inlined audio source`() {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("app/auri/platform/audio/AliasMediaRecorderExample.class")).use { it.readBytes() }
        assertTrue(restricted(bytes))
    }

    @Test
    fun `the allowed recorder bytecode uses one voice communication builder and a preferred device`() {
        val file = File("build/tmp/kotlin-classes/debug/app/auri/platform/audio/AndroidAudioSystem.class")
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/javap").path, "-c", "-p", file.path)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("javap failed: $output", 0, process.waitFor())
        val lines = output.lines()
        assertEquals(1, lines.count { "// class android/media/AudioRecord\$Builder" in it && "new " in it })
        val sourceCalls = lines.indices.filter { "Method android/media/AudioRecord\$Builder.setAudioSource:" in lines[it] }
        assertEquals(1, sourceCalls.size)
        assertTrue("VOICE_COMMUNICATION is framework constant 7", Regex("bipush\\s+7\\s*$").containsMatchIn(lines[sourceCalls.single() - 1]))
        assertFalse("no direct AudioRecord constructor", output.contains("android/media/AudioRecord.\"<init>\""))
        assertEquals(1, lines.count { "Method android/media/AudioRecord.setPreferredDevice:" in it })
    }

    @Test
    fun `an exception while selecting the preferred device releases the recorder`() {
        val open = sources.getValue("AndroidAudioSystem.kt").substringAfter("override fun openRecorder").substringBefore("private fun inputInfo")
        val afterBuild = open.substringAfter(".build()")
        assertTrue("device selection must be guarded", afterBuild.indexOf("try {") in 0 until afterBuild.indexOf("setPreferredDevice"))
        assertTrue("the failure path must release the recorder", afterBuild.substringAfter("catch (").contains("record.release()"))
    }

    @Test
    fun `the guard rejects a recorder hidden behind a Kotlin import alias`() {
        sources["Aliased.kt"] = code("""
            import android.media.AudioRecord as HiddenRecorder
            import android.media.MediaRecorder.AudioSource as HiddenSource
            fun hidden() = HiddenRecorder.Builder().setAudioSource(HiddenSource.MIC).build()
        """.trimIndent())
        val caught = try {
            `全模块只有一处创建录音，且在 AndroidAudioSystem 里`()
            `录音源只用 VOICE_COMMUNICATION：没有 MIC、CAMCORDER、DEFAULT、识别、未处理等别的源`()
            `录音一建好就必须把蓝牙设备设为偏好设备，设不上就放弃，不会留下默认输入的录音`()
            `手机内置麦克风这个类型只出现在路由归类里，从不作为请求的目标`()
            `能打开录音的接口方法只接受蓝牙输入`()
            false
        } catch (expected: AssertionError) {
            true
        }
        assertTrue("the actual guard must reject an aliased AudioRecord", caught)
    }

    @Test
    fun `the guard rejects an aliased inlined audio source constant`() {
        sources["AliasedSource.kt"] = code("""
            import android.media.MediaRecorder.AudioSource as HiddenSource
            val source = HiddenSource.MIC
        """.trimIndent())
        val caught = try {
            `全模块只有一处创建录音，且在 AndroidAudioSystem 里`()
            `录音源只用 VOICE_COMMUNICATION：没有 MIC、CAMCORDER、DEFAULT、识别、未处理等别的源`()
            false
        } catch (expected: AssertionError) {
            true
        }
        assertTrue("inlined source constants must not hide behind aliases", caught)
    }

    @Test
    fun `全模块只有一处创建录音，且在 AndroidAudioSystem 里`() {
        assertTrue("应当扫到源码", sources.size >= 8)
        assertEquals(listOf("AndroidAudioSystem.kt"), filesContaining("android.media.AudioRecord"))
        assertEquals(listOf("AndroidAudioSystem.kt"), filesContaining("android.media.MediaRecorder"))
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
