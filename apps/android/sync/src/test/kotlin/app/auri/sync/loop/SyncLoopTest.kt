@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.sync.loop

import app.auri.sync.engine.JobKey
import app.auri.sync.engine.RetryPolicy
import app.auri.sync.engine.SyncJob
import app.auri.sync.engine.SyncJobStore
import app.auri.sync.machine.SyncState
import app.auri.sync.testing.FakeGateway.Fault
import app.auri.sync.testing.FakeNetwork
import app.auri.sync.testing.SyncRig
import app.auri.sync.testing.SyncSamples
import app.auri.sync.testing.TlsFixtures
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步循环：什么时候去跑一轮同步。引擎保证“一轮里做对”，循环保证“该跑的时候一定会跑、不该跑的时候不跑”：
 * 启动、有新任务、网络恢复、重试时间到、网关一直连不上时按退避去探——并且永远不会对被永久拒绝的任务反复请求。
 * 全部用虚拟时间，引擎是真的，网络、网关、局域网是假的。
 */
class SyncLoopTest {
    private fun loopRig(gatewayOnLan: Boolean = true, body: suspend TestScope.(SyncRig, FakeNetwork, SyncLoop) -> Unit) = runTest {
        val rig = SyncRig(this, gatewayOnLan = gatewayOnLan)
        val network = FakeNetwork()
        body(rig, network, SyncLoop(rig.engine, rig.jobs, network, rig.clock, RetryPolicy(), rig.log))
    }

    private fun SyncRig.bringGatewayUp() = lan.host(SyncSamples.LAN_ADDRESS, TlsFixtures.gatewayA) { server.handle(it) }

    private suspend fun TestScope.after(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun SyncRig.passes() = log.lines.count { it == "loop.pass" }

    // ---- 什么时候跑一轮 ----

    @Test
    fun `启动时先跑一轮：上次留下的排队任务被发出去（重启之后不会丢）`() = loopRig { rig, _, loop ->
        val key = rig.publish()

        loop.start(backgroundScope)
        runCurrent()

        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `启动时接回上次被杀时卡在发送中的任务：它不是排队中的，只有启动那一轮能发现它；先问网关再决定`() = loopRig { rig, _, loop ->
        val key = rig.publish()
        rig.jobs.update(rig.job(key).copy(state = SyncState.SENDING, attempts = 1, uncertain = true)) // 进程在发送途中被杀留下的样子

        loop.start(backgroundScope)
        runCurrent()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals("先问网关有没有，再提交", listOf("GET status ${key.recordId}", "PUT ${key.recordId}#1"), rig.server.calls.toList())
    }

    @Test
    fun `运行中有新任务（发布了新的 Revision）：不需要任何别的触发，自己就发出去`() = loopRig { rig, _, loop ->
        loop.start(backgroundScope)
        runCurrent()

        val key = rig.publish()
        runCurrent()

        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `网络恢复就跑一轮：网关在等的任务补传；在这之前什么事件都没有，它就一直等着`() = loopRig(gatewayOnLan = false) { rig, network, loop ->
        val key = rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(key).state)
        network.set(false)
        runCurrent()

        rig.bringGatewayUp()
        runCurrent()
        assertEquals("网关回来了，但还没有任何事件告诉手机", SyncState.WAITING_GATEWAY, rig.job(key).state)

        network.set(true)
        runCurrent()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertEquals(1, rig.server.putCount)
    }

    @Test
    fun `默认网络换了一个而信号一直是可用（Wi-Fi 断开落到移动数据、Wi-Fi 回来又换回去）：也算网络变化，立刻跑一轮，不用干等探测的退避`() = loopRig(gatewayOnLan = false) { rig, network, loop ->
        val key = rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(key).state)
        after(2 * 60 * 60_000L) // 网关一直不在：探测间隔早已涨到封顶的 15 分钟，下一次探测最远还有一刻钟

        rig.bringGatewayUp() // 手机回到了局域网；手机常开着移动数据，所以系统给的只是“默认网络换了一个”，没有“没网→有网”
        runCurrent()
        assertEquals("网关回来了，但还没有任何事件告诉手机", SyncState.WAITING_GATEWAY, rig.job(key).state)

        network.switchToAnotherNetwork()
        runCurrent()

        assertEquals("换网络就该立刻补传，而不是再等最长一刻钟", SyncState.STORED, rig.job(key).state)
        assertEquals(1, rig.server.putCount)
    }

    @Test
    fun `网络断开：排队与等待重试的任务改为等待网关，断网期间不再碰网关，网络回来后补传`() = loopRig { rig, network, loop ->
        rig.server.faults += Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)
        val key = rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        assertEquals(SyncState.RETRY_SCHEDULED, rig.job(key).state)

        network.set(false)
        runCurrent()
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(key).state)
        val requests = rig.lan.attempts.size + rig.server.calls.size

        after(24 * 60 * 60_000L)
        assertEquals("断网时不探测、不重试", requests, rig.lan.attempts.size + rig.server.calls.size)

        network.set(true)
        runCurrent()
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `网关答 503：到了重试时间才重发，不早也不晚`() = loopRig { rig, _, loop ->
        rig.server.faults += Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)
        val key = rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        assertEquals(1, rig.server.putCount)
        assertEquals(SyncState.RETRY_SCHEDULED, rig.job(key).state)

        after(4_999)
        assertEquals("还差 1 毫秒", 1, rig.server.putCount)

        after(1)
        assertEquals(2, rig.server.putCount)
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `一直失败时重试间隔逐次翻倍，封顶在退避上限，永远不会密集重试`() = loopRig { rig, _, loop ->
        repeat(40) { rig.server.faults += Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true) }
        val key = rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        val attemptTimes = mutableListOf(0L)
        var elapsed = 0L
        // 第一次失败之后的每次重试都先问网关结果（“可能已送到”），所以数的是到达网关的请求，不只是 PUT
        var seen = rig.server.calls.size

        val step = 1_000L
        while (elapsed < 6 * 60 * 60_000L) {
            after(step)
            elapsed += step
            if (rig.server.calls.size != seen) {
                assertEquals("每次只发一个请求", seen + 1, rig.server.calls.size)
                seen = rig.server.calls.size
                attemptTimes += elapsed
            }
        }

        val gaps = attemptTimes.zipWithNext { a, b -> b - a }
        assertEquals("前几次：5、10、20、40、80、160、320、640 秒", listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 320_000L, 640_000L), gaps.take(8))
        assertTrue("之后封顶在 15 分钟：$gaps", gaps.drop(8).all { it == 15 * 60_000L })
        assertEquals(SyncState.RETRY_SCHEDULED, rig.job(key).state)
    }

    @Test
    fun `网关一直连不上（网络是好的）：按退避去探，5、10、20 秒……封顶 15 分钟，找到了就补传`() = loopRig(gatewayOnLan = false) { rig, _, loop ->
        val key = rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        val perPass = rig.lan.attempts.size
        assertTrue("一轮至少试一个地址", perPass > 0)

        after(4_999)
        assertEquals(perPass, rig.lan.attempts.size)
        after(1)
        assertEquals("5 秒后第一次探测", perPass * 2, rig.lan.attempts.size)
        after(9_999)
        assertEquals(perPass * 2, rig.lan.attempts.size)
        after(1)
        assertEquals("再 10 秒后第二次探测", perPass * 3, rig.lan.attempts.size)

        // 很久以后：每 15 分钟一次，不会更密
        after(6 * 60 * 60_000L)
        val before = rig.lan.attempts.size
        after(60 * 60_000L)
        assertEquals("封顶之后每小时 4 次探测", 4 * perPass, rig.lan.attempts.size - before)

        rig.bringGatewayUp()
        after(15 * 60_000L)
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `被永久拒绝的任务无论过多久、网络怎么变、被催多少次，都不会再请求网关`() = loopRig { rig, network, loop ->
        rig.server.faults += Fault.Reject(409, "RECORD_VERSION_CONFLICT", retryable = false)
        val key = rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        assertEquals(SyncState.BLOCKED, rig.job(key).state)
        val requests = rig.server.calls.size
        val attempts = rig.lan.attempts.size

        network.set(false)
        runCurrent()
        network.set(true)
        runCurrent()
        repeat(5) { loop.nudge() }
        runCurrent()
        val passes = rig.passes()
        after(7 * 24 * 60 * 60_000L)

        assertEquals(SyncState.BLOCKED, rig.job(key).state)
        assertEquals("没有再发 PUT 或查询", requests, rig.server.calls.size)
        assertEquals("连探测都没有", attempts, rig.lan.attempts.size)
        assertEquals("也没有任何计时器去唤醒新的一轮", passes, rig.passes())
    }

    @Test
    fun `nudge 立刻跑一轮，并且退避从头开始`() = loopRig(gatewayOnLan = false) { rig, _, loop ->
        val key = rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        val perPass = rig.lan.attempts.size
        after(5_000 + 10_000 + 20_000) // 已经探了三次，下一次要等 40 秒
        assertEquals(perPass * 4, rig.lan.attempts.size)

        loop.nudge()
        runCurrent()
        assertEquals("nudge 马上探一次", perPass * 5, rig.lan.attempts.size)
        after(4_999)
        assertEquals(perPass * 5, rig.lan.attempts.size)
        after(1)
        assertEquals("退避从头开始：5 秒后再探，不是 40 秒", perPass * 6, rig.lan.attempts.size)

        rig.bringGatewayUp()
        loop.nudge()
        runCurrent()
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `网络恢复或默认网络换了一个：立刻探一次，并且退避从头开始（刚连上的那一刻网关未必已经应答）`() = loopRig(gatewayOnLan = false) { rig, network, loop ->
        val key = rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        val perPass = rig.lan.attempts.size
        after(5_000 + 10_000 + 20_000) // 已经探了三次，下一次要等 40 秒
        assertEquals(perPass * 4, rig.lan.attempts.size)

        network.switchToAnotherNetwork() // 刚连上 Wi-Fi：网关还没应答
        runCurrent()
        assertEquals("换了网络马上探一次", perPass * 5, rig.lan.attempts.size)
        after(4_999)
        assertEquals(perPass * 5, rig.lan.attempts.size)
        after(1)
        assertEquals("退避从头开始：5 秒后再探，不是 40 秒（也不是已经睡着的旧计时器的剩余时间）", perPass * 6, rig.lan.attempts.size)

        rig.bringGatewayUp() // 网关应答了：下一次探测（再过 10 秒）就能补传
        after(10_000)
        assertEquals(SyncState.STORED, rig.job(key).state)
    }

    // ---- F11：网络变了，等待重试的任务不再干等旧的重试时间 ----

    private suspend fun SyncRig.parkInLongBackoff(key: JobKey) {
        // 已经连失败了六次：退避涨到 5·2^5 = 160 秒，下一次重试还要等一阵
        jobs.update(job(key).copy(state = SyncState.RETRY_SCHEDULED, attempts = 6, uncertain = true, retryAt = clock.now().plusSeconds(160)))
    }

    @Test
    fun `默认网络换了一个：等待重试的任务不再干等旧的重试时间，立刻再试（旧的失败发生在另一个网络上）`() = loopRig { rig, network, loop ->
        val key = rig.publish()
        rig.parkInLongBackoff(key)
        loop.start(backgroundScope)
        runCurrent()
        assertEquals("启动那一轮：还没到重试时间，什么也不发", SyncState.RETRY_SCHEDULED, rig.job(key).state)

        network.switchToAnotherNetwork()
        runCurrent()

        assertEquals("换了网络就该立刻重试，而不是再等两分多钟", SyncState.STORED, rig.job(key).state)
        assertEquals("上次的结果不确定：先问再发", listOf("GET status ${key.recordId}", "PUT ${key.recordId}#1"), rig.server.calls.toList())
    }

    @Test
    fun `网络变了之后再失败：退避从头算（5 秒），不是接着旧的 160 秒翻倍；已持久化的次数也清零了`() = loopRig { rig, network, loop ->
        val key = rig.publish()
        rig.parkInLongBackoff(key)
        loop.start(backgroundScope)
        runCurrent()

        rig.server.faults += Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true) // 新网络上网关也答 503
        network.switchToAnotherNetwork()
        runCurrent()

        val job = rig.job(key)
        assertEquals(SyncState.RETRY_SCHEDULED, job.state)
        assertEquals("次数从头数：这一次失败是第 1 次", 1, job.attempts)
        assertEquals(Duration.ofMillis(RetryPolicy().delayMs(1)), Duration.between(rig.clock.now(), job.retryAt))
    }

    // ---- F12：发送途中断网 ----

    @Test
    fun `发送途中断了网、请求随之被中断：任务等网关，网络一回来就先问再补传，不用等旧的重试时间`() = loopRig { rig, network, loop ->
        val gate = CompletableDeferred<Fault?>()
        rig.server.holdNext = gate
        loop.start(backgroundScope)
        runCurrent()
        val key = rig.publish()
        runCurrent()
        assertEquals(SyncState.SENDING, rig.job(key).state)

        network.set(false)
        runCurrent()
        gate.complete(Fault.LoseRequest)
        runCurrent()
        assertEquals("断网时排重试倒计时没有意义", SyncState.WAITING_GATEWAY, rig.job(key).state)
        assertTrue(rig.job(key).uncertain)

        network.set(true)
        runCurrent()

        assertEquals("网络一回来就补传", SyncState.STORED, rig.job(key).state)
        assertEquals(listOf("PUT ${key.recordId}#1", "GET status ${key.recordId}", "PUT ${key.recordId}#1"), rig.server.calls.toList())
    }

    @Test
    fun `网络回来之后：失败又按正常的重试倒计时算，不会还当成断着网`() = loopRig { rig, network, loop ->
        loop.start(backgroundScope)
        runCurrent()
        network.set(false)
        runCurrent()
        network.set(true)
        runCurrent()

        rig.server.faults += Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)
        val key = rig.publish()
        runCurrent()

        assertEquals(SyncState.RETRY_SCHEDULED, rig.job(key).state)
    }

    // ---- 不出错 ----

    @Test
    fun `正在发送的请求不会被任务表的变化打断：只发一次，不重复也不取消`() = loopRig { rig, _, loop ->
        val gate = CompletableDeferred<Fault?>()
        rig.server.holdNext = gate
        val first = rig.publish(SyncSamples.RECORD_ID, 1)
        loop.start(backgroundScope)
        runCurrent()
        assertEquals(SyncState.SENDING, rig.job(first).state)

        val second = rig.publish(SyncSamples.OTHER_RECORD_ID, 1) // 任务表又变了、又有新任务
        runCurrent()
        assertEquals("第一条还在发送中", SyncState.SENDING, rig.job(first).state)

        gate.complete(null)
        runCurrent()

        assertEquals(listOf(SyncState.STORED, SyncState.STORED), listOf(first, second).map { rig.job(it).state })
        assertEquals("每条只发了一次", 2, rig.server.putCount)
        assertEquals(1, rig.job(first).attempts)
    }

    // 上一条测试里在途的请求总是专用循环发的，碰不到“一轮跑在会被取消的协程里”这种写法。
    // 真正会出事的是这一条：专用循环正忙着发别的任务时重试计时器响了，锁（先来先得）先交给后者，请求就发在会被取消的协程里。
    @Test
    fun `别的任务正占着这一轮时重试计时器响了：轮到重试的那条照常只发一次，不会因为任务表又变了而被取消、再多发一遍`() = loopRig { rig, _, loop ->
        rig.server.faults += Fault.Reject(503, "GATEWAY_UNREACHABLE", retryable = true)
        val retried = rig.publish(SyncSamples.RECORD_ID, 1)
        loop.start(backgroundScope)
        runCurrent()
        assertEquals(SyncState.RETRY_SCHEDULED, rig.job(retried).state) // 5 秒后重试

        val busyGate = CompletableDeferred<Fault?>()
        rig.server.holdNext = busyGate
        val busy = rig.publish(SyncSamples.OTHER_RECORD_ID, 1)
        runCurrent()
        assertEquals("另一条正占着这一轮", SyncState.SENDING, rig.job(busy).state)

        after(5_000) // 重试计时器响了，可这一轮还没结束
        val retryGate = CompletableDeferred<Fault?>()
        rig.server.holdNext = retryGate // 重试的那条先问网关结果（上次可能已送到），这个请求停在这里
        busyGate.complete(null)
        runCurrent()

        assertEquals("占着的那条发完了", SyncState.STORED, rig.job(busy).state)
        assertEquals("轮到重试的那条，它的请求还在途中，任务表的每次变化都不能打断它", SyncState.SENDING, rig.job(retried).state)

        retryGate.complete(null)
        runCurrent()

        assertEquals(listOf(SyncState.STORED, SyncState.STORED), listOf(retried, busy).map { rig.job(it).state })
        assertEquals(
            "重试的那条：被 503 一次，之后问一次、提交一次；没有因为被取消而多出一次状态查询和一次重发",
            listOf("PUT ${retried.recordId}#1", "PUT ${busy.recordId}#1", "GET status ${retried.recordId}", "PUT ${retried.recordId}#1"),
            rig.server.calls.toList(),
        )
        assertEquals(2, rig.job(retried).attempts)
    }

    @Test
    fun `一堆催促合并成一次：一轮在跑的时候来了很多次 nudge，之后只多跑一轮`() = loopRig { rig, _, loop ->
        val gate = CompletableDeferred<Fault?>()
        rig.server.holdNext = gate
        rig.publish()
        loop.start(backgroundScope)
        runCurrent()
        assertEquals(1, rig.passes())

        repeat(50) { loop.nudge() }
        gate.complete(null)
        runCurrent()

        assertEquals("第一轮 + 合并后的一轮", 2, rig.passes())
    }

    @Test
    fun `循环的作用域被取消之后什么都不再发生`() = loopRig(gatewayOnLan = false) { rig, network, loop ->
        rig.publish()
        val running = loop.start(backgroundScope)
        runCurrent()

        running.cancel()
        runCurrent()
        val before = rig.lan.attempts.size

        loop.nudge()
        network.set(false)
        network.set(true)
        rig.publish(SyncSamples.OTHER_RECORD_ID, 1)
        after(2 * 60 * 60_000L)

        assertEquals(before, rig.lan.attempts.size)
    }

    @Test
    fun `网络信号自己出了故障：记一条日志，其余的触发照常工作`() = runTest {
        val rig = SyncRig(this)
        val broken = object : NetworkSignal {
            override val available: Flow<Boolean> = flow { throw IllegalStateException("系统服务不可用") }
        }
        val loop = SyncLoop(rig.engine, rig.jobs, broken, rig.clock, RetryPolicy(), rig.log)
        loop.start(backgroundScope)
        runCurrent()

        val key = rig.publish()
        runCurrent()

        assertEquals(SyncState.STORED, rig.job(key).state)
        assertTrue(rig.log.lines.any { it.startsWith("loop.network.failed IllegalStateException") })
    }

    @Test
    fun `任务表的变化信号出了故障：记一条日志，网络触发与手动触发照常工作`() = runTest {
        val rig = SyncRig(this)
        val network = FakeNetwork()
        val key = rig.publish()
        val brokenJobs = object : app.auri.sync.engine.SyncJobStore by rig.jobs {
            override fun observe(): Flow<List<app.auri.sync.engine.SyncJob>> = flow { throw IllegalStateException("数据库打不开") }
        }
        val loop = SyncLoop(rig.engine, brokenJobs, network, rig.clock, RetryPolicy(), rig.log)
        loop.start(backgroundScope)
        runCurrent()

        assertEquals("启动时那一轮不依赖变化信号", SyncState.STORED, rig.job(key).state)
        assertTrue(rig.log.lines.any { it.startsWith("loop.jobs.failed IllegalStateException") })
    }

    // ---- F4：观察者的故障是暂时的，不能让它永远失聪 ----

    private fun SyncJobStore.failingFirstSubscriptions(count: Int, cause: () -> Exception = { IllegalStateException("database is locked") }): SyncJobStore {
        val remaining = AtomicInteger(count)
        val real = this
        return object : SyncJobStore by real {
            override fun observe(): Flow<List<SyncJob>> = if (remaining.getAndDecrement() > 0) flow { throw cause() } else real.observe()
        }
    }

    @Test
    fun `任务表的变化信号暂时出了故障（比如数据库被锁）：按退避重新订阅，恢复之后新任务照样自动发出去`() = runTest {
        val rig = SyncRig(this)
        val loop = SyncLoop(rig.engine, rig.jobs.failingFirstSubscriptions(2), FakeNetwork(), rig.clock, RetryPolicy(), rig.log)
        loop.start(backgroundScope)
        runCurrent()
        assertEquals("第一次订阅就失败了", 1, rig.log.lines.count { it.startsWith("loop.jobs.failed IllegalStateException") })

        after(5_000) // 第一次重新订阅：又失败
        after(10_000) // 第二次重新订阅：成功
        assertEquals("恰好失败了两次，没有密集重试", 2, rig.log.lines.count { it.startsWith("loop.jobs.failed IllegalStateException") })

        val key = rig.publish()
        runCurrent()

        assertEquals("观察者以前失败一次就永远结束，新任务没人发现", SyncState.STORED, rig.job(key).state)
    }

    @Test
    fun `任务表的变化信号一直出故障：重新订阅的间隔 5、10、20 秒……封顶在退避上限，一小时内只有十来次，不会密集空转`() = runTest {
        val rig = SyncRig(this)
        val loop = SyncLoop(rig.engine, rig.jobs.failingFirstSubscriptions(Int.MAX_VALUE), FakeNetwork(), rig.clock, RetryPolicy(), rig.log)
        loop.start(backgroundScope)
        runCurrent()

        after(60 * 60_000L)

        val failures = rig.log.lines.count { it.startsWith("loop.jobs.failed IllegalStateException") }
        assertTrue("一小时里失败了 $failures 次：应当在 8 到 14 之间（5+10+…+640 秒之后每次 15 分钟）", failures in 8..14)
    }

    @Test
    fun `上一次订阅活了一阵（一分钟以上）才出故障：重新订阅的退避从头算，不是接着上一次的往上翻`() = runTest {
        val rig = SyncRig(this)
        val subscribedAt = java.util.concurrent.CopyOnWriteArrayList<java.time.Instant>()
        val real = rig.jobs
        val shaped = object : SyncJobStore by real {
            override fun observe(): Flow<List<SyncJob>> = when (subscribedAt.size.also { subscribedAt += rig.clock.now() }) {
                0 -> flow { throw IllegalStateException("database is locked") } // 立刻失败
                1 -> flow {
                    kotlinx.coroutines.delay(120_000) // 活了两分钟才失败
                    throw IllegalStateException("database is locked")
                }
                else -> real.observe()
            }
        }
        val loop = SyncLoop(rig.engine, shaped, FakeNetwork(), rig.clock, RetryPolicy(), rig.log)
        loop.start(backgroundScope)
        runCurrent()

        after(5_000 + 120_000 + 5_000 + 1)

        assertEquals("共订阅了三次", 3, subscribedAt.size)
        assertEquals("第二次订阅活了两分钟才失败：下一次在 5 秒之后，不是 10 秒", Duration.ofMillis(120_000 + 5_000), Duration.between(subscribedAt[1], subscribedAt[2]))
    }

    @Test
    fun `网络信号暂时出了故障：按退避重新订阅，恢复之后网络回来的事件照样触发补传`() = runTest {
        val rig = SyncRig(this, gatewayOnLan = false)
        val subscriptions = AtomicInteger()
        val network = FakeNetwork()
        val flaky = object : NetworkSignal {
            override val available: Flow<Boolean> = flow {
                if (subscriptions.incrementAndGet() == 1) throw IllegalStateException("系统服务暂时不可用")
                network.available.collect { emit(it) }
            }
        }
        val key = rig.publish()
        val loop = SyncLoop(rig.engine, rig.jobs, flaky, rig.clock, RetryPolicy(), rig.log)
        loop.start(backgroundScope)
        runCurrent()
        assertEquals(SyncState.WAITING_GATEWAY, rig.job(key).state)

        after(5_000) // 重新订阅成功
        rig.bringGatewayUp()
        network.switchToAnotherNetwork()
        runCurrent()

        assertEquals("网络观察者恢复了：换网络的事件到了就补传", SyncState.STORED, rig.job(key).state)
        assertEquals(2, subscriptions.get())
    }

    @Test
    fun `同一个 Record 的新 Revision 是新任务：也会被发出去，两个版本各发一次`() = loopRig { rig, _, loop ->
        loop.start(backgroundScope)
        runCurrent()
        val first = rig.publish(SyncSamples.RECORD_ID, 1)
        runCurrent()
        val second = rig.publish(SyncSamples.RECORD_ID, 2, text = "改过之后的文字")
        runCurrent()

        assertEquals(listOf(JobKey(SyncSamples.RECORD_ID, 1), JobKey(SyncSamples.RECORD_ID, 2)), listOf(first, second))
        assertEquals(listOf(SyncState.STORED, SyncState.STORED), listOf(first, second).map { rig.job(it).state })
    }
}
