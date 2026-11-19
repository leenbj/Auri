package app.auri.syncplatform

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** 以 shell 身份执行命令（UiAutomation）并返回标准输出：切换飞行模式、读 logcat 用。 */
internal fun shell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
}
