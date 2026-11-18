package app.auri.platform.feedback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import app.auri.capture.feedback.Feedback
import app.auri.capture.feedback.FeedbackPlayer

/**
 * 开始、停止、没能开始三种提示：振动节奏 + 一条静音通知（屏幕上的文字由界面自己显示）。
 *
 * 没有声音，是刻意的：耳机麦克风正在录音，任何提示音都会走蓝牙通路进入录音（NoSoundIntoBluetoothTest 守着源码，
 * FeedbackPlayerOnDeviceTest 在设备上核对通知渠道没有声音）。
 * 每个框架调用都可能抛异常（没有振动器、没有通知权限、系统服务缺失）：包住，只记原因，不影响录音，也不向外抛。
 * 通知没有通知权限时系统会悄悄丢弃，这不是错误。日志只写步骤与异常类名，不写通知文字。
 */
class AndroidFeedbackPlayer(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : FeedbackPlayer {
    override fun play(feedback: Feedback) {
        guarded("vibrate") { vibrate(feedback) }
        guarded("notify") { notify(feedback) }
    }

    // 振动按“通知”这一类用途发出：应用在后台、屏幕锁着时系统仍允许通知类的振动（别的用途会被当作后台噪音丢弃）
    @Suppress("DEPRECATION")
    private fun vibrate(feedback: Feedback) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(feedback.vibrationPattern.toLongArray(), NO_REPEAT)
        vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
    }

    private fun notify(feedback: Feedback) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "录音反馈", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false) // 振动由 vibrate() 按各自的节奏发出
                setShowBadge(false)
            },
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Auri")
            .setContentText(feedback.notificationText)
            .setTimeoutAfter(TIMEOUT_MS)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun guarded(step: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("feedback_${step}_failed reason=${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val CHANNEL_ID = "auri-feedback"

        /** 与前台服务的通知（编号 1）区分开；同一编号后来的反馈替换前一条，不会越堆越多。 */
        const val NOTIFICATION_ID = 17
        private const val TIMEOUT_MS = 5_000L
        private const val NO_REPEAT = -1
    }
}
