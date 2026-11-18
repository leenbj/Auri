package app.auri.platform.control

import app.auri.capture.control.KeyPhase
import app.auri.capture.control.MediaKind
import app.auri.capture.control.RawMediaInput
import app.auri.capture.coordinator.Clock

/**
 * 假的媒体按键来源：只记录被激活 / 释放了几次，并让测试从“系统”一侧递送按键。
 * 激活之后的监听器全部留着（[staleListeners]），用来造“释放之后才送到的旧回调”。
 */
class FakeMediaButtonSource(private val clock: Clock) : MediaButtonSource {
    var activateCalls = 0
        private set
    var deactivateCalls = 0
        private set

    /** 激活的结果；false 模拟系统不给会话。 */
    var activateResult = true

    /** 非空时 activate / deactivate 抛它：模拟框架自己的 bug。 */
    var activateThrows: RuntimeException? = null
    var deactivateThrows: RuntimeException? = null

    private var active: ((RawMediaInput) -> Unit)? = null
    private val everRegistered = mutableListOf<(RawMediaInput) -> Unit>()

    val isActive: Boolean get() = active != null

    val staleListeners: List<(RawMediaInput) -> Unit> get() = everRegistered.toList()

    override fun activate(listener: (RawMediaInput) -> Unit): Boolean {
        activateCalls += 1
        activateThrows?.let { throw it }
        if (!activateResult) return false
        active = listener
        everRegistered += listener
        return true
    }

    override fun deactivate() {
        deactivateCalls += 1
        active = null
        deactivateThrows?.let { throw it }
    }

    /** 从系统一侧递送一个按键。没有激活的来源就没有人收听：返回 false，什么都不会发生。 */
    fun press(
        kind: MediaKind,
        phase: KeyPhase = KeyPhase.DOWN,
        rawId: String? = null,
        issuedAtNs: Long? = clock.monotonicNs(),
        external: Boolean? = true,
        receivedAtNs: Long = clock.monotonicNs(),
    ): Boolean {
        val listener = active ?: return false
        listener(RawMediaInput(kind, phase, rawId, issuedAtNs, external, receivedAtNs, fromBluetoothHeadset = external == true))
        return true
    }

    /** 第 [index] 次激活留下的监听器收到一个按键：释放之后才送到的旧回调。 */
    fun pressViaStaleListener(index: Int, kind: MediaKind, receivedAtNs: Long = clock.monotonicNs()) {
        everRegistered[index](RawMediaInput(kind, issuedAtNs = receivedAtNs, receivedAtNs = receivedAtNs, fromExternalDevice = true))
    }

    fun pressPlatformKey(keyCode: Int, eventTimeMs: Long): Boolean {
        val listener = active ?: return false
        val receivedAtNs = clock.monotonicNs()
        listener(rawInputOf(keyCode, 0, 0, eventTimeMs, true, receivedAtNs / 1_000_000, receivedAtNs))
        return true
    }
}
