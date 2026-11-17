package app.auri.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 测试工具自己的自检：编辑距离算错的话，“识别结果接近参考”这类断言就毫无意义。 */
@RunWith(AndroidJUnit4::class)
class TestAudioTest {
    @Test
    fun editDistanceMatchesTheTextbookExamples() {
        assertEquals(3, TestAudio.editDistance("kitten", "sitting"))
        assertEquals(0, TestAudio.editDistance("", ""))
        assertEquals(4, TestAudio.editDistance("", "abcd"))
        assertEquals(1, TestAudio.editDistance("开饭时间", "开饭时问"))
    }

    @Test
    fun relativeDistanceIsZeroForIdenticalTextAndOneForDisjointText() {
        assertEquals(0.0, TestAudio.relativeDistance("Hello, World!", "hello world"), 0.0)
        assertEquals(1.0, TestAudio.relativeDistance("abcd", "wxyz"), 0.0)
        assertEquals(0.25, TestAudio.relativeDistance("abcx", "abcd"), 1e-9)
    }

    @Test
    fun normalizeKeepsLettersAndDigitsOfAnyScript() {
        assertEquals("开饭时间早上9点至下午5点", TestAudio.normalize("开饭时间早上9点至下午5点。"))
        assertEquals("조금만생각을", TestAudio.normalize("조금만 생각을."))
    }
}
