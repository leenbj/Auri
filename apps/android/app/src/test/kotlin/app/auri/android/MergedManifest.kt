package app.auri.android

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/** 单元测试读**合并之后**的应用清单（AGP 生成的 test_config.properties 里给出路径）：库模块带进来的声明也在里面。 */
internal object MergedManifest {
    fun file(): File {
        val resource = "com/android/tools/test_config.properties"
        val stream = MergedManifest::class.java.classLoader!!.getResourceAsStream(resource)
            ?: error("找不到 $resource；:app 需要 unitTests.isIncludeAndroidResources = true")
        val props = Properties().apply { stream.use { load(it) } }
        return File(props.getProperty("android_merged_manifest") ?: error("test_config.properties 里没有 android_merged_manifest"))
    }

    fun document(): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(file())

    fun application(): Element = document().getElementsByTagName("application").item(0) as Element

    fun elements(tag: String): List<Element> = document().getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    fun permissions(): List<String> = (elements("uses-permission") + elements("uses-permission-sdk-23")).map { it.getAttribute("android:name") }
}
