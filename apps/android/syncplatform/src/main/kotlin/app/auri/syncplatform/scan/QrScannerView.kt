package app.auri.syncplatform.scan

import android.content.Context
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import app.auri.sync.pairing.LuminanceFrame
import app.auri.sync.pairing.QrFrameReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 的分析器：把每一帧适配成 [LuminanceFrame] 交给 [QrFrameReader]（读失败的帧不影响下一帧，每一帧恰好关闭一次）。
 */
internal class QrFrameAnalyzer(onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QrFrameReader(onText)

    override fun analyze(image: ImageProxy) {
        reader.read(ImageProxyFrame(image))
    }
}

private class ImageProxyFrame(private val image: ImageProxy) : LuminanceFrame {
    override val width: Int get() = image.width
    override val height: Int get() = image.height
    override val rowStride: Int get() = image.planes[0].rowStride // Y 平面：每个像素一个字节

    override fun luminance(): ByteArray {
        val buffer = image.planes[0].buffer
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    override fun close() = image.close()
}

/**
 * 取景并读二维码的视图（CameraX 预览 + 分析）。摄像头只在 [start] 与 [stop] 之间开着；
 * 绑定在 [LifecycleOwner] 上，界面进后台系统会自动关掉摄像头。摄像头不可用（没有摄像头、没权限、被占用）时调用 [onUnavailable]。
 *
 * 这一层只有真机的摄像头才能验证（模拟器没有对着屏幕的真实取景），见验收文档的真机步骤；
 * “画面 → 文字”那一段由 QrDecoder 的测试与网关真实终端二维码的测试覆盖。
 */
class QrScannerView(context: Context) : FrameLayout(context) {
    private val preview = PreviewView(context)
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null

    /** 当前这次 [start] 的凭证：[stop] 之后才回调的绑定（摄像头提供者是异步拿到的）不能再把摄像头打开。 */
    private var session: Any? = null

    init {
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun start(owner: LifecycleOwner, onText: (String) -> Unit, onUnavailable: () -> Unit) {
        stop()
        val mine = Any().also { session = it }
        val analysisExecutor = Executors.newSingleThreadExecutor().also { executor = it }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (session !== mine) return@addListener
            try {
                val cameraProvider = future.get()
                val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, QrFrameAnalyzer(onText)) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
                provider = cameraProvider
            } catch (_: Exception) {
                onUnavailable()
            }
        }, context.mainExecutor)
    }

    fun stop() {
        session = null
        provider?.unbindAll()
        provider = null
        executor?.shutdown()
        executor = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
