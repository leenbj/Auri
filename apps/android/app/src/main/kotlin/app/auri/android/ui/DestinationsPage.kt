package app.auri.android.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auri.android.destinations.DestinationDetailViewModel
import app.auri.android.destinations.DestinationsViewModel
import app.auri.android.destinations.SendReviewViewModel

private const val LIST = "list"
private const val REVIEW = "review"
private const val DETAIL = "detail:"

/**
 * “发送”页的接线：列表与创建 / 待发送与标签 / 某个目的地的详情，三个界面之间的切换。
 * 保存的状态只有“现在在哪一页”（一个字符串）——凭证、正文、名字都不进保存的状态，所以进程被杀后重新进来，凭证面板一定是空的。
 * 离开详情页（返回、切走、被销毁）时 [DestinationDetailViewModel.leave] 抹掉凭证。
 */
@Composable
fun DestinationsPage(
    destinations: DestinationsViewModel,
    review: SendReviewViewModel,
    detail: (String) -> DestinationDetailViewModel,
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
            val context = LocalContext.current
            DisposableEffect(viewModel) { onDispose { viewModel.leave() } }
            LaunchedEffect(viewModel) { viewModel.refreshStatuses() }
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
                    copySecretToClipboard(context, credential.reveal())
                    viewModel.credentialCopied()
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
