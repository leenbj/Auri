package app.auri.syncplatform.auth

import android.hardware.biometrics.BiometricManager
import app.auri.sync.SyncLog
import app.auri.sync.destinations.AuthPurpose
import app.auri.sync.destinations.AuthResult
import app.auri.sync.destinations.UnavailableReason
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 B4：生成或轮换凭证前的本机核验只认强生物识别（指纹/人脸），**锁屏 PIN/图案/密码不行**——
 * 只设了 PIN、没登记生物识别的手机，拿到手机又知道 PIN 的人不一定是用户本人，而凭证一旦拿到手，Agent 就能读到这个目的地的文字。
 * 这里不靠设备：经 [BiometricFramework] 这条缝，假系统按“向它要的核验方式”如实回答（要了设备凭据，PIN 就算数），
 * 所以本应用要的是哪一种核验，会直接决定这些测试的结果。
 */
class BiometricGateTest {
    private val deviceCredential = BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG

    /** 一台“设置了锁屏 PIN、没有登记任何生物识别”的手机：只有被问到设备凭据时才说可以。 */
    private class PinOnlyPhone(var promptResult: AuthResult = AuthResult.Authenticated) : BiometricFramework {
        val asked = CopyOnWriteArrayList<Int>()
        val prompts = CopyOnWriteArrayList<PromptRequest>()

        override fun canAuthenticate(allowedAuthenticators: Int): Int {
            asked += allowedAuthenticators
            val pinCounts = allowedAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0
            return if (pinCounts) BiometricManager.BIOMETRIC_SUCCESS else BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        }

        override suspend fun prompt(request: PromptRequest): AuthResult {
            prompts += request
            return promptResult
        }
    }

    /** 一台登记了强生物识别的手机：不管问什么都说可以（含只问强生物识别）。 */
    private class BiometricPhone(var promptResult: AuthResult = AuthResult.Authenticated) : BiometricFramework {
        val asked = CopyOnWriteArrayList<Int>()
        val prompts = CopyOnWriteArrayList<PromptRequest>()

        override fun canAuthenticate(allowedAuthenticators: Int): Int {
            asked += allowedAuthenticators
            return BiometricManager.BIOMETRIC_SUCCESS
        }

        override suspend fun prompt(request: PromptRequest): AuthResult {
            prompts += request
            return promptResult
        }
    }

    @Test
    fun `只设了锁屏 PIN、没有登记生物识别：核验不可用，提示根本不弹，所以凭证不会生成`() = runBlocking {
        val phone = PinOnlyPhone()

        val result = BiometricGate(phone).authenticate(AuthPurpose.ISSUE_CREDENTIAL)

        assertEquals(AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED), result)
        assertTrue("PIN 不能当成本人核验：系统提示一次都没弹：${phone.prompts}", phone.prompts.isEmpty())
    }

    @Test
    fun `向系统要的核验方式是纯强生物识别，不带设备凭据这一位；问可用性与弹提示时要的是同一个`() = runBlocking {
        val phone = BiometricPhone()

        BiometricGate(phone).authenticate(AuthPurpose.ROTATE_CREDENTIAL)

        assertEquals(listOf(strong), phone.asked.toList())
        assertEquals(listOf(PromptRequest(AuthPurpose.ROTATE_CREDENTIAL, strong)), phone.prompts.toList())
        assertEquals("设备凭据（PIN/图案/密码）这一位必须是 0", 0, phone.prompts.single().allowedAuthenticators and deviceCredential)
        assertEquals(strong, BiometricGate.REQUIRED)
    }

    @Test
    fun `登记了生物识别的手机：弹提示，系统说通过才是通过；取消、拒绝都原样交回，不会变成通过`() = runBlocking {
        val phone = BiometricPhone()
        val gate = BiometricGate(phone)

        assertEquals(AuthResult.Authenticated, gate.authenticate(AuthPurpose.ISSUE_CREDENTIAL))
        phone.promptResult = AuthResult.Cancelled
        assertEquals(AuthResult.Cancelled, gate.authenticate(AuthPurpose.ISSUE_CREDENTIAL))
        phone.promptResult = AuthResult.Denied
        assertEquals(AuthResult.Denied, gate.authenticate(AuthPurpose.ISSUE_CREDENTIAL))
        assertEquals(listOf(AuthPurpose.ISSUE_CREDENTIAL, AuthPurpose.ISSUE_CREDENTIAL, AuthPurpose.ISSUE_CREDENTIAL), phone.prompts.map { it.purpose })
    }

    @Test
    fun `每一次都现场问系统，不缓存上一次的结果：通过过一次之后手机变成不可用，下一次就是不可用`() = runBlocking {
        var enrolled = true
        val prompts = CopyOnWriteArrayList<PromptRequest>()
        val phone = object : BiometricFramework {
            override fun canAuthenticate(allowedAuthenticators: Int) =
                if (enrolled) BiometricManager.BIOMETRIC_SUCCESS else BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

            override suspend fun prompt(request: PromptRequest): AuthResult {
                prompts += request
                return AuthResult.Authenticated
            }
        }
        val gate = BiometricGate(phone)

        assertEquals(AuthResult.Authenticated, gate.authenticate(AuthPurpose.ISSUE_CREDENTIAL))
        enrolled = false
        assertEquals(AuthResult.Unavailable(UnavailableReason.NONE_ENROLLED), gate.authenticate(AuthPurpose.ROTATE_CREDENTIAL))
        assertEquals("第二次没有弹提示", 1, prompts.size)
    }

    @Test
    fun `没有硬件与暂时不可用也是不可用，提示不弹；日志只有结果的种类`() = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val log = SyncLog { lines += it }
        val prompts = CopyOnWriteArrayList<PromptRequest>()
        var code = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        val phone = object : BiometricFramework {
            override fun canAuthenticate(allowedAuthenticators: Int) = code

            override suspend fun prompt(request: PromptRequest): AuthResult {
                prompts += request
                return AuthResult.Authenticated
            }
        }
        val gate = BiometricGate(phone, log)

        assertEquals(AuthResult.Unavailable(UnavailableReason.NO_HARDWARE), gate.authenticate(AuthPurpose.ISSUE_CREDENTIAL))
        code = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        assertEquals(AuthResult.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE), gate.authenticate(AuthPurpose.ISSUE_CREDENTIAL))

        assertTrue(prompts.isEmpty())
        assertEquals(listOf("auth.Unavailable", "auth.Unavailable"), lines.toList())
    }

    @Test
    fun `OCR M21：系统框架抛 SecurityException 或 IllegalArgumentException 时降级为暂时不可用，不逃逸且只记异常类名`() = runBlocking {
        val lines = CopyOnWriteArrayList<String>()
        val log = SyncLog { lines += it }
        val checkThrows = object : BiometricFramework {
            override fun canAuthenticate(allowedAuthenticators: Int): Int = throw SecurityException("secret detail")

            override suspend fun prompt(request: PromptRequest): AuthResult = error("should not reach prompt")
        }
        val promptThrows = object : BiometricFramework {
            override fun canAuthenticate(allowedAuthenticators: Int): Int = BiometricManager.BIOMETRIC_SUCCESS

            override suspend fun prompt(request: PromptRequest): AuthResult = throw IllegalArgumentException("bad combo")
        }

        assertEquals(AuthResult.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE), BiometricGate(checkThrows, log).authenticate(AuthPurpose.ISSUE_CREDENTIAL))
        assertEquals(AuthResult.Unavailable(UnavailableReason.TEMPORARILY_UNAVAILABLE), BiometricGate(promptThrows, log).authenticate(AuthPurpose.ROTATE_CREDENTIAL))
        assertEquals(listOf("auth.error SecurityException", "auth.error IllegalArgumentException"), lines.toList())
    }

    @Test
    fun `OCR M21：AndroidBiometricFramework 调 authenticate 必须兜住异常、取消信号并恢复为暂时不可用`() {
        val source = java.io.File("src/main/kotlin/app/auri/syncplatform/auth/BiometricLocalAuthenticator.kt").readText()
        val promptBody = source.substringAfter("override suspend fun prompt(")

        assertTrue("authenticate 调用必须包在 try/catch 里：$promptBody", "try {" in promptBody && "builder.build().authenticate(" in promptBody)
        assertTrue("异常时必须取消 CancellationSignal：$promptBody", "signal.cancel()" in promptBody)
        assertTrue("异常时必须恢复为 TEMPORARILY_UNAVAILABLE：$promptBody", "UnavailableReason.TEMPORARILY_UNAVAILABLE" in promptBody)
    }
}
