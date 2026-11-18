package app.auri.platform.control

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock as AndroidSystemClock
import android.view.KeyEvent
import app.auri.capture.control.RawMediaInput
import app.auri.capture.coordinator.Clock

/** 媒体会话的标签：设备测试靠它在系统的会话列表（`dumpsys media_session`）里认出我们的会话。 */
const val MEDIA_SESSION_TAG = "AuriHeadsetControl"

/**
 * 系统的媒体按键通道：一个 MediaSession。只在 [activate] 与 [deactivate] 之间存在——控制模式关闭 = 会话已释放。
 *
 * 为什么这样用（每一条都是刻意的）：
 *  - 只重写 onMediaButtonEvent 并返回 true，**不调用 super**：super 会再把同一个按键翻成 onPlay / onPause 回调，
 *    同一个手势就有两个通道递送（FR-HS-004 要求只选一个主事件源），还会把连按两次合成成“下一首”。
 *  - 会话只声明 PAUSED（暂停）与可用动作，**绝不声明 PLAYING**：不播放任何东西，不用静音音频或假通话保活。
 *    这是有代价的，已在 API 35 模拟器上实测（E2，注入的键）：系统按“实际在放音频的应用”挑媒体键会话，只声明状态不够——
 *    没有音频在播时，无状态 / PAUSED / PLAYING 三种声明 × 两种注入方式，dumpsys 都是 “Media button session is null”，
 *    注入的键 0/6 送达；同一进程放着真音频时 6/6 送达（仅诊断，不进产品；复现见 execution/acceptance/android-control/probes/）。
 *    所以真机上这个通道很可能同样收不到键，要靠校准向导的真机结果说话
 *    （README 里标 NOT_RUN），屏幕按钮是兜底。要不要为了收键改成“声明 PLAYING”或别的办法，是要先评审合规性的范围决策
 *    （android-probe README 的 S8），不是这里能定的。
 *  - 不声明任何清单权限、不注册任何广播接收器；回调在主线程。
 *  - 每个框架调用都可能抛异常：逐个包住，一个失败不会跳过后面的释放。日志只写状态与原因，不写设备名。
 *
 * 系统不会通知应用“别的应用抢走了媒体按键”，所以 [activate] 成功不等于“按键一定会到”（FR-HS-005）。
 */
class AndroidMediaButtonSource(
    private val context: Context,
    private val clock: Clock,
    private val log: (String) -> Unit = {},
) : MediaButtonSource {
    private val lock = Any()
    private var session: MediaSession? = null

    override fun activate(listener: (RawMediaInput) -> Unit): Boolean = synchronized(lock) {
        releaseLocked()
        val created = try {
            MediaSession(context, MEDIA_SESSION_TAG)
        } catch (e: Exception) {
            log("media_session_create_failed reason=${e.javaClass.simpleName}")
            return@synchronized false
        }
        try {
            created.setCallback(
                object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        deliver(mediaButtonIntent, listener)
                        return true
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            created.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(SUPPORTED_ACTIONS)
                    .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .build(),
            )
            created.isActive = true
        } catch (e: Exception) {
            log("media_session_setup_failed reason=${e.javaClass.simpleName}")
            releaseSession(created)
            return@synchronized false
        }
        session = created
        true
    }

    override fun deactivate() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        val current = session ?: return
        session = null
        releaseSession(current)
    }

    private fun releaseSession(target: MediaSession) {
        // 三步各自包住：回调先摘掉（之后不再有按键进来），再取消激活，最后释放
        runCatchingLogged("media_session_clear_callback") { target.setCallback(null) }
        runCatchingLogged("media_session_deactivate") { target.isActive = false }
        runCatchingLogged("media_session_release") { target.release() }
    }

    private fun deliver(intent: Intent, listener: (RawMediaInput) -> Unit) {
        try {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
            val event = keyEventOf(intent) ?: return
            val receivedAtNs = clock.monotonicNs()
            val uptimeNowMs = AndroidSystemClock.uptimeMillis()
            listener(
                rawInputOf(
                    keyCode = event.keyCode,
                    action = event.action,
                    repeatCount = event.repeatCount,
                    eventTimeMs = event.eventTime,
                    fromExternalDevice = event.device?.isExternal,
                    uptimeNowMs = uptimeNowMs,
                    receivedAtNs = receivedAtNs,
                ),
            )
        } catch (e: Exception) {
            log("media_button_failed reason=${e.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun keyEventOf(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private inline fun runCatchingLogged(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("${what}_failed reason=${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val SUPPORTED_ACTIONS: Long =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
}
