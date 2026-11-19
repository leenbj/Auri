package app.auri.sync.engine

/**
 * 退避时间：第 n 次失败后等 base × 2^(n-1)，封顶 [maxMs]。上限有界，所以网关长时间不在也不会越等越久到“再也不试”。
 * 只用于“可重试”的失败；永久性拒绝不重试（状态机里它们进 BLOCKED，只有用户能放行）。
 */
class RetryPolicy(val baseMs: Long = 5_000, val maxMs: Long = 15 * 60_000) {
    init {
        require(baseMs > 0 && maxMs >= baseMs) { "退避参数不合法" }
    }

    fun delayMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt 从 1 开始" }
        var delay = baseMs
        repeat(attempt - 1) {
            if (delay >= maxMs) return maxMs
            delay *= 2
        }
        return minOf(delay, maxMs)
    }
}
