package app.auri.platform.audio

import android.media.AudioDeviceInfo
import app.auri.capture.contract.ObservedInputRouteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AudioDeviceTypes] 是为了让路由判断能在 JVM 里测而复写的一份框架常量。
 * AudioDeviceInfo.TYPE_* 是编译期常量，这里的比较在 JVM 里就能做：框架哪天变了值，这里先红，而不是在设备上把路由认错。
 */
class AudioDeviceTypesTest {
    @Test
    fun `复写的常量与 Android 框架的 AudioDeviceInfo 常量逐个相等`() {
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceTypes.WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceTypes.WIRED_HEADPHONES)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceTypes.BLUETOOTH_SCO)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceTypes.BLUETOOTH_A2DP)
        assertEquals(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceTypes.USB_DEVICE)
        assertEquals(AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceTypes.USB_ACCESSORY)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceTypes.BUILTIN_MIC)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceTypes.USB_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceTypes.BLE_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceTypes.BLE_SPEAKER)
    }

    @Test
    fun `框架的设备类型只有 SCO 与 BLE 耳机能造出蓝牙输入，其余都造不出`() {
        val buildable = (0..40).filter { BluetoothInput.from(InputDevice(1, it, "x")) != null }

        assertEquals(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET), buildable)
    }

    @Test
    fun `路由类型：蓝牙的三种、手机内置麦克风、有线、USB 各归各的，null 是 unknown`() {
        fun route(type: Int?) = routeTypeOf(type?.let { InputDevice(1, it, null) })

        assertEquals(ObservedInputRouteType.BLUETOOTH_HFP, route(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(ObservedInputRouteType.BLUETOOTH_LE_AUDIO, route(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(ObservedInputRouteType.BLUETOOTH_UNKNOWN_PROFILE, route(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(ObservedInputRouteType.BUILT_IN_MIC, route(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(ObservedInputRouteType.WIRED_HEADSET, route(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(ObservedInputRouteType.USB_AUDIO, route(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(ObservedInputRouteType.UNKNOWN, route(null))
        assertEquals(ObservedInputRouteType.OTHER, route(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    @Test
    fun `耳机已连接只看有没有蓝牙音频设备，名字取第一个非空的；手机自带的麦克风与喇叭不算`() {
        val phone = listOf(
            InputDevice(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Built-in Mic"),
            InputDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"),
            InputDevice(3, AudioDeviceInfo.TYPE_WIRED_HEADSET, "Wired"),
        )
        assertEquals(HeadsetStatus.NONE, headsetStatusOf(phone))
        assertFalse(headsetStatusOf(emptyList()).connected)

        val a2dpOnly = phone + InputDevice(4, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "FreeClip 2")
        assertEquals(HeadsetStatus(true, "FreeClip 2"), headsetStatusOf(a2dpOnly))

        val unnamed = phone + InputDevice(5, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, null) + InputDevice(6, AudioDeviceInfo.TYPE_BLE_HEADSET, "LE Buds")
        assertEquals(HeadsetStatus(true, "LE Buds"), headsetStatusOf(unnamed))

        val noName = headsetStatusOf(listOf(InputDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, " ")))
        assertTrue(noName.connected)
        assertNull(noName.name)
    }
}
