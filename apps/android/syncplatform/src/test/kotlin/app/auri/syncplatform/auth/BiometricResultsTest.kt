package app.auri.syncplatform.auth

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 系统给的错误码 → 核验结果。要紧的只有一条：除了系统明说通过，任何码都不能变成“已通过”——
 * 通过之外的结果都会让凭证根本不生成（DestinationService：核验没过则零请求）。
 */
class BiometricResultsTest {
    @Test
    fun `只有系统说可以弹提示（BIOMETRIC_SUCCESS）时才往下走，其余每个码都是不可用`() {
        assertNull(BiometricResults.availability(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(AuthResult.Unavailable(UnavailableReason.NO_HARDWARE), BiometricResults.availability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED), BiometricResults.availability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(AuthResult.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE), BiometricResults.availability(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        for (code in -20..40) {
            if (code == BiometricManager.BIOMETRIC_SUCCESS) continue
            assertNotEquals("码 $code 不能让流程往下走", null, BiometricResults.availability(code))
        }
    }

    @Test
    fun `用户取消与系统取消是“取消”：不是失败，也不是通过`() {
        assertEquals(AuthResult.Cancelled, BiometricResults.fromError(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED))
        assertEquals(AuthResult.Cancelled, BiometricResults.fromError(BiometricPrompt.BIOMETRIC_ERROR_CANCELED))
    }

    @Test
    fun `没有硬件、没有录入、暂时不可用各自对应不可用的原因，用户看到的话不同`() {
        assertEquals(AuthResult.Unavailable(UnavailableReason.NO_HARDWARE), BiometricResults.fromError(BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT))
        assertEquals(AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED), BiometricResults.fromError(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS))
        assertEquals(AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED), BiometricResults.fromError(BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL))
        assertEquals(AuthResult.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE), BiometricResults.fromError(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE))
    }

    @Test
    fun `锁定、超时、无法处理与没见过的码都是没通过`() {
        for (code in listOf(
            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
            BiometricPrompt.BIOMETRIC_ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.BIOMETRIC_ERROR_NO_SPACE,
            BiometricPrompt.BIOMETRIC_ERROR_VENDOR,
            999,
            -1,
        )) {
            assertEquals("码 $code", AuthResult.Denied, BiometricResults.fromError(code))
        }
    }

    @Test
    fun `任何错误码都不可能得到“已通过”`() {
        for (code in -100..1000) assertNotEquals("码 $code", AuthResult.Authenticated, BiometricResults.fromError(code))
    }
}
