package io.github.siye.littletoolbox.tools.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private data class Website(val name: String, val url: String)
private data class WebsiteResult(val website: Website, val status: String)

private val websites = listOf(
    Website("GitHub", "https://github.com/"),
    Website("Google", "https://www.google.com/generate_204"),
    Website("X", "https://x.com/"),
    Website("YouTube", "https://www.youtube.com/"),
)

@Composable
internal fun WebsiteReachability() {
    val context = LocalContext.current.applicationContext
    val connectivity = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf(emptyList<WebsiteResult>()) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) job?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            job?.cancel()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("站点可达性", style = MaterialTheme.typography.titleMedium)
            Text(
                "用当前系统网络检查这四个站点是否返回 HTTPS 响应。使用 VPN 时，结果反映当前 VPN 路径。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                enabled = job == null,
                onClick = {
                    val network = connectivity.activeNetwork
                    path = network?.let { networkLabel(connectivity.getNetworkCapabilities(it)) } ?: "设备未连接网络"
                    results = emptyList()
                    if (network != null) {
                        job = scope.launch {
                            try {
                                results = coroutineScope {
                                    websites.map { site -> async(Dispatchers.IO) { checkWebsite(network, site) } }.awaitAll()
                                }
                            } finally {
                                job = null
                            }
                        }
                    }
                },
            ) { Text(if (job == null) "开始检测" else "正在检测…") }
            path?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            results.forEach { result ->
                Text("${result.website.name} · ${result.status}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun networkLabel(capabilities: NetworkCapabilities?): String = when {
    capabilities == null -> "系统未提供网络类型"
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> "当前网络需要网页认证"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "当前网络 · VPN"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "当前网络 · Wi‑Fi"
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "当前网络 · 移动数据"
    else -> "当前网络 · 系统默认"
}

private fun checkWebsite(network: Network, website: Website): WebsiteResult {
    var connection: HttpURLConnection? = null
    val start = SystemClock.elapsedRealtime()
    val result = try {
        val https = network.openConnection(URL(website.url)) as HttpURLConnection
        connection = https
        https.connectTimeout = 6_000
        https.readTimeout = 6_000
        https.instanceFollowRedirects = false
        https.useCaches = false
        val code = https.responseCode
        val elapsed = SystemClock.elapsedRealtime() - start
        when (code) {
            in 200..299 -> "可达 · HTTP $code · ${elapsed} ms"
            in 300..399 -> "收到重定向 · HTTP $code · ${elapsed} ms"
            in 400..599 -> "服务已响应 · HTTP $code · ${elapsed} ms"
            else -> "未收到有效 HTTP 状态"
        }
    } catch (_: UnknownHostException) {
        "DNS 解析失败"
    } catch (_: SSLException) {
        "TLS 连接失败"
    } catch (_: SocketTimeoutException) {
        "连接或等待响应超时"
    } catch (_: ConnectException) {
        "连接失败"
    } catch (_: IOException) {
        "当前网络不可达或已切换"
    } finally {
        connection?.disconnect()
    }
    return WebsiteResult(website, result)
}
