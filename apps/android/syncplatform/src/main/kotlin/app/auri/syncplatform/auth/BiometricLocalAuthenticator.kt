package app.auri.syncplatform.auth

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import app.auri.sync.SyncLog
import app.auri.sync.destinations.AuthPurpose
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.LocalAuthenticator
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 生成或轮换 Destination 凭证前的本机核验（Android 实现）：策略在 [BiometricGate]，这里只是把它接到系统框架上。
 * 用系统框架自带的 [BiometricPrompt]（minSdk 31 起可用），不加任何依赖。**每次都现场核验，不缓存结果**——
 * 上一次通过不代表这一次是本人（凭证一旦泄露，Agent 就能读到这个 Destination 的文字）。
 *
 * 日志只写结果的种类，没有别的（不涉及生物特征、不涉及凭证）。
 * 这里的结果只是“用户在场”的证明，不是密钥：它不参与凭证本身的生成（凭证由网关签发）。
 *
 * 设备上的行为（真的弹出提示、取消、未录入、锁定）见 execution/acceptance/android-destinations/README.md 的真机步骤；本机没有设备，NOT_RUN。
 */
class BiometricLocalAuthenticator(
    context: Context,
    log: SyncLog = SyncLog.None,
) : LocalAuthenticator by BiometricGate(AndroidBiometricFramework(context), log)

/** 系统框架那一侧：[BiometricManager] 与 [BiometricPrompt]。没有可单测的逻辑（逻辑都在 [BiometricGate] 与 [BiometricResults]），真机上才验证。 */
internal class AndroidBiometricFramework(private val context: Context) : BiometricFramework {
    override fun canAuthenticate(allowedAuthenticators: Int): Int {
        val manager = context.getSystemService(BiometricManager::class.java) ?: return BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        return manager.canAuthenticate(allowedAuthenticators)
    }

    override suspend fun prompt(request: PromptRequest): AuthResult = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(AuthResult.Authenticated)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (continuation.isActive) continuation.resume(BiometricResults.fromError(errorCode))
            }

            // onAuthenticationFailed（这一次没认出来）不是终点：系统提示还开着，用户可以再试，或取消
        }
        val rotating = request.purpose == AuthPurpose.ROTATE_CREDENTIAL
        val builder = BiometricPrompt.Builder(context)
            .setTitle(if (rotating) "轮换凭证" else "生成凭证")
            .setSubtitle("确认是你本人")
            .setDescription(
                if (rotating) "轮换后旧凭证会立刻失效，Agent 需要换成新的。" else "凭证只显示这一次。拿到凭证的 Agent 可以读取这个目的地的文字。",
            )
            .setAllowedAuthenticators(request.allowedAuthenticators)
            // 不用设备凭据作后备（评审 B4），系统就要求自己给“取消”按钮；它的回调不走 onAuthenticationError，这里按取消交回
            .setNegativeButton("取消", context.mainExecutor) { _, _ -> if (continuation.isActive) continuation.resume(AuthResult.Cancelled) }
            .setConfirmationRequired(true)
        builder.build().authenticate(signal, context.mainExecutor, callback)
    }
}
