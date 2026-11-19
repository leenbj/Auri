package app.auri.capture.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 20 第 4 项的规则部分：什么读数算“资源不足”，以及多项同时不足时报哪一个原因。
 * 阈值是没有真机标定的保守初值（见 [ResourceThresholds] 的注释）；这里钉的是“边界落在哪一侧”，改阈值要有意识地改这里。
 */
class ResourcePolicyTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024
    private val fine = ResourceReadings(
        thermalStatus = 0,
        batterySaver = false,
        availableMemoryBytes = 2 * gib,
        systemLowMemory = false,
        freeStorageBytes = 10 * gib,
    )

    private fun low(readings: ResourceReadings) = ResourcePolicy.evaluate(readings)

    @Test
    fun `一切正常就是 Ok`() {
        assertEquals(ResourceStatus.Ok, low(fine))
    }

    @Test
    fun `温度：中等（2）仍然可以转，严重（3）起等；越热越等`() {
        assertEquals(ResourceStatus.Ok, low(fine.copy(thermalStatus = 2)))
        for (status in 3..6) {
            assertEquals("热状态 $status", ResourceStatus.Low(ResourceReason.THERMAL), low(fine.copy(thermalStatus = status)))
        }
    }

    @Test
    fun `省电模式开着就等，关了就好`() {
        assertEquals(ResourceStatus.Low(ResourceReason.BATTERY_SAVER), low(fine.copy(batterySaver = true)))
        assertEquals(ResourceStatus.Ok, low(fine.copy(batterySaver = false)))
    }

    @Test
    fun `内存：可用差一个字节不到 800 MiB 就等，正好 800 MiB 可以；系统自报内存紧张也等`() {
        assertEquals(ResourceStatus.Low(ResourceReason.LOW_MEMORY), low(fine.copy(availableMemoryBytes = 800 * mib - 1)))
        assertEquals(ResourceStatus.Ok, low(fine.copy(availableMemoryBytes = 800 * mib)))
        assertEquals(
            "系统说内存紧张，哪怕可用字节数看起来够",
            ResourceStatus.Low(ResourceReason.LOW_MEMORY),
            low(fine.copy(systemLowMemory = true)),
        )
    }

    @Test
    fun `存储：可用差一个字节不到 100 MiB 就等，正好 100 MiB 可以`() {
        assertEquals(ResourceStatus.Low(ResourceReason.LOW_STORAGE), low(fine.copy(freeStorageBytes = 100 * mib - 1)))
        assertEquals(ResourceStatus.Ok, low(fine.copy(freeStorageBytes = 100 * mib)))
    }

    @Test
    fun `多项同时不足只报一个原因：温度、省电模式、内存、存储`() {
        val everythingBad = ResourceReadings(
            thermalStatus = 4,
            batterySaver = true,
            availableMemoryBytes = 0,
            systemLowMemory = true,
            freeStorageBytes = 0,
        )
        assertEquals(ResourceReason.THERMAL, (low(everythingBad) as ResourceStatus.Low).reason)
        assertEquals(ResourceReason.BATTERY_SAVER, (low(everythingBad.copy(thermalStatus = 0)) as ResourceStatus.Low).reason)
        assertEquals(
            ResourceReason.LOW_MEMORY,
            (low(everythingBad.copy(thermalStatus = 0, batterySaver = false)) as ResourceStatus.Low).reason,
        )
        assertEquals(
            ResourceReason.LOW_STORAGE,
            (low(everythingBad.copy(thermalStatus = 0, batterySaver = false, systemLowMemory = false, availableMemoryBytes = 4 * gib)) as ResourceStatus.Low).reason,
        )
    }

    @Test
    fun `阈值可以换：同样的读数在更严的阈值下就等`() {
        val strict = ResourceThresholds(thermalBlockAt = 2, minAvailableMemoryBytes = 4 * gib, minFreeStorageBytes = 20 * gib)
        assertEquals(ResourceStatus.Low(ResourceReason.THERMAL), ResourcePolicy.evaluate(fine.copy(thermalStatus = 2), strict))
        assertEquals(ResourceStatus.Low(ResourceReason.LOW_MEMORY), ResourcePolicy.evaluate(fine, strict))
        assertEquals(
            ResourceStatus.Low(ResourceReason.LOW_STORAGE),
            ResourcePolicy.evaluate(fine.copy(availableMemoryBytes = 8 * gib), strict),
        )
    }
}
