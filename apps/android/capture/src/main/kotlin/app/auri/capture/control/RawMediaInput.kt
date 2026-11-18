package app.auri.capture.control

/**
 * 平台无关的媒体按键种类。平台适配器负责把系统的键值翻成这里的种类；:capture 里只认这些种类。
 * [STOP] 与 [HEADSET_HOOK] 在合同的 platform_event 里没有对应项（合同缺口，见验收 README 的 G7）：
 * 归一化时 STOP 并入 pause、HEADSET_HOOK 并入 toggle，原始键值留在 raw_event_id 里。
 */
enum class MediaKind { PLAY, PAUSE, PLAY_PAUSE, HEADSET_HOOK, STOP, NEXT, PREVIOUS, OTHER }

/** 按键的一个阶段。只有 [DOWN] 算一次手势；抬起与长按产生的自动重复不产生命令。 */
enum class KeyPhase { DOWN, UP, REPEAT }

/**
 * 平台上看到的、尚未归一化的一次媒体按键。
 *
 * [rawId]：平台给的、同一次按键被重复递送时不变的标识；拿不到就是 null——绝不拿 0 之类的占位值充当标识，
 * 否则每次按键都会被当成“同一个事件”而被吞掉。
 * [issuedAtNs]：平台说的按键发生时刻，已换算到与 [receivedAtNs] 同一个单调时钟；拿不到就是 null。
 * [fromExternalDevice]：平台能证明事件来自外接（蓝牙）设备时为 true / false，不知道就是 null。
 */
data class RawMediaInput(
    val kind: MediaKind,
    val phase: KeyPhase = KeyPhase.DOWN,
    val rawId: String? = null,
    val issuedAtNs: Long? = null,
    val fromExternalDevice: Boolean? = null,
    val receivedAtNs: Long,
    val deviceId: Int? = null,
) {
    init {
        require(receivedAtNs >= 0) { "received_at_ns 不能为负" }
    }
}

/**
 * 去重与时间戳的参数：
 *  - [maxIssuedAgeNs]：开始或切换命令在接收时允许的最大年龄；过期命令不再开始采集。
 *  - [rawIdMemory]：记住最近多少个原始事件标识用来去重（有上限，不会无限增长）。
 */
data class ControlConfig(
    val maxIssuedAgeNs: Long = 5_000_000_000L,
    val rawIdMemory: Int = 16,
) {
    init {
        require(maxIssuedAgeNs > 0 && rawIdMemory > 0) { "去重参数必须为正" }
    }
}
