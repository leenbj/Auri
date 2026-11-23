@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 22 第四轮 F10（再评 S11 的缺口）：旧的归属认定只对第 0 项的文字算摘要。别的应用复制一个多项的剪贴板（第 0 项恰好与凭证逐字相同，后面还有别的项），
 * 或者那一项除文字外还带着 HTML / URI / Intent，那已经不是我们放的那一份，我们却会当成自己的清掉——连带抹掉后面那些别人的内容。
 * 现在要求“我们放进去时的原样”：恰好一项、类型只有 text/plain、这一项只有文字（没有 HTML、URI、Intent），并且整段文字的摘要相同。
 */
class ClipShapeTest {
    private companion object {
        const val SECRET = "dc_live_not-a-real-credential-0123456789"
        const val FOREIGN = "会议室门牌 4F-12"
        val PLAIN = listOf("text/plain")
    }

    private fun held(): ClipOwnership = ClipOwnership().also { it.remember(SECRET, at = 3) }

    private fun classify(ownership: ClipOwnership, snapshot: ClipSnapshot?) = ownership.classifyClip { snapshot }

    @Test
    fun `我们放进去时的原样（一项、只有 text-plain、只有文字）读回来逐字相同：认`() {
        val snapshot = ClipSnapshot(PLAIN, listOf(ClipItemSnapshot(SECRET)))

        assertEquals(ClipboardPeek.Ours(3), classify(held(), snapshot))
    }

    @Test
    fun `多出一项：第 0 项与凭证逐字相同，后面还有别的内容——不是我们放的，不认`() {
        val snapshot = ClipSnapshot(PLAIN, listOf(ClipItemSnapshot(SECRET), ClipItemSnapshot(FOREIGN)))

        assertEquals(ClipboardPeek.Other, classify(held(), snapshot))
    }

    @Test
    fun `第 0 项文字相同，但这一项除文字外还带着 HTML、URI 或 Intent：不认`() {
        for (extra in listOf(
            ClipItemSnapshot(SECRET, hasHtml = true),
            ClipItemSnapshot(SECRET, hasUri = true),
            ClipItemSnapshot(SECRET, hasIntent = true),
        )) {
            assertEquals("$extra", ClipboardPeek.Other, classify(held(), ClipSnapshot(PLAIN, listOf(extra))))
        }
    }

    @Test
    fun `类型不是原来的 text-plain（多了 text-html 或 text-uri-list）：不认`() {
        for (types in listOf(listOf("text/plain", "text/html"), listOf("text/html"), listOf("text/uri-list"), emptyList())) {
            val snapshot = ClipSnapshot(types, listOf(ClipItemSnapshot(SECRET)))

            assertEquals("$types", ClipboardPeek.Other, classify(held(), snapshot))
        }
    }

    @Test
    fun `没有文字（图片、文件）或一项都没有：不认`() {
        assertEquals(ClipboardPeek.Other, classify(held(), ClipSnapshot(listOf("image/png"), listOf(ClipItemSnapshot(text = null, hasUri = true)))))
        assertEquals(ClipboardPeek.Other, classify(held(), ClipSnapshot(PLAIN, emptyList())))
    }

    @Test
    fun `认不出之后就忘了：同一份内容之后再原样出现，也不再认`() {
        val ownership = held()
        classify(ownership, ClipSnapshot(PLAIN, listOf(ClipItemSnapshot(SECRET), ClipItemSnapshot(FOREIGN))))

        assertEquals(ClipboardPeek.Other, classify(ownership, ClipSnapshot(PLAIN, listOf(ClipItemSnapshot(SECRET)))))
    }

    @Test
    fun `经过守卫：别的应用的多项剪贴板（第 0 项与凭证相同）在离开时不被清掉，后面那些别人的内容原样还在`() = runTest {
        var clip: ClipSnapshot? = null
        var clears = 0
        val ownership = ClipOwnership()
        val port = object : ClipboardPort {
            override fun put(secret: String, at: Long) {
                ownership.remember(secret, at)
                clip = ClipSnapshot(PLAIN, listOf(ClipItemSnapshot(secret)))
                ownership.startWatching()
            }

            override fun peek() = ownership.classifyClip { clip }

            override fun clear() {
                clip = null
                clears++
                ownership.forget()
            }
        }
        val guard = SecretClipboardGuard(port, backgroundScope, { 0L })
        guard.copy(SECRET)
        val theirs = ClipSnapshot(PLAIN, listOf(ClipItemSnapshot(SECRET), ClipItemSnapshot(FOREIGN)))
        clip = theirs

        guard.onLeaving()

        assertEquals("别人的多项内容原样还在", theirs, clip)
        assertEquals("一次都没清过", 0, clears)
    }
}
