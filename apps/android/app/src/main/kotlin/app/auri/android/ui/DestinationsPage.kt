package app.auri.android.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auri.android.destinations.DestinationDetailViewModel
import app.auri.android.destinations.DestinationsViewModel
import app.auri.android.destinations.SecretClipboardGuard
import app.auri.android.destinations.SendReviewViewModel
import app.auri.android.destinations.describeClipboardNotice

private const val LIST = "list"
private const val REVIEW = "review"
private const val DETAIL = "detail:"

/**
 * “发送”页的接线：列表与创建 / 待发送与标签 / 某个目的地的详情，三个界面之间的切换。
 * 保存的状态只有“现在在哪一页”（一个字符串）——凭证、正文、名字都不进保存的状态，所以进程被杀后重新进来，凭证面板一定是空的。
 * 离开详情页（返回、切走、被销毁）时 [DestinationDetailViewModel.leave] 抹掉凭证；应用退到后台（ON_STOP）时 [DestinationDetailViewModel.backgrounded] 抹掉正在显示的凭证、并让还在路上的请求作废（之后到的凭证不显示）。
 * 复制出去的凭证由 [SecretClipboardGuard] 清出剪贴板（复评 R6）：只在读得到、认得出是自己放的那一份时才清——离开详情页、应用即将失去焦点、前台里满 60 秒；
 * 读不到（已在后台）绝不盲清。复制之后用一句话告诉用户这些（[describeClipboardNotice]）。
 */
@Composable
fun DestinationsPage(
    destinations: DestinationsViewModel,
    review: SendReviewViewModel,
    detail: (String) -> DestinationDetailViewModel,
    clipboard: SecretClipboardGuard,
) {
    var route by rememberSaveable { mutableStateOf(LIST) }
    when {
        route == REVIEW -> {
            BackHandler { route = LIST }
            val state by review.state.collectAsStateWithLifecycle()
            SendReviewScreen(
                state = state,
                onBack = { route = LIST },
                onConfirm = review::confirm,
                onSkip = review::skip,
                onSetIntent = review::setIntent,
                onSetDefaultIntent = review::setDefaultIntent,
            )
        }

        route.startsWith(DETAIL) -> {
            val id = route.removePrefix(DETAIL)
            val viewModel = remember(id) { detail(id) }
            val owner = LocalLifecycleOwner.current
            val context = LocalContext.current
            DisposableEffect(viewModel, owner) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.backgrounded() }
                owner.lifecycle.addObserver(observer)
                onDispose {
                    owner.lifecycle.removeObserver(observer)
                    viewModel.leave()
                    clipboard.onLeaving() // 离开详情页：剪贴板里还是自己放的凭证就清（读不到、或是别人的不动）
                }
            }
            LaunchedEffect(viewModel) { viewModel.refreshStatusesAutomatically() }
            BackHandler { route = LIST }
            val state by viewModel.state.collectAsStateWithLifecycle()
            DestinationDetailScreen(
                state = state,
                onBack = { route = LIST },
                onRefresh = viewModel::refreshStatuses,
                onRequestCredential = viewModel::requestCredential,
                onConfirmRotate = viewModel::confirmRotate,
                onCancelRotate = viewModel::cancelRotate,
                onCopyCredential = { credential ->
                    clipboard.copy(credential.reveal())
                    viewModel.credentialCopied()
                    Toast.makeText(context, describeClipboardNotice(), Toast.LENGTH_LONG).show() // 面板已经抹掉了，复制之后的下落只能这样告诉用户
                },
                onDismissCredential = viewModel::dismissCredential,
                onRequestRevoke = viewModel::requestRevoke,
                onConfirmRevoke = viewModel::confirmRevoke,
                onCancelRevoke = viewModel::cancelRevoke,
                onDismissProblem = viewModel::dismissProblem,
            )
        }

        else -> {
            val state by destinations.state.collectAsStateWithLifecycle()
            DestinationsScreen(
                state = state,
                onSetName = destinations::setName,
                onChooseSendMode = destinations::chooseSendMode,
                onSetAllowActionRequests = destinations::setAllowActionRequests,
                onSubmit = destinations::submit,
                onRefresh = destinations::refresh,
                onOpenDetail = { route = DETAIL + it },
                onOpenReview = { route = REVIEW },
                onDismissProblem = destinations::dismissProblem,
            )
        }
    }
}
