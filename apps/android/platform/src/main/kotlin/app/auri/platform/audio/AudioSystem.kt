package app.auri.platform.audio

import app.auri.capture.adapter.PcmFormat
import app.auri.capture.adapter.Subscription
import app.auri.capture.contract.LifecycleState
import app.auri.capture.contract.ObservedInputRouteType

/** 系统里一个音频输入设备的快照：只留判断路由所需的东西，不含地址、序列号等设备标识。 */
data class InputDevice(val id: Int, val type: Int, val name: String?)

/**
 * `android.media.AudioDeviceInfo.TYPE_*` 的数值（编译期常量，这里复写一份是为了让这些逻辑在 JVM 单元测试里跑，
 * 不依赖 Android 框架类；[AndroidAudioSystem] 里有断言保证与框架的值一致）。
 */
object AudioDeviceTypes {
    const val WIRED_HEADSET = 3
    const val WIRED_HEADPHONES = 4
    const val BLUETOOTH_SCO = 7
    const val BLUETOOTH_A2DP = 8
    const val USB_DEVICE = 11
    const val USB_ACCESSORY = 12
    const val BUILTIN_MIC = 15
    const val USB_HEADSET = 22
    const val BLE_HEADSET = 26
}

/** 系统观测到的输入设备类型 → 合同里的 route_type。 */
fun routeTypeOf(device: InputDevice?): ObservedInputRouteType = when (device?.type) {
    null -> ObservedInputRouteType.UNKNOWN
    AudioDeviceTypes.BLUETOOTH_SCO -> ObservedInputRouteType.BLUETOOTH_HFP
    AudioDeviceTypes.BLE_HEADSET -> ObservedInputRouteType.BLUETOOTH_LE_AUDIO
    AudioDeviceTypes.BLUETOOTH_A2DP -> ObservedInputRouteType.BLUETOOTH_UNKNOWN_PROFILE
    AudioDeviceTypes.BUILTIN_MIC -> ObservedInputRouteType.BUILT_IN_MIC
    AudioDeviceTypes.WIRED_HEADSET, AudioDeviceTypes.WIRED_HEADPHONES -> ObservedInputRouteType.WIRED_HEADSET
    AudioDeviceTypes.USB_DEVICE, AudioDeviceTypes.USB_ACCESSORY, AudioDeviceTypes.USB_HEADSET -> ObservedInputRouteType.USB_AUDIO
    else -> ObservedInputRouteType.OTHER
}

/**
 * 一个**蓝牙**输入设备。构造函数私有，只能经 [from] 由蓝牙类型的设备造出来；
 * [AudioSystem.openRecorder] 只接受它，所以“请求手机麦克风”在类型上就写不出来：没有可传的参数。
 */
class BluetoothInput private constructor(val device: InputDevice) {
    val routeType: ObservedInputRouteType get() = routeTypeOf(device)

    override fun equals(other: Any?) = other is BluetoothInput && other.device == device

    override fun hashCode() = device.hashCode()

    companion object {
        /** 只有 SCO（HFP）与 BLE Audio 耳机麦克风算蓝牙输入；手机内置麦克风、有线、USB 都返回 null。 */
        fun from(device: InputDevice): BluetoothInput? =
            if (device.type == AudioDeviceTypes.BLUETOOTH_SCO || device.type == AudioDeviceTypes.BLE_HEADSET) BluetoothInput(device) else null
    }
}

/** 一次正在进行的系统录音。 */
interface Recorder {
    /** 开始采集。系统拒绝时抛异常。 */
    fun startRecording()

    /** 阻塞读一批 16 位采样；返回读到的个数，负数表示出错或已停止，0 表示这次没读到。 */
    fun read(buffer: ShortArray): Int

    /** 系统此刻实际把这次录音路由到哪个设备（Route Evidence 的来源）；系统还没说清时为 null。 */
    fun routedDevice(): InputDevice?

    /** 系统通知路由变了。回调可能在任意线程。 */
    fun onRoutingChanged(callback: () -> Unit): Subscription

    /** 停止并释放（幂等）。 */
    fun stopAndRelease()
}

/** 适配器对 Android 音频系统的全部依赖，缩到最小，便于在 JVM 里用假系统测试路由与闸门行为。 */
interface AudioSystem {
    /** 系统当前可用的蓝牙输入设备。 */
    fun bluetoothInputs(): List<BluetoothInput>

    /** 请求系统把通信音频路由到这个蓝牙设备。这只是请求，不是证据。系统不接受返回 false。 */
    fun requestCommunicationRoute(input: BluetoothInput): Boolean

    /** 撤销 [requestCommunicationRoute]（幂等）。 */
    fun clearCommunicationRoute()

    /** 请求音频焦点；之后被抢走时回调 [onLost]。返回的订阅用来放弃焦点；系统不给焦点时返回 null。 */
    fun requestAudioFocus(onLost: () -> Unit): Subscription?

    /** 打开一个指向 [input] 的录音（还没开始）。参数里没有别的设备，也没有“默认输入”。 */
    fun openRecorder(input: BluetoothInput, format: PcmFormat): Recorder
}

/** 适配器需要问环境的几件事。权限与前台服务是两层，分开问。 */
interface CaptureEnvironment {
    fun micPermissionGranted(): Boolean

    /** API 31+ 需要 BLUETOOTH_CONNECT 才能选择蓝牙设备。 */
    fun bluetoothPermissionGranted(): Boolean

    /** 麦克风类前台服务的权限（FOREGROUND_SERVICE_MICROPHONE，API 34+）；与麦克风运行时权限是两层。 */
    fun microphoneServicePermissionGranted(): Boolean

    fun lifecycle(): LifecycleState
}
