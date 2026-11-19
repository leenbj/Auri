package app.auri.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 调试版（src/debug）：没有摄像头的设备和模拟器上，把网关命令行输出的配对载荷粘贴进来配对（等同于扫到了那张二维码）。
 * release 版在 src/release 里有一个同名的空实现：这段界面与文案不会进 release 包（评审 F14，ReleaseVariantTest 读编译出的类核对）。
 */
@Composable
fun ManualPairingEntry(onSubmit: (String) -> Unit, enabled: Boolean) {
    var text by remember { mutableStateOf("") }
    Text("调试版：没有摄像头时可以粘贴网关命令行输出的配对载荷（JSON）")
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("配对载荷") }, modifier = Modifier.padding(vertical = 4.dp))
    Button(onClick = { onSubmit(text) }, enabled = enabled && text.isNotBlank()) { Text("用文字配对") }
}
