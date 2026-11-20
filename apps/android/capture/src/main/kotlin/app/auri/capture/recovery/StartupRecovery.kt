package app.auri.capture.recovery

import kotlinx.coroutines.CancellationException

// 启动时的恢复入口（Ticket 19 第 3、4 项）：应用每次启动都走它，测试也走它，所以“重启之后只做这些事”是可以证明的。

/**
 * 启动时的恢复：把上次没来得及交付的录音找回来登记成 Record（见 [OrphanRecovery]）。
 *
 * 它**只依赖 Record 这一侧**（存储里的孤儿、Record 库、Record 管线），手里没有协调器、控制适配器、采集适配器、前台服务的任何引用：
 * 恢复不可能开麦、起前台服务、开启耳机控制或开始录音，重启之后采集一直是 DISARMED（第 12 章）。
 *
 * 恢复失败不能让应用起不来：失败时返回 null，什么都不抛；录音原样留在磁盘上，下次启动再来一遍。
 * 日志只记计数与失败类别，不带路径、Record 标识或异常信息（异常消息里常有文件路径）。
 */
class StartupRecovery(private val recovery: OrphanRecovery, private val log: (String) -> Unit) {
    /** 返回这次恢复的结果；恢复本身失败时返回 null。 */
    suspend fun run(): RecoveryReport? = try {
        recovery.run().also { log("orphan recovery: recovered=${it.recovered} withoutAudio=${it.withoutAudio} failed=${it.failed}") }
    } catch (e: CancellationException) {
        throw e // 应用在恢复中途退出：取消照常传出去，不当成恢复失败
    } catch (e: Exception) {
        log("orphan recovery failed")
        null
    }
}
