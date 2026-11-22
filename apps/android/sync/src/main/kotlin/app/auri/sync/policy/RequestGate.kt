package app.auri.sync.policy

/**
 * 每个发往网关的请求交出去的前一刻要过的门（再评 S3、第三轮 S12）。放行就运行 [request] 并返回它的结果；不放行返回 null，[request] 一行也不运行。
 *
 * 一次操作要发好几个请求时（找网关：逐个地址探测、再问 mDNS），**每一个**请求各过一次门：只在第一个之前过一次，
 * 第一个请求在路上的时候授权被收回，后面的探测就绕过了这一道检查。
 */
interface RequestGate {
    suspend fun <T : Any> gated(request: suspend () -> T): T?

    /** 不设防的门：不属于“授权了才联系网关”的流程（配对、目的地管理）用它，请求原样运行。 */
    object Open : RequestGate {
        override suspend fun <T : Any> gated(request: suspend () -> T): T? = request()
    }
}
