package app.auri.android

import app.auri.android.netguard.SourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 H4 的接线：行为在 ReadFailureContainmentTest 里用真的视图模型摆过；这里钉住只能在设备上跑、JVM 里碰不到的那一处——
 * AuriRuntime 造出来的每一个作用域（运行时自己的、每个视图模型的、剪贴板守卫的）都带着异常处理器。没有它，哪个读 SQLite 的协程抛了
 * 没人接的异常，就会走到线程的默认处理器，在 Android 上整个进程退出——包括正在录音的前台服务。做法同 ClipboardWiringTest：读生产源码。
 */
class RuntimeScopeGuardTest {
    private val runtimeFile = File("..").resolve("app/src/main/kotlin/app/auri/android/AuriRuntime.kt")
    private val raw = runtimeFile.readText()
    private val code = SourceText.withoutCommentsAndStrings(raw)

    @Test
    fun `运行时里只有一处直接造作用域（scopeOf）：它带着 SupervisorJob 和异常处理器，别处都经它`() {
        assertEquals("别处不许再直接 CoroutineScope(...)：漏掉处理器的作用域一出事就是进程退出", 1, Regex("""\bCoroutineScope\(""").findAll(code).count())
        assertTrue("scopeOf 里要有 SupervisorJob（一个子协程失败不连累别的）和异常处理器", code.contains("CoroutineScope(SupervisorJob() + dispatcher + escapedFailureHandler)"))
        assertTrue("运行时自己的作用域也经它", Regex("""\bval scope = scopeOf\(\)""").containsMatchIn(code))
        assertEquals("运行时自己的一个、六个视图模型、剪贴板守卫，共八处都经它", 8, Regex("""\bscopeOf\(""").findAll(code.substringBefore("companion object")).count())
    }

    @Test
    fun `逃出来的异常只记事件名与异常类名：不记异常信息（里面可能有路径与文字）、不打印堆栈`() {
        val handler = raw.lineSequence().single { it.contains("CoroutineExceptionHandler {") }

        assertTrue(handler, handler.contains("AndroidSyncLog.event(\"scope.escaped \${failure::class.simpleName}\")"))
        for (leak in listOf(".message", "toString()", "printStackTrace", "Log.e(", "localizedMessage", "cause")) assertFalse("处理器不许碰“$leak”：$handler", handler.contains(leak))
    }
}
