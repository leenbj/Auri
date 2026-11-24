package app.auri.capture.calibration

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.ContractJson
import app.auri.capture.contract.PlatformEvent
import app.auri.capture.control.ControlProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 本文件是 contracts/schemas/headset-profile.schema.json（合同 1.2）的 Kotlin 镜像：字段名、取值、约束一一对应。
// 与 adapter 类型一样，约束写在构造函数里，构造不出非法值；合同自带的样本在 HeadsetProfileContractTest 里逐个对表。

/** common.schema.json#/$defs/compat_status */
@Serializable
enum class CompatStatus { NOT_TESTED, CALIBRATION_REQUIRED, SUPPORTED_FOREGROUND, SUPPORTED_RECORDING_IN_BACKGROUND, SUPPORTED_BACKGROUND_RESTART, PARTIAL, UNSUPPORTED }

/** common.schema.json#/$defs/evidence_level：E2 = 单测/模拟器，E3 = 真机 + 真实耳机（第 5.2 节）。 */
@Serializable
enum class EvidenceLevel { E0, E1, E2, E3, E4 }

/** common.schema.json#/$defs/platform */
@Serializable
enum class ProfilePlatform {
    @SerialName("harmonyos") HARMONYOS,
    @SerialName("ios") IOS,
    @SerialName("android") ANDROID,
}

/**
 * 一项能力的结论。兼容结论（无论支持还是不支持）必须有 E3 以上的真机证据和至少一个证据标识；
 * 没测的能力是 NOT_TESTED（可以带 E2 的模拟器/单测证据，但那只说明“跑过”，不是兼容结论）。
 */
@Serializable
data class CapabilityRecord(
    val status: CompatStatus,
    @SerialName("evidence_level") val evidenceLevel: EvidenceLevel?,
    @SerialName("evidence_ids") val evidenceIds: List<String>,
) {
    init {
        require(evidenceIds.toSet().size == evidenceIds.size) { "evidence_ids 不能重复" }
        require(evidenceIds.all { it.isNotEmpty() && it.length <= 300 }) { "evidence_ids 的每一项是 1 到 300 个字符" }
        if (!isUntested) {
            require(evidenceLevel == EvidenceLevel.E3 || evidenceLevel == EvidenceLevel.E4) { "兼容结论必须有 E3 以上的真机证据" }
            require(evidenceIds.isNotEmpty()) { "兼容结论必须带证据标识" }
        }
    }

    val isUntested: Boolean get() = status == CompatStatus.NOT_TESTED || status == CompatStatus.CALIBRATION_REQUIRED

    companion object {
        /** 完全没有证据的未测试。 */
        val untested = CapabilityRecord(CompatStatus.NOT_TESTED, null, emptyList())
    }
}

/** 五个生命周期场景，每个单独赋值，不合并、不缺省（样本 headset-profile.merged-background-capability）。 */
@Serializable
data class HeadsetCapabilities(
    @SerialName("foreground_start_stop") val foregroundStartStop: CapabilityRecord,
    @SerialName("stop_while_bluetooth_mic_active") val stopWhileBluetoothMicActive: CapabilityRecord,
    @SerialName("continue_recording_when_locked") val continueRecordingWhenLocked: CapabilityRecord,
    @SerialName("restart_after_stop_when_locked") val restartAfterStopWhenLocked: CapabilityRecord,
    @SerialName("start_after_process_termination") val startAfterProcessTermination: CapabilityRecord,
) {
    val all: List<CapabilityRecord>
        get() = listOf(foregroundStartStop, stopWhileBluetoothMicActive, continueRecordingWhenLocked, restartAfterStopWhenLocked, startAfterProcessTermination)

    companion object {
        val allUntested = HeadsetCapabilities(
            CapabilityRecord.untested, CapabilityRecord.untested, CapabilityRecord.untested, CapabilityRecord.untested, CapabilityRecord.untested,
        )
    }
}

/**
 * Headset Profile：某副耳机在某平台上的命令映射与已实测能力。只是数据，不含可执行代码。
 * Android 的媒体会话只给平台事件，看不到原始手势，所以 [rawGestureVisible] 在这里恒为 false，也不猜左右耳。
 */
@Serializable
data class HeadsetProfile(
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    @SerialName("profile_id") val profileId: String,
    @SerialName("profile_version") val profileVersion: Int,
    @SerialName("manufacturer_hint") val manufacturerHint: String?,
    @SerialName("model_hint") val modelHint: String?,
    @SerialName("firmware_constraint") val firmwareConstraint: String?,
    val platform: ProfilePlatform,
    @SerialName("raw_gesture_visible") val rawGestureVisible: Boolean,
    @SerialName("command_mapping") val commandMapping: Map<PlatformEvent, BusinessCommand>,
    @SerialName("input_policy") val inputPolicy: String = INPUT_POLICY,
    val capabilities: HeadsetCapabilities,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "schema_version 必须是 $SCHEMA_VERSION" }
        require(inputPolicy == INPUT_POLICY) { "input_policy 只能是 $INPUT_POLICY（不得回退手机麦克风）" }
        require(manufacturerHint == null || manufacturerHint.length <= 100) { "manufacturer_hint 超过 100 个字符" }
        require(modelHint == null || modelHint.length <= 100) { "model_hint 超过 100 个字符" }
        require(firmwareConstraint == null || firmwareConstraint.length <= 100) { "firmware_constraint 超过 100 个字符" }
        controlProfile() // 档案标识、版本与映射的规则与运行时映射是同一套，构造一次即校验
    }

    /** 运行时用的命令映射。 */
    fun controlProfile(): ControlProfile = ControlProfile(profileId, profileVersion, commandMapping)

    fun toJson(): String = ContractJson.json.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = "1.2"
        const val INPUT_POLICY = "bluetooth_only"

        fun fromJson(text: String): HeadsetProfile = ContractJson.json.decodeFromString(serializer(), text)
    }
}
