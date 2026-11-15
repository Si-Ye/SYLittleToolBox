package io.github.siye.littletoolbox.tools.trail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class TrailRecording(
    val active: Boolean = false,
    val saving: Boolean = false,
    val startedAt: Long? = null,
    val latest: TrailFix? = null,
    val gpsInterrupted: Boolean = false,
    val status: String? = null,
)

class TrailRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var location: TrailLocation
    private lateinit var store: TrailStore
    private lateinit var notifications: NotificationManager
    private var stopLocation: (() -> Unit)? = null
    private var recorder: Job? = null
    private var stopping = false
    private var startedElapsed = 0L
    private val pending = mutableListOf<TrailPoint>()

    override fun onCreate() {
        super.onCreate()
        location = TrailLocation(this)
        store = TrailStore.get(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "回迹路线记录", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (intent?.action != START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (recorder != null) return START_NOT_STICKY

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification("正在启动路线记录"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification("正在启动路线记录"))
            }
        } catch (_: SecurityException) {
            mutableState.value = TrailRecording(status = "无法启动记录，请在应用可见时开启精确位置权限")
            stopSelf()
            return START_NOT_STICKY
        }

        val startedAt = System.currentTimeMillis()
        startedElapsed = SystemClock.elapsedRealtime()
        mutableState.value = TrailRecording(active = true, status = "正在启动路线记录")
        recorder = scope.launch {
            try {
                if (!location.hasPrecisePermission || !location.gpsEnabled) {
                    mutableState.value = TrailRecording(status = "开启精确位置权限和系统定位后可记录")
                    return@launch
                }
                // Subscribe before replacing the prior route so failure to access GPS preserves it.
                stopLocation = location.listen(::recordFix) {
                    mutableState.value = mutableState.value.copy(gpsInterrupted = true)
                }
                store.beginRoute(startedAt)
                mutableState.value = mutableState.value.copy(
                    startedAt = startedAt,
                    status = mutableState.value.status.takeIf { stopping },
                )
                var lastStatus: String? = null
                var nextFlush = 0L
                while (!stopping) {
                    val elapsed = SystemClock.elapsedRealtime()
                    if (elapsed >= nextFlush && pending.isNotEmpty()) {
                        val batch = pending.toList()
                        store.append(batch)
                        pending.subList(0, batch.size).clear()
                        nextFlush = elapsed + 9_000L
                    }
                    val state = mutableState.value
                    val last = state.latest
                    val status = when {
                        !location.gpsEnabled -> "系统定位已关闭，等待可用信号"
                        last == null -> "正在等待有效定位"
                        state.gpsInterrupted || elapsed - last.elapsedMs > FIX_EXPIRY_MS -> "定位中断，等待可用信号"
                        else -> "正在记录路线"
                    }
                    if (status != lastStatus) {
                        notifications.notify(NOTIFICATION_ID, notification(status))
                        lastStatus = status
                    }
                    delay(1_000L)
                }
                store.append(pending.toList(), System.currentTimeMillis())
                mutableState.value = TrailRecording()
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                mutableState.value = TrailRecording(status = "定位权限已变化，记录已中断")
            } catch (_: Exception) {
                mutableState.value = TrailRecording(status = "无法继续记录，已写入的轨迹仍可查看")
            } finally {
                stopLocation?.invoke()
                stopLocation = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun recordFix(fix: TrailFix) {
        if (stopping || fix.elapsedMs < startedElapsed) return
        val state = mutableState.value
        val prior = state.latest
        if (prior != null && fix.elapsedMs <= prior.elapsedMs) return
        val breakBefore = prior == null || state.gpsInterrupted || fix.elapsedMs - prior.elapsedMs > FIX_EXPIRY_MS
        pending.add(TrailPoint(fix, breakBefore))
        mutableState.value = state.copy(latest = fix, gpsInterrupted = false)
    }

    private fun requestStop() {
        if (recorder == null) {
            stopSelf()
            return
        }
        stopping = true
        mutableState.value = mutableState.value.copy(saving = true, status = "正在保存路线")
    }

    private fun notification(status: String): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, pendingFlags)
        }
        val stop = PendingIntent.getService(this, 1, command(this, STOP), pendingFlags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.trail_ic_location)
            .setContentTitle("回迹")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "停止记录", stop).build())
            .build()
    }

    override fun onDestroy() {
        stopLocation?.invoke()
        scope.cancel()
        if (mutableState.value.active) mutableState.value = TrailRecording(status = "记录已中断")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "trail_recording"
        private const val NOTIFICATION_ID = 7301
        private const val START = "io.github.siye.littletoolbox.trail.START"
        private const val STOP = "io.github.siye.littletoolbox.trail.STOP"
        private val mutableState = MutableStateFlow(TrailRecording())
        internal val state = mutableState.asStateFlow()

        internal fun start(context: Context): Boolean {
            if (mutableState.value.active) return true
            return try {
                context.startForegroundService(command(context, START))
                mutableState.value = TrailRecording(active = true, status = "正在启动路线记录")
                true
            } catch (_: IllegalStateException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

        internal fun stop(context: Context) {
            context.startService(command(context, STOP))
        }

        private fun command(context: Context, action: String) =
            Intent(context, TrailRecordingService::class.java).setAction(action)
    }
}
