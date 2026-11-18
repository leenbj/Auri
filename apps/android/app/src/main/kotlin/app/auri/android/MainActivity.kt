package app.auri.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auri.android.home.HomeViewModel
import app.auri.android.sync.SyncViewModel
import app.auri.android.ui.HomeScreen
import app.auri.android.ui.SyncPage

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as AuriApplication).runtime

    private val viewModel: HomeViewModel by viewModels { runtime.homeViewModelFactory }
    private val syncViewModel: SyncViewModel by viewModels { runtime.syncViewModelFactory }

    // 不管用户给不给权限都继续开始：协调器会按实际情况拒绝并给出原因（PERMISSION_MIC_DENIED 等），界面如实显示
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            var onSyncPage by rememberSaveable { mutableStateOf(false) }
            if (onSyncPage) {
                BackHandler { onSyncPage = false }
                SyncPage(syncViewModel, onBack = { onSyncPage = false })
            } else {
                HomeScreen(state, onStart = ::onStartPressed, onStop = viewModel::stop, onDismissNotice = viewModel::dismissNotice, onOpenSync = { onSyncPage = true })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.pipeline.resume() // 模型装好之后回到应用，等模型的 Record 就会被转录
        runtime.sync.syncNow() // 回到前台：立刻同步一轮（没配对时什么也不会发生）
    }

    override fun onResume() {
        super.onResume()
        runtime.captureAdapter.permissionsMayHaveChanged()
    }

    private fun onStartPressed() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) viewModel.start() else permissions.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
