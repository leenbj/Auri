package app.auri.platform.feedback

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.auri.capture.feedback.FeedbackKind
import app.auri.capture.feedback.Feedbacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 反馈在设备上真的发出来，并且没有声音（证据等级 E2：模拟器）。
 * 证明：三种反馈各发一条通知，文字与 Feedbacks 里的一致（用户能分清）；通知渠道没有声音、不自带振动、重要性低
 * （不会弹出、不会响）；play 不抛异常。
 * 不证明：振动在手上的感觉与节奏是否容易分清（要真机、有人）；通知是否在锁屏上显示（取决于用户的系统设置）。
 */
@RunWith(AndroidJUnit4::class)
class FeedbackPlayerOnDeviceTest {
    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val player = AndroidFeedbackPlayer(context)

    @After
    fun clear() {
        manager.cancel(AndroidFeedbackPlayer.NOTIFICATION_ID)
    }

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /**
     * 等到我们的通知文字变成 [expectedText]。通知的发出与取消都由系统异步处理：读得太早，会读到上一次留下的同标识通知
     * （这个测试以前每轮先 cancel 再立刻读：连跑四次设备验收，有两次读到的是上一轮那一种的文字）。
     * 等满 5 秒仍不是这段文字，就返回最后读到的那条，让断言报出实际的文字；一条都没有才是 null。
     */
    private fun posted(expectedText: String): Notification? {
        var last: Notification? = null
        repeat(50) {
            last = manager.activeNotifications.firstOrNull { it.id == AndroidFeedbackPlayer.NOTIFICATION_ID }?.notification
            if (last?.text() == expectedText) return last
            Thread.sleep(100)
        }
        return last
    }

    @Test
    fun eachFeedbackKindPostsItsOwnSilentNotification() {
        // 与真实使用一样，两次之间不取消：后一条通知按同一个标识替换前一条。顺序让每一种都跟在另一种之后（没有相邻重复）
        val order = FeedbackKind.entries + FeedbackKind.entries.reversed().drop(1)
        order.forEach { kind ->
            val feedback = Feedbacks.of(kind)

            player.play(feedback)

            val notification = posted(feedback.notificationText)
            assertNotNull("$kind：应当发出一条通知", notification)
            assertEquals("$kind：通知文字", feedback.notificationText, notification!!.text())
        }
    }

    @Suppress("DEPRECATION") // Notification.sound 在有渠道的系统上不起作用，这里只是确认没人往里塞声音
    @Test
    fun theNotificationChannelHasNoSoundNoVibrationAndLowImportance() {
        val started = Feedbacks.of(FeedbackKind.STARTED)
        player.play(started)
        val notification = checkNotNull(posted(started.notificationText))

        val channel = checkNotNull(manager.getNotificationChannel(notification.channelId))

        assertEquals(AndroidFeedbackPlayer.CHANNEL_ID, channel.id)
        assertNull("渠道不能有声音：录音走的是蓝牙通路", channel.sound)
        assertFalse("渠道自己不振动（振动由节奏单独发出）", channel.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertTrue("通知本身也不带声音", notification.sound == null)
    }
}
