package app.auri.syncplatform

import android.util.Log
import app.auri.sync.SyncLog

/**
 * 同步与配对的日志出口（`adb logcat -s AuriSync:I`）。SyncLog 的合同是只写事件名、枚举与数字：
 * 没有正文、令牌、配对码、公钥指纹、主机名或地址；这里原样输出，不加任何东西。
 */
object AndroidSyncLog : SyncLog {
    const val TAG = "AuriSync"

    override fun event(line: String) {
        Log.i(TAG, line)
    }
}
