package app.auri.platform.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackNotificationsTest {
    private class FakeNotifications : FeedbackNotificationGateway {
        val channels = mutableMapOf("auri-feedback" to true)
        var postedChannel: String? = null
        var audible = false
        var silent = false
        var onlyAlertOnce = false

        override fun deleteChannel(id: String) { channels.remove(id) }
        override fun createSilentChannel(id: String) { channels.putIfAbsent(id, false) }
        override fun post(id: String, text: String, silent: Boolean, onlyAlertOnce: Boolean) {
            postedChannel = id
            audible = channels.getValue(id) && !silent
            this.silent = silent
            this.onlyAlertOnce = onlyAlertOnce
        }
    }

    @Test
    fun `previously audible channel is replaced and every feedback notification is silent`() {
        val fake = FakeNotifications()
        FeedbackNotifications(fake).show("fixture")

        assertFalse(fake.channels.containsKey("auri-feedback"))
        assertNotEquals("auri-feedback", fake.postedChannel)
        assertEquals(false, fake.channels.getValue(fake.postedChannel!!))
        assertFalse(fake.audible)
        assertTrue(fake.silent)
        assertTrue(fake.onlyAlertOnce)
    }

    @Test
    fun `notification remains silent if the user changes the new channel to audible`() {
        val fake = FakeNotifications()
        fake.channels[AndroidFeedbackPlayer.CHANNEL_ID] = true
        FeedbackNotifications(fake).show("fixture")

        assertTrue(fake.channels.getValue(AndroidFeedbackPlayer.CHANNEL_ID))
        assertTrue(fake.silent)
        assertFalse(fake.audible)
        assertTrue(fake.onlyAlertOnce)
    }
}
