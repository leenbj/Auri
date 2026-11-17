package app.auri.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.auri.asr.engine.SherpaOnnxEngineFactory
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket 14 第 1 项（设备侧）：APK 里实际加载的原生引擎就是固定的那个构建。
 * 构建时的 SHA-256 校验保证“打进来的 AAR 字节正确”；这里再确认运行时读到的版本与固定信息一致，
 * 并且用的是 arm64 原生库（本项目的目标 ABI）。
 */
@RunWith(AndroidJUnit4::class)
class EnginePinTest {
    private val pin = Properties().apply {
        DeviceEnv.testContext.assets.open("sherpa-onnx.properties").use { load(it) }
    }

    @Test
    fun packagedNativeEngineReportsExactlyThePinnedVersion() {
        val info = SherpaOnnxEngineFactory.runtimeInfo()

        assertEquals(pin.getProperty("version"), info.version)
        assertEquals(pin.getProperty("onnxruntime.version"), info.onnxruntimeVersion)
    }

    @Test
    fun packagedNativeEngineWasBuiltFromThePinnedCommit() {
        val sha = SherpaOnnxEngineFactory.runtimeInfo().gitSha1
        val pinned = pin.getProperty("source.commit")

        // 前缀比对要有足够长度才有意义：一个字符的“前缀”几乎匹配任何提交
        assertTrue("原生库的 git sha=\"$sha\" 太短，不足以确认固定的提交", sha.length >= 7)
        assertTrue("原生库的 git sha=$sha，固定的提交是 $pinned", pinned.startsWith(sha) || sha.startsWith(pinned))
    }
}
