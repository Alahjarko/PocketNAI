package net.pocketnai.ui.common

import androidx.annotation.StringRes
import net.pocketnai.R
import net.pocketnai.domain.model.GenerationMode

/**
 * 生成模式的中文名。
 *
 * 单独放在这里而不是各界面各写一遍 `when`：画廊的筛选控件与详情页都要显示它，
 * 两处各写一份的话，将来新增一种模式必然漏掉一处，而漏掉的那处不会报错、
 * 只会在界面上少一个选项。
 */
@StringRes
fun GenerationMode.labelRes(): Int = when (this) {
    GenerationMode.TXT2IMG -> R.string.generate_mode_txt2img
    GenerationMode.IMG2IMG -> R.string.generate_mode_img2img
    GenerationMode.PRECISE_REFERENCE -> R.string.generate_mode_precise_reference
    GenerationMode.INPAINT -> R.string.generate_mode_inpaint
    GenerationMode.UPSCALE -> R.string.generate_mode_upscale
}
