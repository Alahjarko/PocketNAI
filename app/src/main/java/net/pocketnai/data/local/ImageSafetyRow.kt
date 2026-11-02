package net.pocketnai.data.local

/** 包括撤销窗口中的记录，避免其它图片入口因画廊暂时隐藏而失去遮罩。 */
data class ImageSafetyRow(val relativePath: String, val imageId: String, val prompt: String, val charactersJson: String?)
