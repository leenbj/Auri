package app.auri.platform

import java.net.URL

/** 只在测试源集里：NetworkIsolationTest 用它的字节码检验扫描器真的抓得到网络引用。不是生产代码，不会被扫描。 */
class ExampleNetworkUser {
    fun open(): Any = URL("https://example.invalid/").openConnection()
}
