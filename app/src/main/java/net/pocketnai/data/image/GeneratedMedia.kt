package net.pocketnai.data.image

import android.graphics.BitmapFactory
import android.util.Xml
import com.caverock.androidsvg.SVG
import org.xmlpull.v1.XmlPullParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.ceil

/** Inspect original output bytes without flattening animated or vector media. */
object GeneratedMedia {
    data class Info(val extension: String, val width: Int, val height: Int)

    fun inspect(bytes: ByteArray, expectedFormat: String): Info {
        require(bytes.isNotEmpty() && bytes.size <= 36 * 1024 * 1024)
        val info = if (expectedFormat == "svg") {
            val svg = parseSafeSvg(bytes)
            val bounds = svg.documentViewBox
            val width = svg.documentWidth.takeIf { it.isFinite() && it > 0 } ?: bounds?.width() ?: 0f
            val height = svg.documentHeight.takeIf { it.isFinite() && it > 0 } ?: bounds?.height() ?: 0f
            Info("svg", ceil(width.toDouble()).toInt(), ceil(height.toDouble()).toInt())
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val extension = when (options.outMimeType) {
                "image/png" -> "png"; "image/jpeg" -> "jpeg"; "image/webp" -> "webp"; "image/gif" -> "gif"
                else -> error("Unsupported image format")
            }
            Info(extension, options.outWidth, options.outHeight)
        }
        require(info.extension == expectedFormat)
        require(info.width in 1..3840 && info.height in 1..3840 && info.width.toLong() * info.height <= 8_294_400)
        return info
    }

    /** Static SVG only. Reject rather than silently rewrite an unsafe original before export/share. */
    fun parseSafeSvg(bytes: ByteArray): SVG {
        require(bytes.size in 1..(8 * 1024 * 1024))
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text))
        val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(text.reader()) }
        var elements = 0
        var root = false
        var styleDepth = -1
        val styleText = StringBuilder()
        while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    require(++elements <= 50_000 && parser.depth <= 128)
                    if (!root) { require(parser.name == "svg"); root = true }
                    require(parser.namespace.isNullOrEmpty() || parser.namespace == "http://www.w3.org/2000/svg")
                    require(parser.name in STATIC_ELEMENTS)
                    if (parser.name == "style") {
                        require(styleDepth < 0)
                        styleDepth = parser.depth
                        styleText.setLength(0)
                    }
                    for (index in 0 until parser.attributeCount) {
                        val name = parser.getAttributeName(index).lowercase()
                        val value = parser.getAttributeValue(index).trim()
                        require(!name.startsWith("on") && name != "base")
                        if (name == "href") require(value.startsWith("#") && value.length > 1)
                        validateCssReferences(value)
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    require(parser.text != null)
                    if (styleDepth > 0) styleText.append(parser.text)
                }
                XmlPullParser.END_TAG -> if (parser.depth == styleDepth) {
                    validateCssReferences(styleText.toString())
                    styleDepth = -1
                }
                XmlPullParser.PROCESSING_INSTRUCTION, XmlPullParser.DOCDECL -> error("Unsupported SVG declaration")
            }
        }
        require(root)
        return SVG.getFromString(text)
    }

    private fun validateCssReferences(value: String) {
        require(!value.contains('@') && !value.contains('\\'))
        Regex("url\\s*\\((.*?)\\)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(value).forEach {
            require(it.groupValues[1].trim().trim('\'', '"').matches(Regex("#[A-Za-z_][A-Za-z0-9_.:-]*")))
        }
    }

    private val STATIC_ELEMENTS = setOf("svg", "g", "defs", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
        "text", "tspan", "textPath", "linearGradient", "radialGradient", "stop", "clipPath", "mask", "pattern", "symbol", "use",
        "title", "desc", "style")
}
