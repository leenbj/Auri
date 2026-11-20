@file:OptIn(ExperimentalCoroutinesApi::class)

package app.auri.android.destinations

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * 让视图模型的后台协程把手头的活干完。视图模型的收集器都挂在 backgroundScope 上，而 advanceUntilIdle 在只剩后台任务时立刻返回、一个都不跑：
 * 所以先 runCurrent（此刻能跑的全跑完），再让虚拟时间走过详情页刷新之间的间隔（500 毫秒），再 runCurrent。
 */
internal fun TestScope.settle() {
    runCurrent()
    advanceTimeBy(1_100)
    runCurrent()
}
