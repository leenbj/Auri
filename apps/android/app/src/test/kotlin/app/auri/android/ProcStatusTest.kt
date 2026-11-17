package app.auri.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcStatusTest {
    private val status = "Name:\tapp.auri.android\nVmPeak:\t 9999999 kB\nVmHWM:\t  123456 kB\nVmRSS:\t   98765 kB\nThreads:\t17\n"

    @Test
    fun `读出 VmHWM 与 VmRSS 的千字节数`() {
        assertEquals(123456L, ProcStatus.kb(status, "VmHWM"))
        assertEquals(98765L, ProcStatus.kb(status, "VmRSS"))
    }

    @Test
    fun `字段名必须完整匹配 VmHWM 不会误读成 VmPeak`() {
        assertEquals(9999999L, ProcStatus.kb(status, "VmPeak"))
        assertNull(ProcStatus.kb(status, "Vm"))
    }

    @Test
    fun `字段不存在或数字不合法时返回 null 而不是抛异常`() {
        assertNull(ProcStatus.kb(status, "VmSwap"))
        assertNull(ProcStatus.kb("VmHWM:\tabc kB\n", "VmHWM"))
        assertNull(ProcStatus.kb("", "VmHWM"))
    }
}
