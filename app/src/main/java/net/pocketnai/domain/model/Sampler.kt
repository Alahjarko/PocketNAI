package net.pocketnai.domain.model

/** 采样器，值为 NovelAI API 的 `sampler` 字段。 */
enum class Sampler(val apiValue: String, val displayName: String) {
    EULER("k_euler", "Euler"),
    EULER_ANCESTRAL("k_euler_ancestral", "Euler Ancestral"),
    DPM_PLUS_PLUS_2S_ANCESTRAL("k_dpmpp_2s_ancestral", "DPM++ 2S Ancestral"),
    DPM_PLUS_PLUS_2M("k_dpmpp_2m", "DPM++ 2M"),
    DPM_PLUS_PLUS_SDE("k_dpmpp_sde", "DPM++ SDE"),
    DPM_PLUS_PLUS_2M_SDE("k_dpmpp_2m_sde", "DPM++ 2M SDE"),
    DDIM("ddim", "DDIM"),
    ;

    companion object {
        fun fromApiValue(apiValue: String): Sampler? =
            entries.firstOrNull { it.apiValue == apiValue }
    }
}

/** 噪声调度，值为 NovelAI API 的 `noise_schedule` 字段。 */
enum class NoiseSchedule(val apiValue: String, val displayName: String) {
    NATIVE("native", "Native"),
    KARRAS("karras", "Karras"),
    EXPONENTIAL("exponential", "Exponential"),
    POLYEXPONENTIAL("polyexponential", "Polyexponential"),
    ;

    companion object {
        fun fromApiValue(apiValue: String): NoiseSchedule? =
            entries.firstOrNull { it.apiValue == apiValue }
    }
}
