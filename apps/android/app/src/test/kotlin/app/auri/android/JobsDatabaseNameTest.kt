package app.auri.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JobsDatabaseNameTest {
    @Test
    fun `任务库的文件名跟着 Record 库走：默认库对应默认任务库，别的库名对应别的任务库`() {
        assertEquals("auri-jobs.db", jobsDatabaseNameFor("auri.db"))
        assertNotEquals(
            "测试用自己的库名，就不会读到真应用留下的任务（否则别人的任务会被这次的调度器认领）",
            jobsDatabaseNameFor("auri.db"),
            jobsDatabaseNameFor("capture-test-7.db"),
        )
        assertNotEquals("任务库与 Record 库是两个文件", "auri.db", jobsDatabaseNameFor("auri.db"))
    }
}
