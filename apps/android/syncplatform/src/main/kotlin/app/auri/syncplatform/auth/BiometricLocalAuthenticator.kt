package app.auri.syncplatform.auth

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import app.auri.sync.SyncLog
import app.auri.sync.destinations.AuthPurpose
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.LocalAuthenticator
import app.auri.sync.destinations.UnavailableReason
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 生成或轮换 Destination 凭证前的本机核验：系统的生物识别提示，只认强生物识别或设备锁屏凭据（PIN/图案/密码）。
 * 用系统框架自带的 [BiometricPrompt]（minSdk 31 起可用），不加任何依赖。**每次都现场核验，不缓存结果**——
 * 上一次通过不代表这一次是本人（凭证一旦泄露，Agent 就能读到这个 Destination 的文字）。
 *
 * 没有任何生物识别也没有设置锁屏时，系统说“没有可用的凭据”，这里就返回 [AuthResult.Unavailable]：调用方不会生成凭证。
 * 日志只写结果的种类，没有别的（不涉及生物特征、不涉及凭证）。
 * 这里的结果只是“用户在场”的证明，不是密钥：它不参与凭证本身的生成（凭证由网关签发）。
 *
 * 设备上的行为（真的弹出提示、取消、未录入、锁定）见 execution/acceptance/android-destinations/README.md 的真机步骤；本机没有设备，NOT_RUN。
 */
class BiometricLocalAuthenticator(
    private val context: Context,
    private val log: SyncLog = SyncLog.None,
) : LocalAuthenticator {
    override suspend fun authenticate(purpose: AuthPurpose): AuthResult {
        val manager = context.getSystemService(BiometricManager::class.java)
            ?: return AuthResult.Unavailable(UnavailableReason.NO_HARDWARE).also(::record)
        BiometricResults.availability(manager.canAuthenticate(ALLOWED))?.let { return it.also(::record) }
        return prompt(purpose).also(::record)
    }

    private suspend fun prompt(purpose: AuthPurpose): AuthResult = suspendCancellableCoroutine { continuation ->
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
        val builder = BiometricPrompt.Builder(context)
            .setTitle(if (purpose == AuthPurpose.ROTATE_CREDENTIAL) "轮换凭证" else "生成凭证")
            .setSubtitle("确认是你本人")
            .setDescription(
                if (purpose == AuthPurpose.ROTATE_CREDENTIAL) "轮换后旧凭证会立刻失效，Agent 需要换成新的。" else "凭证只显示这一次。拿到凭证的 Agent 可以读取这个目的地的文字。",
            )
            .setAllowedAuthenticators(ALLOWED) // 用设备凭据作后备时不能再设“取消”按钮：系统自带返回
            .setConfirmationRequired(true)
        builder.build().authenticate(signal, context.mainExecutor, callback)
    }

    private fun record(result: AuthResult) = log.event("auth.${result::class.simpleName}")

    private companion object {
        const val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
