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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auri.android.control.ControlViewModel
import app.auri.android.destinations.DestinationDetailViewModel
import app.auri.android.destinations.DestinationsViewModel
import app.auri.android.destinations.SendReviewViewModel
import app.auri.android.home.HomeViewModel
import app.auri.android.modelui.ModelsViewModel
import app.auri.android.revisions.RecordDetailViewModel
import app.auri.android.revisions.RecordsViewModel
import app.auri.android.sync.SyncViewModel
import app.auri.android.ui.AppRoot
import app.auri.android.ui.ControlScreen
import app.auri.android.ui.DestinationsPage
import app.auri.android.ui.HomeScreen
import app.auri.android.ui.ModelsScreen
import app.auri.android.ui.RecordsPage
import app.auri.android.ui.SyncPage
import app.auri.platform.system.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val runtime get() = (application as AuriApplication).runtime

    private val viewModel: HomeViewModel by viewModels { runtime.homeViewModelFactory }
    private val controlViewModel: ControlViewModel by viewModels { runtime.controlViewModelFactory }
    private val syncViewModel: SyncViewModel by viewModels { runtime.syncViewModelFactory }
    private val modelsViewModel: ModelsViewModel by viewModels { runtime.modelsViewModelFactory }
    private val destinationsViewModel: DestinationsViewModel by viewModels { runtime.destinationsViewModelFactory }
    private val sendReviewViewModel: SendReviewViewModel by viewModels { runtime.sendReviewViewModelFactory }
    private val recordsViewModel: RecordsViewModel by viewModels { runtime.recordsViewModelFactory }

    /** 每个目的地一个详情视图模型，以编号为键（同一个目的地回到详情页拿到的是同一个）。 */
    private fun destinationDetail(id: String): DestinationDetailViewModel =
        ViewModelProvider(this, runtime.destinationDetailViewModelFactory(id))["destination-$id", DestinationDetailViewModel::class.java]

    private fun recordDetail(id: String): RecordDetailViewModel =
        ViewModelProvider(this, runtime.recordDetailViewModelFactory(id))["record-$id", RecordDetailViewModel::class.java]

    // 不管用户给不给权限都继续开始：协调器会按实际情况拒绝并给出原因（PERMISSION_MIC_DENIED 等），界面如实显示
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val controlState by controlViewModel.state.collectAsStateWithLifecycle()
            val models by modelsViewModel.state.collectAsStateWithLifecycle()
            var onSyncPage by rememberSaveable { mutableStateOf(false) }
            if (onSyncPage) {
                // 配对与同步（Ticket 21）占满整个屏幕，返回键回到原来的页
                BackHandler { onSyncPage = false }
                SyncPage(syncViewModel, onBack = { onSyncPage = false })
            } else {
                // 录音 / 耳机控制（Ticket 17）/ 模型与转录（Ticket 20）/ 发送（Ticket 22）四页；“同步与配对”入口在录音页里
                AppRoot(
                    home = {
                        HomeScreen(
                            state,
                            onStart = ::onStartPressed,
                            onStop = viewModel::stop,
                            onDismissNotice = viewModel::dismissNotice,
                            onSetDuration = viewModel::setDurationLimit,
                            onOpenSync = { onSyncPage = true },
                        )
                    },
                    control = { ControlScreen(controlState, controlViewModel, onStart = ::onStartPressed) },
                    models = {
                        ModelsScreen(
                            models,
                            onStart = modelsViewModel::start,
                            onPause = modelsViewModel::pause,
                            onCancelDownload = modelsViewModel::cancelDownload,
                            onOpenLicense = ::openLicense,
                            onCancelJob = modelsViewModel::cancelJob,
                            onRetranscribe = modelsViewModel::retranscribe,
                            onRecheck = modelsViewModel::recheckResources,
                        )
                    },
                    destinations = { DestinationsPage(destinationsViewModel, sendReviewViewModel, ::destinationDetail, runtime.secretClipboard) },
                    records = { RecordsPage(recordsViewModel, ::recordDetail) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.pipeline.resume() // 模型装好之后回到应用，等模型的 Record 就会被转录
        runtime.sync.syncNow() // 回到前台：立刻同步一轮（没配对时什么也不会发生）
        // OCR #22：长生命周期进程回到前台时也按保留期清理到期音频
        runtime.scope.launch(Dispatchers.IO) {
            runtime.audioRetentionCleaner.purgeExpired(SystemClock.now(), runtime.audioRetentionSetting.days.value)
        }
    }

    override fun onResume() {
        super.onResume()
        runtime.captureAdapter.permissionsMayHaveChanged()
    }

    override fun onPause() {
        // 离开应用之前最后一个还读得到剪贴板的时刻（Android 10 起失去焦点就读不了）：自己放的凭证此刻清掉；读不到、或是别人的，守卫不动（复评 R6）
        runtime.secretClipboard.onLeaving()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 拿到焦点：Android 10 起只有拿到焦点的应用读得了剪贴板（onStart / onResume 时窗口还没有焦点，读到的是空），复制出去的凭证已到期就清掉。
        // 失去焦点：分屏里点了另一个窗口、系统对话框、下拉通知栏都不走 onPause，凭证不能就这么留着——按“离开”处理：刚失焦的这一刻通常还读得到，
        // 认得出是自己放的就清；读不到、或是别人的，守卫不动（再评 S10）
        if (hasFocus) runtime.secretClipboard.onForeground() else runtime.secretClipboard.onLeaving()
    }

    private fun onStartPressed() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) viewModel.start() else permissions.launch(missing.toTypedArray())
    }

    /** 交给系统浏览器看许可全文；本应用自己不联网取它（见 [BrowserLink]）。 */
    private fun openLicense(url: String) = BrowserLink(this).open(url)

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
