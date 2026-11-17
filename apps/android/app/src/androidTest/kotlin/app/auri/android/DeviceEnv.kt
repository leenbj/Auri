package app.auri.android

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.auri.asr.manifest.ModelManifest
import java.io.File
import org.junit.Assert.fail

/**
 * 仪器测试的共用环境。模型与示例音频不在仓库里，也不在 APK 里：由 install_on_device.sh 放进应用私有目录。
 * 缺了就让测试失败并说明怎么装，不跳过：跳过会把“没测”显示成“通过”。
 */
object DeviceEnv {
    const val MANIFEST_ASSET = "sensevoice-small-int8-2024-07-17.manifest.json"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** 被测应用的上下文：assets 里有清单，filesDir 是模型与输出所在的私有目录。 */
    val target: Context get() = instrumentation.targetContext

    /** 测试 APK 自己的上下文：assets 里有引擎固定信息（engine/sherpa-onnx.properties）。 */
    val testContext: Context get() = instrumentation.context

    val manifestJson: String by lazy {
        target.assets.open(MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    val manifest: ModelManifest by lazy { ModelManifest.parse(manifestJson) }

    val modelDir: File get() = File(target.filesDir, "models/${manifest.modelId}/${manifest.modelVersion}")
    val samplesDir: File get() = File(target.filesDir, "samples")

    /** 合同 JSON 与运行报告写在这里，由 run_emulator_acceptance.sh 拉回主机校验。 */
    val outRoot: File get() = File(target.filesDir, "contract-out")

    fun sample(name: String): File = File(samplesDir, "$name.wav")

    /** 真实模型与示例音频必须已经装好。 */
    fun requireInstalled(vararg samples: String) {
        val missing = buildList {
            if (!modelDir.isDirectory) add(modelDir.path)
            samples.forEach { if (!sample(it).isFile) add(sample(it).path) }
        }
        if (missing.isNotEmpty()) {
            fail("缺少 $missing；先运行 execution/acceptance/asr-android/prepare_model.py 与 install_on_device.sh")
        }
    }

    fun outDir(name: String): File = File(outRoot, name).also { it.deleteRecursively() }

    /** 以 shell 身份执行命令（UiAutomation），返回标准输出；用来切换飞行模式、读 dumpsys。 */
    fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
