package app.auri.syncplatform.auth

import android.hardware.biometrics.BiometricManager
import app.auri.sync.SyncLog
import app.auri.sync.destinations.AuthPurpose
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.LocalAuthenticator

/** 向系统要的一次核验：为什么要（界面据此写提示）和接受哪一类核验方式（[android.hardware.biometrics.BiometricManager.Authenticators] 的位）。 */
data class PromptRequest(val purpose: AuthPurpose, val allowedAuthenticators: Int)

/**
 * 系统生物识别框架里本应用用到的两个动作。真机上是 [AndroidBiometricFramework]；JVM 测试里换成假的——
 * 这样才能检验最要紧的事：本应用向系统要的是**哪一种**核验（见 [BiometricGate.REQUIRED]）。
 */
interface BiometricFramework {
    /** [android.hardware.biometrics.BiometricManager.canAuthenticate] 的返回码；设备没有这个系统服务时按“没有硬件”。 */
    fun canAuthenticate(allowedAuthenticators: Int): Int

    /** 弹出系统提示并等结果（通过、取消、各种错误）。 */
    suspend fun prompt(request: PromptRequest): AuthResult
}

/**
 * 生成或轮换 Destination 凭证前的本机核验的**策略**：只认强生物识别（[REQUIRED]）；问系统“现在能不能做这种核验”，能才弹提示；每次都现场问，不缓存。
 * 不能就原样返回原因（[AuthResult.Unavailable]）：调用方不会生成凭证。日志只写结果的种类。
 */
class BiometricGate(
    private val framework: BiometricFramework,
    private val log: SyncLog = SyncLog.None,
) : LocalAuthenticator {
    override suspend fun authenticate(purpose: AuthPurpose): AuthResult {
        BiometricResults.availability(framework.canAuthenticate(REQUIRED))?.let { return it.also(::record) }
        return framework.prompt(PromptRequest(purpose, REQUIRED)).also(::record)
    }

    private fun record(result: AuthResult) = log.event("auth.${result::class.simpleName}")

    companion object {
        /**
         * 只认强生物识别（指纹、人脸）。**不含 DEVICE_CREDENTIAL**（锁屏 PIN/图案/密码）：拿到手机又知道 PIN 的人不一定是用户本人，
         * 而凭证一旦拿到手，Agent 就能读到这个目的地的文字（评审 B4）。没有登记生物识别就不可用，不降级。
         */
        const val REQUIRED = BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}
