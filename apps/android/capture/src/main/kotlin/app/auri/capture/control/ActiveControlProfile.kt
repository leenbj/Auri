package app.auri.capture.control

/**
 * 耳机控制适配器当前用的命令映射。平时是保存下来的档案（没有就是默认映射）；
 * 校准向导进行中，用向导给出的临时映射盖在上面，结束或放弃后撤掉。
 */
class ActiveControlProfile(saved: ControlProfile = ControlProfile.generic()) {
    @Volatile
    private var saved: ControlProfile = saved

    @Volatile
    private var temporary: ControlProfile? = null

    val current: ControlProfile get() = temporary ?: saved

    fun useSaved(profile: ControlProfile) {
        saved = profile
    }

    /** 校准期间的临时映射；传 null 撤掉。 */
    fun useTemporary(profile: ControlProfile?) {
        temporary = profile
    }
}
