package app.auri.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 两个页面之间的切换，仅此而已（真正的导航由重做界面的人决定）。 */
@Composable
fun AppRoot(home: @Composable () -> Unit, control: @Composable () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { tab = 0 }, enabled = tab != 0) { Text("录音") }
            Button(onClick = { tab = 1 }, enabled = tab != 1) { Text("耳机控制") }
        }
        Box(Modifier.weight(1f)) { if (tab == 0) home() else control() }
    }
}
