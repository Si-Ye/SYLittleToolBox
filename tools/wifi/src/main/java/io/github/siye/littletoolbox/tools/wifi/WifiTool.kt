package io.github.siye.littletoolbox.tools.wifi

import android.content.Intent
import android.net.Uri
import android.net.wifi.ScanResult
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.siye.littletoolbox.core.tool.ToolCategory
import io.github.siye.littletoolbox.core.tool.ToolInfo
import io.github.siye.littletoolbox.core.tool.ToolModule
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SAMPLE_INTERVAL_MS = 1_000L
private const val SAMPLE_WINDOW = 20

object WifiTool : ToolModule {
    override val info = ToolInfo(
        id = "wifi",
        name = "Wi‑Fi 探查",
        category = ToolCategory.NETWORK,
        description = "查看网络连接、站点可达性和接入点测距。",
        keywords = listOf("wifi", "无线网络", "接入点", "RTT", "FTM", "测距", "路由器", "GitHub", "Google", "YouTube"),
        help = listOf(
            "开启 Wi‑Fi 和位置服务，按提示授权后，点「扫描接入点」。",
            "在支持 Wi‑Fi RTT 的接入点下点「开始测距」。切换到后台或离开页面会停止采样。",
            "接入点的 dBm 是扫描收到的无线信号强度。RTT 测量的是手机到所选接入点的距离。",
            "点「开始检测」检查四个站点的 HTTPS 响应。结果按当前系统网络记录，包括 VPN 的影响。",
        ),
    )

    @Composable
    override fun Content(initialText: String?) {
        WifiDiagnosticsScreen()
    }
}

@Composable
private fun WifiDiagnosticsScreen() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val diagnostics = remember(context.applicationContext) { WifiDiagnostics(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var device by remember(diagnostics) { mutableStateOf(diagnostics.deviceState()) }
    var scan by remember { mutableStateOf(ScanSnapshot(emptyList(), "尚未扫描")) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var rangingJob by remember { mutableStateOf<Job?>(null) }
    var session by remember { mutableStateOf<MeasurementSession?>(null) }
    var permissionRequested by remember { mutableStateOf(false) }
    var waitingForPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        waitingForPermission = false
        device = diagnostics.deviceState()
    }

    fun stopRanging(reason: String) {
        if (rangingJob?.isActive == true) {
            session = session?.copy(message = reason)
            rangingJob?.cancel()
        }
    }

    fun refreshDevice() {
        device = diagnostics.deviceState()
        device.rangingBlock?.let { stopRanging(it) }
    }

    DisposableEffect(diagnostics, lifecycle) {
        val unregister = diagnostics.observeDevice {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshDevice()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> refreshDevice()
                Lifecycle.Event.ON_PAUSE -> {
                    if (scanJob?.isActive == true) {
                        scan = scan.copy(message = "已停止等待扫描。此前的接入点为缓存")
                        scanJob?.cancel()
                    }
                    stopRanging("页面已离开前台，测距已停止")
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            scanJob?.cancel()
            rangingJob?.cancel()
            lifecycle.removeObserver(observer)
            unregister()
        }
    }

    val missingPermissions = diagnostics.missingPermissions()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Section("手机与权限") {
                Detail("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                Detail("FEATURE_WIFI_RTT · ${if (device.rttFeature) "系统已声明" else "系统未声明"}")
                Detail("isAvailable() · ${when (device.rttAvailable) {
                    true -> "当前可用"
                    false -> "当前不可用"
                    null -> "无 RTT 服务"
                }}")
                if (Build.VERSION.SDK_INT >= 35 && device.rttFeature) {
                    Detail("802.11az NTB 发起 · ${when (device.ntbInitiator) {
                        true -> "支持"
                        false -> "不支持"
                        null -> "系统未报告"
                    }}")
                }
                Detail("Wi‑Fi · ${device.wifiEnabled.onOff()}　位置服务 · ${device.locationEnabled.onOff()}")
                Detail("Wi‑Fi 关闭时允许扫描 · ${device.scanAlwaysAvailable.onOff()}")
                Detail("精确定位权限 · ${device.fineLocationGranted.permissionLabel()}")
                device.nearbyWifiGranted?.let { Detail("附近 Wi‑Fi 设备权限 · ${it.permissionLabel()}") }
                if (missingPermissions.isNotEmpty()) {
                    Button(
                        onClick = {
                            permissionRequested = true
                            waitingForPermission = true
                            permissionLauncher.launch(diagnostics.missingPermissions())
                        },
                        enabled = !waitingForPermission && scanJob == null && rangingJob == null,
                    ) { Text("授权 Wi‑Fi 扫描与测距") }
                    if (permissionRequested) {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        }) { Text("打开应用权限设置") }
                    }
                }
                if (!device.wifiEnabled) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }) { Text("打开 Wi‑Fi 设置") }
                }
                if (!device.locationEnabled) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) { Text("打开位置设置") }
                }
            }
        }
        item {
            Section("已连接的 Wi‑Fi") {
                val connection = device.connection
                if (connection == null) {
                    Detail("系统未报告当前 Wi‑Fi 连接")
                } else {
                    Text(connection.name ?: "SSID 未公开", style = MaterialTheme.typography.titleSmall)
                    Detail("BSSID · ${connection.bssid ?: "未公开"}")
                    if (connection.frequency > 0) Detail(channelLabel(connection.frequency))
                    if (connection.rssi > -127) Detail("接入点信号 · ${connection.rssi} dBm")
                    if (connection.linkSpeed > 0) Detail("链路速率 · ${connection.linkSpeed} Mbps")
                    val capabilities = scan.accessPoints.firstOrNull {
                        it.bssid.equals(connection.bssid, ignoreCase = true)
                    }?.result?.capabilities?.takeIf { it.isNotBlank() }
                    Detail("安全类型 · ${connection.security ?: capabilities?.let { "扫描广播 $it" } ?: "系统未报告"}")
                    Detail("本机 IPv4 · ${connection.localIpv4 ?: "系统未报告"}")
                }
                OutlinedButton(onClick = { refreshDevice() }) { Text("刷新连接状态") }
            }
        }
        item { WebsiteReachability() }
        item {
            Section("周围接入点") {
                Detail("列表显示接入点的广播能力与信号强度。测距目标由 BSSID 标识。")
                Button(
                    onClick = {
                        val active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        if (active && !waitingForPermission && scanJob == null && rangingJob == null) {
                            val job = scope.launch(start = CoroutineStart.LAZY) {
                                scan = diagnostics.scan()
                                refreshDevice()
                            }
                            scanJob = job
                            job.invokeOnCompletion { scanJob = null }
                            job.start()
                        }
                    },
                    enabled = device.scanBlock == null && !waitingForPermission && scanJob == null && rangingJob == null,
                ) { Text(if (scanJob == null) "扫描接入点" else "等待系统扫描…") }
                Detail(device.scanBlock ?: scan.message)
                if (scan.accessPoints.isNotEmpty()) {
                    val prefix = if (device.scanBlock == null) "" else "此前扫描 · "
                    Detail("$prefix${scan.accessPoints.size} 个接入点")
                }
                device.rangingBlock?.let { Detail("测距状态 · $it") }
            }
        }
        items(scan.accessPoints, key = { it.bssid }) { target ->
            val running = rangingJob != null
            val targetSession = session?.takeIf { it.bssid == target.bssid }
            val canRange = target.canRange(device)
            Section(target.name) {
                Detail(target.bssid)
                Detail("${target.result.level} dBm · ${channelLabel(target.result.frequency)}")
                Detail("扫描样本 · ${target.seenAt?.let(::timeLabel) ?: "时间未报告"}")
                Detail("802.11mc FTM · ${if (target.supportsMc) "已声明响应" else "未声明响应"}")
                target.supportsAz?.let { Detail("802.11az NTB · ${if (it) "已声明响应" else "未声明响应"}") }
                if (target.supportsAz == true && !canRange) {
                    Detail("此手机未报告 802.11az NTB 发起支持")
                }
                if (targetSession != null && running) {
                    OutlinedButton(onClick = { stopRanging("已停止测距") }) { Text("停止测距") }
                } else if (canRange) {
                    Button(
                        enabled = !running && !waitingForPermission && scanJob == null && device.rangingBlock == null,
                        onClick = {
                            val active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                            if (active && !waitingForPermission && rangingJob == null && scanJob == null) {
                                session = MeasurementSession(target.bssid, "正在测距")
                                val job = scope.launch(start = CoroutineStart.LAZY) {
                                    try {
                                        val request = diagnostics.rangingRequest(target)
                                        while (isActive) {
                                            val sample = diagnostics.rangeOnce(target, request)
                                            session = session?.record(sample)
                                            if (sample.terminal) {
                                                device = diagnostics.deviceState()
                                                session = session?.copy(message = "测距已停止")
                                                break
                                            }
                                            delay(maxOf(SAMPLE_INTERVAL_MS, sample.minNextIntervalMs))
                                        }
                                    } catch (_: IllegalArgumentException) {
                                        session = session?.copy(message = "接入点参数无效，请重新扫描")
                                    }
                                }
                                rangingJob = job
                                job.invokeOnCompletion { rangingJob = null }
                                job.start()
                            }
                        },
                    ) { Text("开始测距") }
                }
                targetSession?.let { MeasurementResults(it) }
            }
        }
    }
}

private data class MeasurementSession(
    val bssid: String,
    val message: String,
    val attempts: Int = 0,
    val successes: Int = 0,
    val lastSuccess: RttSample? = null,
    val recent: List<RttSample> = emptyList(),
) {
    fun record(sample: RttSample): MeasurementSession {
        val isSuccess = sample.distanceMm != null
        return copy(
            attempts = attempts + 1,
            successes = if (isSuccess) successes + 1 else successes,
            lastSuccess = if (isSuccess) sample else lastSuccess,
            recent = (recent + sample).takeLast(SAMPLE_WINDOW),
        )
    }
}

@Composable
private fun MeasurementResults(session: MeasurementSession) {
    Detail("${session.message} · 成功 ${session.successes} / ${session.attempts} 次")
    val latest = session.recent.lastOrNull()
    val currentDistance = latest?.distanceMm
    val lastValid = session.lastSuccess
    if (latest != null && currentDistance != null) {
        Text(meters(currentDistance), style = MaterialTheme.typography.headlineSmall)
        Detail("最近一次测量 · ${timeLabel(latest.time)}")
        latest.protocol?.let { Detail("实际使用 · $it") }
        latest.deviationMm?.let { Detail("此次测量标准差 · ${meters(it)}") }
        latest.successfulMeasurements?.let { Detail("有效帧 · $it / ${latest.attemptedMeasurements}") }
    } else if (latest != null) {
        Detail("最近一次 · ${latest.failure} · ${timeLabel(latest.time)}")
        if (lastValid?.distanceMm != null) {
            Detail("上次有效距离 · ${meters(lastValid.distanceMm)} · ${timeLabel(lastValid.time)}")
        }
    }
    val windowDistances = session.recent.mapNotNull { it.distanceMm }
    if (windowDistances.size >= 2) {
        val min = windowDistances.min()
        val max = windowDistances.max()
        Detail("近 ${session.recent.size} 次 · ${windowDistances.size} 次有效 · 波动 ${meters(min)} 至 ${meters(max)}")
    }
    if (session.recent.size > 1) {
        Detail("最近 ${minOf(5, session.recent.size)} 次采样")
        session.recent.takeLast(5).asReversed().forEach { sample ->
            Detail("${timeLabel(sample.time)} · ${sample.distanceMm?.let(::meters) ?: sample.failure}")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun Boolean.onOff() = if (this) "开启" else "关闭"

private fun Boolean.permissionLabel() = if (this) "已授权" else "未授权"

private fun meters(mm: Int): String = String.format(Locale.CHINA, "%.2f 米", mm / 1000.0)

private fun timeLabel(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(epochMs))

private fun channelLabel(frequency: Int): String {
    val band = when (frequency) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        in 57000..71000 -> "60 GHz"
        else -> null
    }
    val channel = when {
        Build.VERSION.SDK_INT >= 31 -> ScanResult.convertFrequencyMhzToChannelIfSupported(frequency)
        frequency == 2484 -> 14
        frequency in 2412..2472 -> (frequency - 2407) / 5
        frequency in 4910..4980 -> (frequency - 4000) / 5
        frequency in 5000..5895 -> (frequency - 5000) / 5
        else -> -1
    }
    return listOfNotNull(band, "$frequency MHz", "信道 $channel".takeIf { channel > 0 }).joinToString(" · ")
}
