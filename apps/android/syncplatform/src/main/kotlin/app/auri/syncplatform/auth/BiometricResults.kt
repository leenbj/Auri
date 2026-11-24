package app.auri.syncplatform.auth

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.UnavailableReason

/**
 * 系统生物识别给的错误码 → 本应用的核验结果。纯函数（常量在编译时就内联了，JVM 单测不需要设备）。
 * 规则只有一条要紧：**除了系统明说通过（onAuthenticationSucceeded），任何码都不是 [AuthResult.Authenticated]**——未知的码按“没通过”处理。
 */
object BiometricResults {
    /** [BiometricManager.canAuthenticate] 的返回值：可以弹提示就返回 null，否则是为什么不行。 */
    fun availability(code: Int): AuthResult? = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> AuthResult.Unavailable(UnavailableReason.NO_HARDWARE)
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED)
        else -> AuthResult.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE) // 硬件暂时不可用、需要安全更新、没见过的码：这次不能核验
    }

    /** 提示进行中系统回报的错误（[BiometricPrompt.AuthenticationCallback.onAuthenticationError]）。 */
    fun fromError(code: Int): AuthResult = when (code) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
        -> AuthResult.Cancelled

        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT -> AuthResult.Unavailable(UnavailableReason.NO_HARDWARE)

        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
        BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
        -> AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED)

        BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE -> AuthResult.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE)

        // 试错次数太多被锁定、超时、无法处理、没见过的码：都是“没通过”
        else -> AuthResult.Denied
    }
}
