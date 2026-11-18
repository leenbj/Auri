package app.auri.platform.control

import app.auri.capture.control.KeyPhase
import app.auri.capture.control.MediaKind
import app.auri.capture.control.RawMediaInput
import kotlin.math.abs

// android.view.KeyEvent 的取值。单独写一份常量，下面两个纯函数才能在 JVM 单测里不依赖 Android 框架。
private const val ACTION_DOWN = 0
private const val ACTION_UP = 1

private const val KEYCODE_HEADSETHOOK = 79
private const val KEYCODE_MEDIA_PLAY_PAUSE = 85
private const val KEYCODE_MEDIA_STOP = 86
private const val KEYCODE_MEDIA_NEXT = 87
private const val KEYCODE_MEDIA_PREVIOUS = 88
private const val KEYCODE_MEDIA_PLAY = 126
private const val KEYCODE_MEDIA_PAUSE = 127

/**
 * SystemClock.uptimeMillis() 与应用用的单调时钟（System.nanoTime）在 Android 上同属 CLOCK_MONOTONIC，读数只差先后顺序的几毫秒。
 * 差得比这个多，说明两个时钟不是一回事，事件时刻换算出来的手势时刻不能用（会把新命令误判成过时命令）。
 */
private const val CLOCK_AGREEMENT_NS = 50_000_000L

fun mediaKindOf(keyCode: Int): MediaKind = when (keyCode) {
    KEYCODE_MEDIA_PLAY -> MediaKind.PLAY
    KEYCODE_MEDIA_PAUSE -> MediaKind.PAUSE
    KEYCODE_MEDIA_PLAY_PAUSE -> MediaKind.PLAY_PAUSE
    KEYCODE_HEADSETHOOK -> MediaKind.HEADSET_HOOK
    KEYCODE_MEDIA_STOP -> MediaKind.STOP
    KEYCODE_MEDIA_NEXT -> MediaKind.NEXT
    KEYCODE_MEDIA_PREVIOUS -> MediaKind.PREVIOUS
    else -> MediaKind.OTHER
}

/**
 * 把一个 KeyEvent 的字段翻成与平台无关的原始输入。拿不到的信息不编：
 *  - 事件时刻为 0（合成的按键）：没有原始标识，也没有手势时刻——绝不拿 0 当标识，否则每次按键都会被当成“同一个事件”吞掉；
 *  - 两个时钟对不上（见 [CLOCK_AGREEMENT_NS]）：不信事件时刻，手势时刻留空；
 *  - [fromExternalDevice] 原样带过去（null = 系统没说）。
 * [uptimeNowMs] 是收到这个事件时 SystemClock.uptimeMillis() 的读数，[receivedAtNs] 是同一时刻应用单调时钟的读数。
 */
fun rawInputOf(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    eventTimeMs: Long,
    fromExternalDevice: Boolean?,
    uptimeNowMs: Long,
    receivedAtNs: Long,
): RawMediaInput {
    val phase = when (action) {
        ACTION_DOWN -> if (repeatCount > 0) KeyPhase.REPEAT else KeyPhase.DOWN
        ACTION_UP -> KeyPhase.UP
        else -> KeyPhase.REPEAT
    }
    val hasEventTime = eventTimeMs > 0
    val clocksAgree = abs(uptimeNowMs * 1_000_000L - receivedAtNs) <= CLOCK_AGREEMENT_NS
    return RawMediaInput(
        kind = mediaKindOf(keyCode),
        phase = phase,
        rawId = if (hasEventTime) "key:$keyCode:a:$action:t:$eventTimeMs" else null,
        issuedAtNs = if (hasEventTime && clocksAgree) eventTimeMs * 1_000_000L else null,
        fromExternalDevice = fromExternalDevice,
        receivedAtNs = receivedAtNs,
    )
}
