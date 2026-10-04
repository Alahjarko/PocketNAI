package net.pocketnai.data.chat

import android.content.Context
import android.util.AtomicFile
import java.io.File

class AgentFiles(private val context: Context) {
    private val root = File(context.filesDir, "chat-agent")
    val names = listOf("soul.md", "tools.md")
    fun defaultText(name: String): String {
        require(name in names)
        return context.assets.open("chat/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    fun read(name: String): String {
        require(name in names)
        val file = File(root, name)
        return if (file.isFile) file.readText(Charsets.UTF_8)
        else defaultText(name)
    }
    fun save(name: String, text: String) {
        require(name in names && text.toByteArray(Charsets.UTF_8).size <= 128 * 1024)
        root.mkdirs()
        val file = AtomicFile(File(root, name))
        val out = file.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun reset(name: String) { require(name in names); File(root, name).delete() }
    fun systemPrompt(): String = read("soul.md") + "\n\n" + read("tools.md")
}
