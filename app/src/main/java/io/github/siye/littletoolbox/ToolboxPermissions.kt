package io.github.siye.littletoolbox

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

private enum class ToolboxPermission(
    val permission: String,
    val title: String,
    val usage: String,
    val explanation: String,
    val minSdk: Int = Build.VERSION_CODES.P,
    val runtime: Boolean = true,
    val requiredFeature: String? = null,
) {
    PRECISE_LOCATION(
        Manifest.permission.ACCESS_FINE_LOCATION,
        "精确位置",
        "Wi‑Fi 探查与回迹",
        "Wi‑Fi 探查需要精确位置才能读取附近接入点及测距。回迹需要精确位置来记录 GPS 路线并显示当前位置。",
    ),
    NEARBY_WIFI(
        Manifest.permission.NEARBY_WIFI_DEVICES,
        "附近 Wi‑Fi 设备",
        "Wi‑Fi 探查 · RTT 测距",
        "在支持 Wi‑Fi RTT 的 Android 13 及以上设备中，Wi‑Fi 探查用此权限与所选接入点测距。",
        minSdk = Build.VERSION_CODES.TIRAMISU,
        requiredFeature = PackageManager.FEATURE_WIFI_RTT,
    ),
    NOTIFICATIONS(
        Manifest.permission.POST_NOTIFICATIONS,
        "通知",
        "回迹 · 记录状态与停止入口",
        "允许通知后，回迹会在记录期间显示状态，可从通知打开一袖或停止记录。",
        minSdk = Build.VERSION_CODES.TIRAMISU,
    ),
    WIFI_STATE(
        Manifest.permission.ACCESS_WIFI_STATE,
        "读取 Wi‑Fi 状态",
        "Wi‑Fi 探查 · 连接与扫描结果",
        "Wi‑Fi 探查用此权限读取 Wi‑Fi 开关、当前连接和接入点扫描结果。Android 安装时授予此权限，无需手动申请。",
        runtime = false,
    ),
    WIFI_SCAN(
        Manifest.permission.CHANGE_WIFI_STATE,
        "发起 Wi‑Fi 扫描",
        "Wi‑Fi 探查 · 发现接入点",
        "Wi‑Fi 探查用此权限请系统刷新附近的接入点。Android 安装时授予此权限，无需手动申请。",
        runtime = false,
    ),
    NETWORK_STATE(
        Manifest.permission.ACCESS_NETWORK_STATE,
        "读取网络状态",
        "Wi‑Fi 探查 · 测试所用网络",
        "显示检测使用 Wi‑Fi、移动数据还是 VPN。Android 安装时授予此权限。",
        runtime = false,
    ),
    INTERNET(
        Manifest.permission.INTERNET,
        "访问网络",
        "Wi‑Fi 探查 · 站点可达性",
        "点击「开始检测」后向选定站点发起 HTTPS 请求。Android 安装时授予此权限。",
        runtime = false,
    ),
    FOREGROUND_SERVICE(
        Manifest.permission.FOREGROUND_SERVICE,
        "前台服务",
        "回迹 · 锁屏时继续记录",
        "手动开始回迹后，Android 用前台服务维持路线记录。安装时授予此权限。",
        runtime = false,
    ),
    FOREGROUND_LOCATION(
        Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        "前台服务定位",
        "回迹 · 记录 GPS 路线",
        "Android 14 及以上要求前台服务具有此权限才能继续读取位置。安装时授予此权限。",
        minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        runtime = false,
    );

    fun requestPermissions(): Array<String> = when (this) {
        PRECISE_LOCATION -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, permission)
        else -> arrayOf(permission)
    }
}

private enum class PermissionStatus(
    val label: String,
    val canRequest: Boolean = false,
    val needsSettings: Boolean = false,
) {
    GRANTED("已授权"),
    SYSTEM_GRANTED("系统已授予"),
    SYSTEM_NOT_GRANTED("系统未授予"),
    NOT_NEEDED("此设备无需授权"),
    NOT_GRANTED("未授权", canRequest = true),
    DENIED("已拒绝", canRequest = true),
    APPROXIMATE("仅大致位置", canRequest = true),
    APPROXIMATE_SETTINGS("仅大致位置", needsSettings = true),
    SETTINGS_ONLY("需到系统设置授权", needsSettings = true),
}

private class PermissionAccess(private val activity: Activity) {
    private val preferences = activity.getSharedPreferences("toolbox_permissions", Context.MODE_PRIVATE)
    val permissions = ToolboxPermission.entries.filter { Build.VERSION.SDK_INT >= it.minSdk }
    var setupSeen: Boolean
        get() = preferences.getBoolean("setup_seen", false)
        set(value) = preferences.edit().putBoolean("setup_seen", value).apply()

    fun hasPermission(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun status(item: ToolboxPermission): PermissionStatus {
        if (item.requiredFeature?.let(activity.packageManager::hasSystemFeature) == false) {
            return PermissionStatus.NOT_NEEDED
        }
        if (hasPermission(item.permission)) {
            clearDenial(item)
            return if (item.runtime) PermissionStatus.GRANTED else PermissionStatus.SYSTEM_GRANTED
        }
        if (!item.runtime) return PermissionStatus.SYSTEM_NOT_GRANTED

        val canRetry = activity.shouldShowRequestPermissionRationale(item.permission)
        val needsSettings = !canRetry && preferences.getBoolean(denialKey(item), false)
        if (item == ToolboxPermission.PRECISE_LOCATION && hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return if (needsSettings) PermissionStatus.APPROXIMATE_SETTINGS else PermissionStatus.APPROXIMATE
        }
        return when {
            needsSettings -> PermissionStatus.SETTINGS_ONLY
            canRetry -> PermissionStatus.DENIED
            else -> PermissionStatus.NOT_GRANTED
        }
    }

    fun recordResult(item: ToolboxPermission, result: Map<String, Boolean>, upgradingLocation: Boolean) {
        if (hasPermission(item.permission)) {
            clearDenial(item)
        } else if (result[item.permission] == false) {
            // A first choice of approximate location still allows a separate precise-location request.
            val justGrantedApproximate = item == ToolboxPermission.PRECISE_LOCATION &&
                !upgradingLocation && hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (justGrantedApproximate) {
                clearDenial(item)
            } else {
                preferences.edit().putBoolean(denialKey(item), true).apply()
            }
        }
    }

    fun openSettings() {
        activity.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null),
            ),
        )
    }

    private fun clearDenial(item: ToolboxPermission) {
        if (preferences.contains(denialKey(item))) preferences.edit().remove(denialKey(item)).apply()
    }

    private fun denialKey(item: ToolboxPermission): String = "denied_${item.permission}"
}

private data class PermissionRequest(
    val inProgress: Boolean,
    val launch: (ToolboxPermission) -> Unit,
)

@Composable
private fun rememberPermissionRequest(access: PermissionAccess, onResult: () -> Unit): PermissionRequest {
    var activeName by rememberSaveable { mutableStateOf<String?>(null) }
    var upgradingLocation by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        ToolboxPermission.entries.firstOrNull { it.name == activeName }?.let {
            access.recordResult(it, result, upgradingLocation)
        }
        activeName = null
        onResult()
    }
    return PermissionRequest(activeName != null) { item ->
        if (activeName == null && access.status(item).canRequest) {
            activeName = item.name
            upgradingLocation = item == ToolboxPermission.PRECISE_LOCATION &&
                access.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            launcher.launch(item.requestPermissions())
        }
    }
}

@Composable
internal fun PermissionSetup(canShow: Boolean = true) {
    val activity = LocalActivity.current ?: return
    val access = remember(activity) { PermissionAccess(activity) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    var showIntro by remember(access) { mutableStateOf(!access.setupSeen) }
    var nextRequest by rememberSaveable { mutableIntStateOf(-1) }
    val request = rememberPermissionRequest(access) {}
    val runtimePermissions = remember(access) { access.permissions.filter { it.runtime } }

    LaunchedEffect(nextRequest, request.inProgress, lifecycleState, canShow) {
        if (nextRequest < 0 || request.inProgress || lifecycleState != Lifecycle.State.RESUMED || !canShow) {
            return@LaunchedEffect
        }
        val item = runtimePermissions.getOrNull(nextRequest)
        if (item == null) {
            nextRequest = -1
        } else {
            nextRequest += 1
            request.launch(item)
        }
    }

    if (!showIntro || !canShow) return
    val awaiting = runtimePermissions.filter { access.status(it).canRequest }
    fun dismiss() {
        access.setupSeen = true
        showIntro = false
    }
    if (awaiting.isEmpty()) {
        LaunchedEffect(Unit) { dismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("设置工具权限") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("继续后，Android 会逐项询问是否允许。也可以稍后在「设置 → 应用权限」处理。")
                awaiting.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.explanation)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                nextRequest = 0
            }) { Text("继续授权") }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("稍后") } },
    )
}

@Composable
internal fun PermissionsPage() {
    val activity = LocalActivity.current ?: return
    val access = remember(activity) { PermissionAccess(activity) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var statuses by remember(access) { mutableStateOf(access.permissions.associateWith(access::status)) }
    fun refresh() {
        statuses = access.permissions.associateWith(access::status)
    }
    val request = rememberPermissionRequest(access, ::refresh)

    DisposableEffect(access, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(LocalPageGutter.current, 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(access.permissions, key = { it.name }) { item ->
            Card(
                onClick = { selectedName = item.name },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                ListItem(
                    headlineContent = { Text(item.title) },
                    supportingContent = { Text(item.usage) },
                    trailingContent = {
                        Text(
                            statuses.getValue(item).label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
    }

    val selected = access.permissions.firstOrNull { it.name == selectedName } ?: return
    val status = statuses.getValue(selected)
    AlertDialog(
        onDismissRequest = { if (!request.inProgress) selectedName = null },
        title = { Text(selected.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(selected.explanation)
                Text(status.label, color = MaterialTheme.colorScheme.primary)
                if (status.needsSettings) {
                    Text("在系统的「应用信息 → 权限」中开启此权限。")
                }
            }
        },
        confirmButton = {
            when {
                status.canRequest -> TextButton(
                    onClick = { request.launch(selected) },
                    enabled = !request.inProgress,
                ) { Text(if (status == PermissionStatus.APPROXIMATE) "申请精确位置" else "申请权限") }
                status.needsSettings || status == PermissionStatus.GRANTED ->
                    TextButton(onClick = access::openSettings) { Text("打开系统设置") }
                else -> TextButton(onClick = { selectedName = null }) { Text("关闭") }
            }
        },
        dismissButton = {
            if (status.canRequest || status.needsSettings || status == PermissionStatus.GRANTED) {
                TextButton(onClick = { selectedName = null }, enabled = !request.inProgress) { Text("关闭") }
            }
        },
    )
}
