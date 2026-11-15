package io.github.siye.littletoolbox.tools.trail

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.siye.littletoolbox.core.tool.ToolCategory
import io.github.siye.littletoolbox.core.tool.ToolInfo
import io.github.siye.littletoolbox.core.tool.ToolModule
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TrailTool : ToolModule {
    override val info = ToolInfo(
        id = "trail",
        name = "回迹",
        category = ToolCategory.TRAVEL,
        description = "离线记录路线，标记停车点与车位。",
        keywords = listOf("停车", "找车", "路线", "轨迹", "车位", "GPS", "定位"),
        help = listOf(
            "开启系统定位并允许精确位置权限。在回迹页面开始记录后，切到后台或锁屏也可以继续记录。",
            "在回迹页面或记录通知中停止。记录通知需要系统通知权限。每次开始会替换上一条路线，停车资料单独保存。",
            "在停车处点「标记停车点」。可补充楼层、车位和照片。定位不佳时也可先保存停车资料。",
            "离线轨迹图可双指缩放。直线距离由两个定位计算。信号中断会在路线留下断点，地下请以楼层、车位和照片为准。",
        ),
    )

    @Composable
    override fun Content(initialText: String?) {
        TrailScreen()
    }
}

private enum class TrailConfirmation { REPLACE_ROUTE, CLEAR_ROUTE, CLEAR_PARKING }

@Composable
private fun TrailScreen() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val store = remember(context.applicationContext) { TrailStore.get(context) }
    val location = remember(context.applicationContext) { TrailLocation(context) }
    val recording by TrailRecordingService.state.collectAsStateWithLifecycle()
    val revision by store.changes.collectAsStateWithLifecycle()
    var snapshot by remember { mutableStateOf<TrailSnapshot?>(null) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var precise by remember { mutableStateOf(location.hasPrecisePermission) }
    var gps by remember { mutableStateOf(location.gpsEnabled) }
    var foregroundFix by remember { mutableStateOf<TrailFix?>(null) }
    var elapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<TrailConfirmation?>(null) }
    var askedLocation by rememberSaveable { mutableStateOf(false) }

    fun refreshLocationState() {
        precise = location.hasPrecisePermission
        gps = location.gpsEnabled
        elapsed = SystemClock.elapsedRealtime()
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshLocationState()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "回迹可以显示记录通知" else "可返回回迹页面停止记录"
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    refreshLocationState()
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    foregroundFix = null
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        while (resumed) {
            refreshLocationState()
            delay(1_000L)
        }
    }
    DisposableEffect(location, resumed, precise, gps, recording.active) {
        val stop = if (resumed && precise && gps && !recording.active) {
            try {
                location.listen(
                    onFix = { fix ->
                        elapsed = SystemClock.elapsedRealtime()
                        foregroundFix = fix
                    },
                    onDisabled = {
                        foregroundFix = null
                        refreshLocationState()
                    },
                )
            } catch (_: SecurityException) {
                refreshLocationState()
                null
            } catch (_: IllegalArgumentException) {
                message = "此设备当前无法使用 GPS"
                null
            }
        } else null
        onDispose { stop?.invoke() }
    }
    LaunchedEffect(store, revision) {
        try {
            snapshot = store.read()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            message = "无法读取本地回迹资料"
        }
    }

    fun mutate(failure: String, success: String, block: suspend () -> Unit) {
        if (busy) return
        message = null
        busy = true
        scope.launch {
            try {
                block()
                snapshot = store.read()
                message = success
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = failure
            } finally {
                busy = false
            }
        }
    }

    fun start() {
        message = null
        if (!resumed || !location.hasPrecisePermission || !location.gpsEnabled || !TrailRecordingService.start(context)) {
            message = "无法启动记录，请在回迹页面开启精确位置权限和系统定位后重试"
        }
    }

    val route = snapshot?.route
    val parking = snapshot?.parking
    val isCurrentRoute = route?.startedAt == recording.startedAt
    val points = route?.points.orEmpty()
    val lastFix = if (recording.active) recording.latest else foregroundFix
    val current = lastFix?.takeIf {
        resumed && precise && gps && !recording.gpsInterrupted && elapsed - it.elapsedMs in 0..FIX_EXPIRY_MS
    }
    val lastKnown = lastFix ?: points.lastOrNull()?.fix
    val distance = remember(parking?.fix, current) {
        val destination = parking?.fix
        if (destination != null && current != null) {
            val result = FloatArray(1)
            Location.distanceBetween(current.latitude, current.longitude, destination.latitude, destination.longitude, result)
            result[0]
        } else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TrailCard("路线记录") {
                val status = when {
                    recording.status != null -> recording.status.orEmpty()
                    recording.active -> "正在记录"
                    route?.endedAt != null -> "路线已保存"
                    route != null -> "上次记录已中断 · 已写入的轨迹仍保留"
                    snapshot == null && message == null -> "正在读取本地路线"
                    else -> "尚未记录"
                }
                Text(status, style = MaterialTheme.typography.titleSmall)
                if (route != null && (!recording.active || isCurrentRoute)) {
                    TrailDetail("${trailTime(route.startedAt)} 开始 · ${points.size} 个定位点")
                }
                when {
                    !precise -> {
                        TrailDetail("记录路线与标记坐标需要精确位置权限。")
                        if (!recording.active) {
                            Button(onClick = {
                                askedLocation = true
                                permission.launch(arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ))
                            }) { Text("允许精确定位") }
                            if (askedLocation) TextButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", context.packageName, null)),
                                )
                            }) { Text("打开应用权限设置") }
                        }
                    }
                    !gps -> {
                        TrailDetail("系统定位已关闭")
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                            Text("打开位置设置")
                        }
                    }
                    current != null -> TrailDetail("定位精度 · 约 ${trailDistance(current.accuracy)}")
                    lastKnown == null -> TrailDetail("等待有效定位")
                    else -> TrailDetail("等待有效定位恢复")
                }
                if (current == null && lastKnown != null) {
                    TrailDetail("最近有效定位 · ${trailTime(lastKnown.time)} · 精度约 ${trailDistance(lastKnown.accuracy)}")
                }
                if (recording.active) {
                    Button(
                        enabled = !recording.saving,
                        onClick = { TrailRecordingService.stop(context) },
                    ) { Text(if (recording.saving) "正在保存…" else "停止记录") }
                } else if (precise && gps) {
                    Button(
                        enabled = !busy && snapshot != null,
                        onClick = { if (route == null) start() else confirmation = TrailConfirmation.REPLACE_ROUTE },
                    ) { Text("开始记录") }
                }
                if (Build.VERSION.SDK_INT >= 33 &&
                    !context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
                ) {
                    TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("允许显示记录通知")
                    }
                }
                message?.let { TrailDetail(it) }
            }
        }
        item {
            TrailCard("离线轨迹") {
                TrailMap(points, parking?.fix, current, route?.startedAt)
                if (route != null && !recording.active) {
                    TextButton(enabled = !busy, onClick = { confirmation = TrailConfirmation.CLEAR_ROUTE }) {
                        Text("清除轨迹")
                    }
                }
            }
        }
        item {
            TrailCard("停车点") {
                if (parking == null) {
                    TrailDetail("尚未标记停车点")
                } else {
                    TrailDetail("标记于 ${trailTime(parking.markedAt)}")
                    if (parking.fix != null) {
                        distance?.let {
                            Text("约 ${trailDistance(it)}", style = MaterialTheme.typography.headlineSmall)
                            TrailDetail("到停车点的直线距离")
                        } ?: TrailDetail("等待当前位置以计算直线距离")
                        TrailDetail("停车点精度 · 约 ${trailDistance(parking.fix.accuracy)}")
                    } else {
                        TrailDetail("已保存停车资料，坐标待标记")
                    }
                    parking.floor.takeIf(String::isNotBlank)?.let { TrailDetail("楼层 · $it") }
                    parking.space.takeIf(String::isNotBlank)?.let { TrailDetail("车位 · $it") }
                    parking.photo?.let { ParkingPhoto(store, it) }
                }
                if (current != null) {
                    Button(enabled = !busy && snapshot != null, onClick = {
                        val markedAt = System.currentTimeMillis()
                        val newSpot = (parking ?: ParkingSpot(markedAt)).copy(markedAt = markedAt, fix = current)
                        mutate("无法保存停车点，请重试", "已标记停车点") { store.saveParking(newSpot, null) }
                    }) { Text(if (parking?.fix == null) "标记停车点" else "更新停车坐标") }
                }
                OutlinedButton(enabled = !busy && snapshot != null, onClick = {
                    message = null
                    editor = true
                }) {
                    Text(if (parking == null) "填写停车资料" else "编辑停车资料")
                }
                if (parking != null) {
                    TextButton(enabled = !busy, onClick = { confirmation = TrailConfirmation.CLEAR_PARKING }) {
                        Text("清除停车点")
                    }
                }
            }
        }
    }

    if (editor && snapshot != null) {
        ParkingEditor(parking, busy, message, onDismiss = { editor = false }) { floor, space, photo, keepPhoto ->
            val spot = (parking ?: ParkingSpot(System.currentTimeMillis())).copy(
                floor = floor,
                space = space,
                photo = parking?.photo.takeIf { keepPhoto },
            )
            mutate("无法保存停车资料，请检查选中的照片后重试", "停车资料已保存") {
                store.saveParking(spot, photo)
                editor = false
            }
        }
    }

    confirmation?.let { action ->
        val title = when (action) {
            TrailConfirmation.REPLACE_ROUTE -> "替换上一次路线？"
            TrailConfirmation.CLEAR_ROUTE -> "清除本机保存的路线？"
            TrailConfirmation.CLEAR_PARKING -> "清除停车点和附带的照片？"
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    when (action) {
                        TrailConfirmation.REPLACE_ROUTE -> start()
                        TrailConfirmation.CLEAR_ROUTE -> mutate("无法清除轨迹，请重试", "轨迹已清除") {
                            store.clearRoute()
                        }
                        TrailConfirmation.CLEAR_PARKING -> mutate("无法清除停车点，请重试", "停车点已清除") {
                            store.clearParking()
                        }
                    }
                }) { Text(if (action == TrailConfirmation.REPLACE_ROUTE) "开始新记录" else "清除") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TrailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun TrailDetail(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ParkingPhoto(store: TrailStore, name: String) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, name) {
        value = null
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(store.photoFile(name).absolutePath)?.asImageBitmap()
        }
    }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = "停车点照片",
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun ParkingEditor(
    parking: ParkingSpot?,
    saving: Boolean,
    message: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, Uri?, Boolean) -> Unit,
) {
    var floor by rememberSaveable { mutableStateOf(parking?.floor.orEmpty()) }
    var space by rememberSaveable { mutableStateOf(parking?.space.orEmpty()) }
    var photo by rememberSaveable { mutableStateOf<Uri?>(null) }
    var keepPhoto by rememberSaveable { mutableStateOf(parking?.photo != null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected ->
        if (selected != null) photo = selected
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("停车资料") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = floor,
                    onValueChange = { floor = it.take(50) },
                    label = { Text("楼层") },
                    supportingText = { Text("例如 B2") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = space,
                    onValueChange = { space = it.take(80) },
                    label = { Text("车位") },
                    supportingText = { Text("例如 A 区 126 号") },
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(enabled = !saving, onClick = {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (photo != null || keepPhoto) "更换照片" else "添加照片") }
                if (photo != null || keepPhoto) {
                    TrailDetail(if (photo != null) "已选中一张照片" else "已附一张照片")
                    TextButton(enabled = !saving, onClick = {
                        photo = null
                        keepPhoto = false
                    }) { Text("移除照片") }
                }
                message?.let { TrailDetail(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(floor.trim(), space.trim(), photo, keepPhoto) },
                enabled = !saving && (parking != null || floor.isNotBlank() || space.isNotBlank() || photo != null),
            ) { Text(if (saving) "正在保存…" else "保存") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } },
    )
}

private fun trailTime(time: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT, DateFormat.MEDIUM,
).format(Date(time))

private fun trailDistance(meters: Float): String = if (meters < 1_000f) {
    String.format(Locale.CHINA, "%.0f 米", meters)
} else {
    String.format(Locale.CHINA, "%.2f 公里", meters / 1_000)
}
