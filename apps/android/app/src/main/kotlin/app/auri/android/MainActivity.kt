package app.auri.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auri.android.control.ControlViewModel
import app.auri.android.home.HomeViewModel
import app.auri.android.ui.AppRoot
import app.auri.android.ui.ControlScreen
import app.auri.android.ui.HomeScreen

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as AuriApplication).runtime

    private val viewModel: HomeViewModel by viewModels { runtime.homeViewModelFactory }
    private val controlViewModel: ControlViewModel by viewModels { runtime.controlViewModelFactory }

    // 不管用户给不给权限都继续开始：协调器会按实际情况拒绝并给出原因（PERMISSION_MIC_DENIED 等），界面如实显示
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val controlState by controlViewModel.state.collectAsStateWithLifecycle()
            AppRoot(
                home = { HomeScreen(state, onStart = ::onStartPressed, onStop = viewModel::stop, onDismissNotice = viewModel::dismissNotice) },
                control = { ControlScreen(controlState, controlViewModel, onStart = ::onStartPressed) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.pipeline.resume() // 模型装好之后回到应用，等模型的 Record 就会被转录
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
