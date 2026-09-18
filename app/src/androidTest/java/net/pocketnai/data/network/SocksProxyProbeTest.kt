package net.pocketnai.data.network

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.pocketnai.data.proxy.PublicProxyNodes
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 诊断探针：对照两种"把请求送进 SOCKS 代理"的方式 ——
 * A. `OkHttpClient.proxy(...)`（固定的 Proxy 对象）
 * B. `OkHttpClient.proxySelector(...)`（每次连接回调的 ProxySelector）
 *
 * 外加 B 方式下的"同 client 第二次请求"，验证 OkHttp 的 RouteDatabase 是否
 * 在首次失败后短路后续尝试。结果看 logcat 的 `SocksProbe` tag。
 *
 * 只发 GET 到 NovelAI 首页，不消耗 Anlas。
 */
@RunWith(AndroidJUnit4::class)
class SocksProxyProbeTest {

    private val target = "https://image.novelai.net/"

    @Test
    fun probeSocksConnectionLatency() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nodes = PublicProxyNodes(context).all()
        println("SocksProbe: 节点数 ${nodes.size}")
        if (nodes.isEmpty()) return
        val node = nodes.first()

        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(node.username, node.password.toCharArray())
        })

        // A：固定 proxy 对象
        runScenario("A(proxy)", 1) {
            OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(node.host, node.port)))
        }

        // B：proxySelector（应用现在用的方式）
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> =
                listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(node.host, node.port)))

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                Log.i("SocksProbe", "B: connectFailed -> ${ioe.javaClass.simpleName}: ${ioe.message}")
            }
        }
        runScenario("B(selector)#1", 1) { OkHttpClient.Builder().proxySelector(selector) }
        runScenario("B(selector)#2", 1) { OkHttpClient.Builder().proxySelector(selector) }
    }

    private fun runScenario(name: String, repeat: Int, builder: () -> OkHttpClient.Builder) {
        repeat(repeat) { index ->
            val client = builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
            val startedAt = System.nanoTime()
            try {
                client.newCall(Request.Builder().url(target).build()).execute().use { response ->
                    val ms = (System.nanoTime() - startedAt) / 1_000_000
                    Log.i("SocksProbe", "$name#$index OK HTTP ${response.code} in ${ms}ms")
                }
            } catch (e: Exception) {
                val ms = (System.nanoTime() - startedAt) / 1_000_000
                Log.e("SocksProbe", "$name#$index FAIL in ${ms}ms -> ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
