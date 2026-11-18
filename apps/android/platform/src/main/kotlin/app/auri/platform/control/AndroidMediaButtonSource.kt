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

/**
 * 系统的媒体按键通道：一个 MediaSession。只在 [activate] 与 [deactivate] 之间存在——控制模式关闭 = 会话已释放。
 *
 * 为什么这样用（每一条都是刻意的）：
 *  - 只重写 onMediaButtonEvent 并返回 true，**不调用 super**：super 会再把同一个按键翻成 onPlay / onPause 回调，
 *    同一个手势就有两个通道递送（FR-HS-004 要求只选一个主事件源），还会把连按两次合成成“下一首”。
 *  - 会话只声明 PAUSED（暂停）与可用动作，**绝不声明 PLAYING**：不播放任何东西，不用静音音频或假通话保活。
 *    PAUSED 的会话是否真的能收到耳机按键，是真机问题，要靠校准向导的结果说话（README 里标 NOT_RUN）。
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
            MediaSession(context, SESSION_TAG)
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
        const val SESSION_TAG = "AuriHeadsetControl"

        const val SUPPORTED_ACTIONS: Long =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
}
