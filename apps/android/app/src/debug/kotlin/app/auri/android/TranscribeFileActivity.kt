package app.auri.android

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import app.auri.asr.engine.SherpaOnnxEngineFactory
import app.auri.asr.manifest.ModelManifest
import app.auri.asr.manifest.ModelRefusedException
import java.io.File

/**
 * Ticket 14 的“转录这个文件”入口（只在 debug 构建里）。真正的录音界面是 Ticket 16。
 *
 * adb shell am start -S -W -n app.auri.android/.TranscribeFileActivity --es wav samples/zh.wav [--es out NAME]
 *     [--es model_dir PATH] [--es manifest ASSET] [--ei warm_repeats N]
 *
 * 必须带 -S（先强行停止应用）：实测应用已在前台时 am start 只是把任务提到前台（START_TASK_TO_FRONT），
 * 新的 Intent 不会送达，什么也不会跑。-S 还让每次都从全新进程开始，“进程启动 → 第一次结果”才是真冷启动。
 *
 * 相对路径按应用私有目录 files/ 解析；默认模型目录是 files/models/<model_id>/<model_version>，
 * 输出在 files/runs/<out>/（合同 JSON + run-report.json）。界面与日志只写状态和数字，不写转录正文。
 */
class TranscribeFileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "Auri 转录验收：运行中"
            setPadding(32, 32, 32, 32)
        }
        setContentView(status)

        // 旋转等配置变化会重建 Activity：这次转录由第一个实例启动，重建出来的实例不能再起第二个并发运行
        if (savedInstanceState != null) {
            status.text = "Auri 转录验收：Activity 已重建，不再重新开始（结果看 logcat 与 runs/）"
            return
        }

        val wavPath = intent.getStringExtra(EXTRA_WAV)
        val manifestAsset = intent.getStringExtra(EXTRA_MANIFEST) ?: DEFAULT_MANIFEST_ASSET
        val modelDirOverride = intent.getStringExtra(EXTRA_MODEL_DIR)
        val outName = intent.getStringExtra(EXTRA_OUT) ?: "run-${System.currentTimeMillis()}"
        // 本入口是导出的，任何应用都能带参数启动：输出名只能是 runs/ 下的一个简单名字，不合法的名字也不回显到日志
        val outLabel = if (SAFE_OUT_NAME.matches(outName)) outName else "(invalid)"
        val warmRepeats = intent.getIntExtra(EXTRA_WARM_REPEATS, 0)

        Thread {
            val line = try {
                requireNotNull(wavPath) { "缺少 --es $EXTRA_WAV" }
                require(outLabel == outName) { "out 只能由字母、数字、点、下划线、连字符组成，且以字母或数字开头" }
                val manifestJson = assets.open(manifestAsset).use { it.readBytes().toString(Charsets.UTF_8) }
                val manifest = ModelManifest.parse(manifestJson)
                val modelDir = modelDirOverride?.let(::resolve) ?: File(filesDir, "models/${manifest.modelId}/${manifest.modelVersion}")
                val outDir = File(filesDir, "runs/$outName")
                val result = FileTranscription(SherpaOnnxEngineFactory(), SystemProcessProbe())
                    .run(FileTranscription.Request(manifestJson, modelDir, resolve(wavPath), outDir, warmRepeats))
                "state=${result.finalState} rtf=${result.metrics.rtf} out=runs/$outLabel"
            } catch (e: ModelRefusedException) {
                "error=${e.javaClass.simpleName} code=${e.refusal} out=runs/$outLabel"
            } catch (e: Throwable) {
                // 只记类型：异常消息里可能带路径或内容。连 UnsatisfiedLinkError 这类 Error 也记下来而不是让进程崩溃，
                // 验收的人才看得到是哪里坏了
                "error=${e.javaClass.simpleName} out=runs/$outLabel"
            }
            Log.i(TAG, line)
            runOnUiThread { status.text = line }
        }.start()
    }

    private fun resolve(path: String): File = File(path).let { if (it.isAbsolute) it else File(filesDir, path) }

    companion object {
        const val TAG = "AuriAsr"
        const val EXTRA_WAV = "wav"
        const val EXTRA_OUT = "out"
        const val EXTRA_MODEL_DIR = "model_dir"
        const val EXTRA_MANIFEST = "manifest"
        const val EXTRA_WARM_REPEATS = "warm_repeats"
        const val DEFAULT_MANIFEST_ASSET = "sensevoice-small-int8-2024-07-17.manifest.json"
        private val SAFE_OUT_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
