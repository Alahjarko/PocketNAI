package net.pocketnai.domain.metadata

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * 测试用的 PNG 构造器。
 *
 * 刻意不在测试里放一张真实的 NovelAI 图片：那会把用户自己的提示词带进版本库。
 * 这里按**真实文件的形状**拼字节（文本块在 IDAT 之后、关键字用下划线版 `Generation_time`），
 * 因此解析器要面对的输入形态与线上一致。
 */
object PngFixture {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** 拼一张 PNG：IHDR + 若干 IDAT + 文本块 + IEND。IDAT 内容不参与解析，随便填。 */
    fun png(
        texts: List<Pair<String, String>> = emptyList(),
        compressedTexts: List<Pair<String, String>> = emptyList(),
        internationalTexts: List<Pair<String, String>> = emptyList(),
        idatChunks: Int = 2,
        idatSize: Int = 32,
        trailingBytes: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.write(chunk("IHDR", ByteArray(13) { if (it == 3) 1 else 0 }))
        repeat(idatChunks) { index ->
            out.write(chunk("IDAT", ByteArray(idatSize) { index.toByte() }))
        }
        texts.forEach { (keyword, text) -> out.write(textChunk(keyword, text)) }
        compressedTexts.forEach { (keyword, text) -> out.write(compressedTextChunk(keyword, text)) }
        internationalTexts.forEach { (keyword, text) -> out.write(internationalTextChunk(keyword, text)) }
        out.write(trailingBytes)
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    /** NovelAI 生成的图片形状：Comment(JSON) / Title / Description / Software / Source / Generation_time。 */
    fun novelAi(
        commentJson: String,
        prompt: String = "1girl, silver hair",
        source: String = "NovelAI Diffusion V5 DB276663",
        software: String = "NovelAI",
        generationTime: String = "2.18",
        extraTexts: List<Pair<String, String>> = emptyList(),
    ): ByteArray = png(
        texts = buildList {
            add("Comment" to commentJson)
            add("Title" to "AI generated image")
            add("Description" to prompt)
            add("Software" to software)
            add("Source" to source)
            add("Generation_time" to generationTime)
            addAll(extraTexts)
        },
    )

    /** 一份与真实文件字段一致的参数 JSON（提示词为构造值）。 */
    fun commentJson(
        prompt: String = "1girl, silver hair",
        baseCaption: String = prompt,
        negativeCaption: String = "",
        uc: String = "",
        width: Int = 832,
        height: Int = 1216,
        steps: Int = 23,
        scale: Double = 7.0,
        cfgRescale: Double = 0.0,
        seed: Long = 495204733L,
        sampler: String = "k_euler_ancestral",
        noiseSchedule: String = "karras",
        nSamples: Int = 1,
        actualPrompt: String? = null,
        charCaptions: String = "[]",
        negativeCharCaptions: String = "[]",
        referenceStrengths: String = "[]",
        directorStrengths: String = "null",
        hasImage: Boolean = false,
        extra: String = "",
    ): String = buildString {
        append("{")
        append("\"prompt\": ${jsonString(prompt)},")
        append("\"steps\": $steps,")
        append("\"height\": $height,")
        append("\"width\": $width,")
        append("\"scale\": $scale,")
        append("\"cfg_rescale\": $cfgRescale,")
        append("\"seed\": $seed,")
        append("\"n_samples\": $nSamples,")
        append("\"noise_schedule\": ${jsonString(noiseSchedule)},")
        append("\"sampler\": ${jsonString(sampler)},")
        append("\"uc\": ${jsonString(uc)},")
        if (actualPrompt != null) {
            append("\"actual_prompts\": {\"prompt\": {\"base_caption\": ${jsonString(actualPrompt)}}},")
        }
        append("\"reference_strength_multiple\": $referenceStrengths,")
        append("\"director_reference_strengths\": $directorStrengths,")
        if (hasImage) append("\"image\": \"AAAA\",")
        append(
            "\"v4_prompt\": {\"caption\": {\"base_caption\": ${jsonString(baseCaption)}, " +
                "\"char_captions\": $charCaptions}, \"use_coords\": false, \"legacy_uc\": false},",
        )
        append(
            "\"v4_negative_prompt\": {\"caption\": {\"base_caption\": ${jsonString(negativeCaption)}, " +
                "\"char_captions\": $negativeCharCaptions}},",
        )
        append("\"version\": 1")
        if (extra.isNotEmpty()) append(", $extra")
        append("}")
    }

    /** 一条角色提示词，用于构造 `char_captions`。 */
    fun charCaption(caption: String, x: Double, y: Double): String =
        "{\"char_caption\": ${jsonString(caption)}, \"centers\": [{\"x\": $x, \"y\": $y}]}"

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    // ---- chunk 拼装 ----

    private fun textChunk(keyword: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(keyword.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(text.toByteArray(Charsets.UTF_8))
        return chunk("tEXt", out.toByteArray())
    }

    private fun compressedTextChunk(keyword: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(keyword.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(0) // 压缩方法 0 = zlib
        out.write(deflate(text.toByteArray(Charsets.UTF_8)))
        return chunk("zTXt", out.toByteArray())
    }

    private fun internationalTextChunk(keyword: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(keyword.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(0) // 未压缩
        out.write(0) // 压缩方法
        out.write(0) // 语言标签结束
        out.write(0) // 翻译关键字结束
        out.write(text.toByteArray(Charsets.UTF_8))
        return chunk("iTXt", out.toByteArray())
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                out.write(buffer, 0, written)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** PNG chunk：长度 + 类型 + 正文 + CRC。CRC 不校验，填零即可。 */
    private fun chunk(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(uint32(body.size.toLong()))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.write(uint32(0L))
        return out.toByteArray()
    }

    fun uint32(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
