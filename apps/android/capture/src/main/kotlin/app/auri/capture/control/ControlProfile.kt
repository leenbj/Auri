package app.auri.capture.control

import app.auri.capture.contract.BusinessCommand
import app.auri.capture.contract.PlatformEvent

/**
 * 耳机命令映射：平台事件 → 业务命令（PRD FR-HS-001 / FR-HS-004）。
 *
 * 键是平台事件，不是物理手势：应用看不到“双击”，只看得到播放 / 暂停 / 切换这类系统事件。
 * 约束与 contracts/schemas/headset-profile.schema.json 的 command_mapping 一致，写在构造函数里，构造不出非法的映射：
 *  - 不含 screen_button（屏幕按钮不经过档案）；
 *  - volume_change 只能映射到 STOP 或 IGNORE（音量手势不能开始录音，ADR-0014）；
 *  - 映射里没有的平台事件一律当 IGNORE：未知不是命令。
 */
data class ControlProfile(
    val profileId: String,
    val profileVersion: Int,
    val mapping: Map<PlatformEvent, BusinessCommand>,
) {
    init {
        require(profileId.length <= MAX_ID_LENGTH && ID_PATTERN.matches(profileId)) { "profile_id 只能是小写字母、数字与连字符，不超过 $MAX_ID_LENGTH 字：$profileId" }
        require(profileVersion >= 1) { "profile_version 至少为 1" }
        require(mapping.isNotEmpty()) { "command_mapping 至少要有一项" }
        require(PlatformEvent.SCREEN_BUTTON !in mapping) { "屏幕按钮不经过耳机档案" }
        mapping[PlatformEvent.VOLUME_CHANGE]?.let {
            require(it == BusinessCommand.STOP || it == BusinessCommand.IGNORE) { "音量变化只能映射为 STOP 或 IGNORE" }
        }
    }

    fun commandFor(event: PlatformEvent): BusinessCommand = mapping[event] ?: BusinessCommand.IGNORE

    /** 校准向导的“只观察”阶段：事件照常产生，命令全部是 IGNORE，所以观察期间不会触发任何录音。 */
    fun observeOnly(): ControlProfile = copy(mapping = mapping.mapValues { BusinessCommand.IGNORE })

    /**
     * 单事件切换映射（FR-HS-004）：只有校准证明耳机的“开始”与“停止”手势发出同一种事件时才采用，
     * 所以只允许 play、pause、toggle 三种。
     */
    fun withSingleEventToggle(event: PlatformEvent): ControlProfile {
        require(event in TOGGLE_CANDIDATES) { "只有 play、pause、toggle 可以映射成单事件切换：$event" }
        return copy(mapping = mapping + (event to BusinessCommand.TOGGLE))
    }

    companion object {
        const val GENERIC_ID = "generic-media-control"

        private const val MAX_ID_LENGTH = 100
        private val ID_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        private val TOGGLE_CANDIDATES = setOf(PlatformEvent.PLAY, PlatformEvent.PAUSE, PlatformEvent.TOGGLE)

        /** 没有校准结果时的默认映射：play → START，pause → STOP，toggle → TOGGLE，上一首 / 下一首忽略。 */
        fun generic() = ControlProfile(
            GENERIC_ID,
            1,
            mapOf(
                PlatformEvent.PLAY to BusinessCommand.START,
                PlatformEvent.PAUSE to BusinessCommand.STOP,
                PlatformEvent.TOGGLE to BusinessCommand.TOGGLE,
                PlatformEvent.NEXT to BusinessCommand.IGNORE,
                PlatformEvent.PREVIOUS to BusinessCommand.IGNORE,
            ),
        )
    }
}
