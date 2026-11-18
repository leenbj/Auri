package app.auri.capture.testing

import org.junit.Rule
import org.junit.rules.Timeout

/**
 * 跑在虚拟时间里的调度器测试的基类。调度器一旦出现活锁（同一个虚拟时刻内永远处理不完），`runCurrent()` 在真实时间里
 * 不会返回，整个测试套件就挂死（变异检查时真的发生过：一个把“引擎拒绝过的模型”忘掉的变体让测试 JVM 空转了十几分钟）。
 * 这条规则把挂死变成这一个测试的失败。30 秒远多于任何正常测试所需（毫秒级）。
 */
abstract class BoundedSchedulerTest {
    @get:Rule
    val watchdog: Timeout = Timeout.seconds(30)
}
