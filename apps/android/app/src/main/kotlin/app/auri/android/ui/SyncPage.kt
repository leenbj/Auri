package app.auri.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auri.android.sync.PairingProgress
import app.auri.android.sync.SyncViewModel
import app.auri.syncplatform.scan.QrScannerView

/**
 * 同步页接线：相机权限、取景视图、视图模型。屏幕上的字在 [SyncScreen]；这里只管“什么时候开摄像头”。
 * 摄像头只在用户点了“扫码配对”并且授权之后才开，读到第一张合规二维码（开始配对）或用户点“停止扫码”就关；页面离开时 [QrScannerView] 自己也会关。
 */
@Composable
fun SyncPage(viewModel: SyncViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scanning by rememberSaveable { mutableStateOf(false) }
    var cameraUnavailable by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraUnavailable = !granted
        scanning = granted
    }
    // 开始配对就不再取景：同一张码在路上还有几帧，视图模型会忽略；配对失败后用户要重试再点一次
    LaunchedEffect(state.pairing) { if (state.pairing == PairingProgress.Pairing) scanning = false }

    SyncScreen(
        state = state,
        scanning = scanning,
        onBack = onBack,
        onStartScan = {
            cameraUnavailable = false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
            else permission.launch(Manifest.permission.CAMERA)
        },
        onStopScan = { scanning = false },
        onDismissProblem = viewModel::dismissPairingProblem,
        onSyncNow = viewModel::syncNow,
        cameraUnavailable = cameraUnavailable,
        manualEntry = { ManualPairingEntry(onSubmit = viewModel::onQrText, enabled = state.pairing != PairingProgress.Pairing) },
        scanner = {
            val owner = LocalLifecycleOwner.current
            AndroidView(
                factory = { QrScannerView(it).also { view -> view.start(owner, onText = viewModel::onQrText, onUnavailable = { cameraUnavailable = true; scanning = false }) } },
                onRelease = { it.stop() },
                modifier = Modifier.fillMaxWidth().height(320.dp),
            )
        },
    )
}
