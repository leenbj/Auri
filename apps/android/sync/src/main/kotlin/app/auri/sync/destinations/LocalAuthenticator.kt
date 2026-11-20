package app.auri.sync.destinations

/** 为什么要核验本人：界面据此写提示（“生成凭证”还是“轮换凭证”）。 */
enum class AuthPurpose { ISSUE_CREDENTIAL, ROTATE_CREDENTIAL }

/** 核验没法进行的原因：每一种用户该做的事不同，所以不合并。 */
enum class UnavailableReason {
    /** 这台设备没有生物识别硬件，也没有可用的锁屏方式。 */
    NO_HARDWARE,

    /** 有硬件但没有登记任何指纹/人脸，也没设锁屏凭证：去系统设置里登记。 */
    NONE_ENROLLED,

    /** 暂时用不了（传感器被占用、被系统临时锁定、需要安全更新）。 */
    TEMPORARILY_UNAVAILABLE,
}

/** 一次本机身份核验的结果。只有 [Authenticated] 允许继续；其余三种都意味着**不能**生成或轮换凭证。 */
sealed interface AuthResult {
    data object Authenticated : AuthResult

    /** 核验没通过（验证失败次数过多、被锁定等）。 */
    data object Denied : AuthResult

    /** 用户自己取消了，或系统取消了提示（来电、应用被切走）。 */
    data object Cancelled : AuthResult

    data class Unavailable(val reason: UnavailableReason) : AuthResult
}

/**
 * 本机身份核验：强生物识别或设备锁屏凭证（Ticket 22 第 2 项）。生成与轮换 Agent 凭证之前**每一次**都要现场核验，不缓存上一次的通过。
 * Android 实现在 :syncplatform（系统的 BiometricPrompt）；这里只有接口，JVM 测试用假的。
 */
fun interface LocalAuthenticator {
    suspend fun authenticate(purpose: AuthPurpose): AuthResult
}
