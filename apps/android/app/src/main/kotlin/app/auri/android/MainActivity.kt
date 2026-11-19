package app.auri.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auri.android.home.HomeViewModel
import app.auri.android.modelui.ModelsViewModel
import app.auri.android.sync.SyncViewModel
import app.auri.android.ui.HomeScreen
import app.auri.android.ui.ModelsScreen
import app.auri.android.ui.SyncPage

private enum class Page { HOME, MODELS }

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as AuriApplication).runtime

    private val viewModel: HomeViewModel by viewModels { runtime.homeViewModelFactory }
    private val syncViewModel: SyncViewModel by viewModels { runtime.syncViewModelFactory }
    private val modelsViewModel: ModelsViewModel by viewModels { runtime.modelsViewModelFactory }

    // 不管用户给不给权限都继续开始：协调器会按实际情况拒绝并给出原因（PERMISSION_MIC_DENIED 等），界面如实显示
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val models by modelsViewModel.state.collectAsStateWithLifecycle()
            var onSyncPage by rememberSaveable { mutableStateOf(false) }
            var page by rememberSaveable { mutableStateOf(Page.HOME) }
            if (onSyncPage) {
                // 配对与同步（Ticket 21）占满整个屏幕，返回键回到原来的页
                BackHandler { onSyncPage = false }
                SyncPage(syncViewModel, onBack = { onSyncPage = false })
            } else {
                // 录音 / 模型与转录两页（Ticket 20）；“同步与配对”入口在录音页里
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { page = Page.HOME }, enabled = page != Page.HOME) { Text("录音") }
                        Button(onClick = { page = Page.MODELS }, enabled = page != Page.MODELS) { Text("模型与转录") }
                    }
                    Box(Modifier.weight(1f)) {
                        when (page) {
                            Page.HOME -> HomeScreen(
                                state,
                                onStart = ::onStartPressed,
                                onStop = viewModel::stop,
                                onDismissNotice = viewModel::dismissNotice,
                                onOpenSync = { onSyncPage = true },
                            )
                            Page.MODELS -> ModelsScreen(
                                models,
                                onStart = modelsViewModel::start,
                                onPause = modelsViewModel::pause,
                                onCancelDownload = modelsViewModel::cancelDownload,
                                onOpenLicense = ::openLicense,
                                onCancelJob = modelsViewModel::cancelJob,
                                onRetranscribe = modelsViewModel::retranscribe,
                                onRecheck = modelsViewModel::recheckResources,
                            )
                        }
                    }
                }
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

    /** 交给系统浏览器看许可全文；本应用自己不联网取它。没有浏览器时打不开，地址仍显示在页面上，可以抄下来。 */
    private fun openLicense(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // 见上
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
