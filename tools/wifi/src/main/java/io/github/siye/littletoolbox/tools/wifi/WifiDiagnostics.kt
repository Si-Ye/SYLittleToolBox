package io.github.siye.littletoolbox.tools.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.SystemClock
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SCAN_TIMEOUT_MS = 12_000L
private const val RANGING_TIMEOUT_MS = 10_000L
private const val REDACTED_MAC = "02:00:00:00:00:00"

internal data class DeviceState(
    val wifiEnabled: Boolean,
    val locationEnabled: Boolean,
    val scanAlwaysAvailable: Boolean,
    val fineLocationGranted: Boolean,
    val nearbyWifiGranted: Boolean?,
    val rttFeature: Boolean,
    val rttAvailable: Boolean?,
    val ntbInitiator: Boolean?,
    val connection: WifiConnection?,
) {
    val scanBlock: String?
        get() = when {
            !wifiEnabled -> "请在系统设置中开启 Wi‑Fi"
            !locationEnabled -> "请在系统设置中开启位置服务"
            !fineLocationGranted -> "扫描需要精确定位权限"
            else -> null
        }

    val rangingBlock: String?
        get() = when {
            !rttFeature -> "系统未声明 Wi‑Fi RTT 能力"
            scanBlock != null -> scanBlock
            nearbyWifiGranted == false -> "测距需要附近 Wi‑Fi 设备权限"
            rttAvailable != true -> "系统 RTT 当前不可用"
            else -> null
        }
}

internal data class WifiConnection(
    val name: String?,
    val bssid: String?,
    val frequency: Int,
    val rssi: Int,
    val linkSpeed: Int,
    val security: String?,
    val localIpv4: String?,
)

internal data class AccessPoint(
    val result: ScanResult,
    val bssid: String,
    val name: String,
    val supportsMc: Boolean,
    val supportsAz: Boolean?,
    val seenAt: Long?,
) {
    fun canRange(device: DeviceState) = supportsMc || (supportsAz == true && device.ntbInitiator == true)
}

internal data class ScanSnapshot(val accessPoints: List<AccessPoint>, val message: String)

internal data class RttSample(
    val time: Long,
    val distanceMm: Int? = null,
    val deviationMm: Int? = null,
    val successfulMeasurements: Int? = null,
    val attemptedMeasurements: Int? = null,
    val protocol: String? = null,
    val minNextIntervalMs: Long = 0,
    val failure: String? = null,
    val terminal: Boolean = false,
)

internal class WifiDiagnostics(private val context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)
    private val rttFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
    private val rtt = context.getSystemService(WifiRttManager::class.java)
    private val needsNearbyPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.TIRAMISU
    private var pendingRange: CompletableDeferred<RttSample>? = null

    fun deviceState(): DeviceState {
        val wifiEnabled = wifi?.isWifiEnabled == true
        return DeviceState(
            wifiEnabled = wifiEnabled,
            locationEnabled = location?.isLocationEnabled == true,
            scanAlwaysAvailable = wifi?.isScanAlwaysAvailable == true,
            fineLocationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            nearbyWifiGranted = if (needsNearbyPermission) {
                hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                null
            },
            rttFeature = rttFeature,
            rttAvailable = rtt?.isAvailable,
            ntbInitiator = if (Build.VERSION.SDK_INT >= 35) {
                rtt?.rttCharacteristics?.takeIf {
                    it.containsKey(WifiRttManager.CHARACTERISTICS_KEY_BOOLEAN_NTB_INITIATOR)
                }?.getBoolean(WifiRttManager.CHARACTERISTICS_KEY_BOOLEAN_NTB_INITIATOR)
            } else {
                null
            },
            connection = if (wifiEnabled) readConnection() else null,
        )
    }

    fun missingPermissions(): Array<String> = buildList {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (rttFeature && needsNearbyPermission && !hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    fun observeDevice(onStateChanged: () -> Unit): () -> Unit {
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onStateChanged()
        }
        registerReceiver(receiver, filter)
        return { context.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun scan(): ScanSnapshot {
        val manager = wifi ?: return ScanSnapshot(emptyList(), "Wi‑Fi 服务不可用")
        deviceState().scanBlock?.let { return ScanSnapshot(emptyList(), it) }
        val freshSince = SystemClock.elapsedRealtimeNanos() / 1_000L
        val scanCompleted = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                scanCompleted.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
            }
        }
        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        return try {
            val accepted = manager.startScan()
            val updated = if (accepted) {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) { scanCompleted.await() }
            } else {
                null
            }
            val results = withContext(Dispatchers.IO) { manager.scanResults.orEmpty() }
            val fresh = if (updated == true) results.filter { it.timestamp >= freshSince } else emptyList()
            val reason = when {
                !accepted -> "系统未启动扫描，可能达到频率限制或 Wi‑Fi 硬件正忙"
                updated == null -> "等待系统扫描结果超时"
                !updated -> "系统报告扫描未更新，可能达到频率限制"
                fresh.isEmpty() && results.isNotEmpty() -> "系统返回的样本早于本次扫描"
                else -> return ScanSnapshot(toAccessPoints(fresh), "已收到发起扫描后更新的结果")
            }
            val cacheStatus = if (results.isEmpty()) "无可用缓存" else "显示系统缓存"
            ScanSnapshot(toAccessPoints(results), "$reason · $cacheStatus")
        } catch (_: SecurityException) {
            ScanSnapshot(emptyList(), "系统拒绝扫描，请检查精确定位权限和位置服务")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    fun rangingRequest(target: AccessPoint): RangingRequest {
        return RangingRequest.Builder().addAccessPoint(target.result).build()
    }

    @SuppressLint("MissingPermission")
    suspend fun rangeOnce(target: AccessPoint, request: RangingRequest): RttSample {
        val manager = rtt ?: return failure("Wi‑Fi RTT 服务不可用", terminal = true)

        // Android has no public method to cancel a submitted ranging request.
        val previous = pendingRange
        if (previous != null && !previous.isCompleted) {
            val completed = withTimeoutOrNull(RANGING_TIMEOUT_MS) { previous.await() }
            if (completed == null) {
                val sample = failure("等待上一次系统请求结束超时", terminal = true)
                previous.complete(sample)
                return sample
            }
        }
        val state = deviceState()
        state.rangingBlock?.let { return failure(it, terminal = true) }
        if (!target.canRange(state)) return failure("此接入点未声明手机可用的 RTT 响应能力", terminal = true)

        val response = CompletableDeferred<RttSample>()
        pendingRange = response
        return try {
            val requestedAt = SystemClock.elapsedRealtime()
            manager.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    val sample = when (code) {
                        RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE ->
                            failure("系统 RTT 当前不可用", terminal = true)
                        else -> failure("系统拒绝测距请求（错误码 $code）")
                    }
                    response.complete(sample)
                }

                override fun onRangingResults(results: List<RangingResult>) {
                    val result = results.firstOrNull {
                        it.macAddress?.toString().equals(target.bssid, ignoreCase = true)
                    }
                    response.complete(readSample(result, requestedAt))
                }
            })
            withTimeoutOrNull(RANGING_TIMEOUT_MS) { response.await() }
                ?: failure("等待测距结果超时", terminal = true).also { response.complete(it) }
        } catch (_: SecurityException) {
            failure("系统拒绝测距，请检查精确定位和附近 Wi‑Fi 设备权限", terminal = true)
                .also { response.complete(it) }
        } catch (_: IllegalArgumentException) {
            failure("系统不接受此接入点的测距参数，请重新扫描后试试", terminal = true)
                .also { response.complete(it) }
        }
    }

    private fun readSample(result: RangingResult?, requestedAt: Long): RttSample {
        if (result == null) return failure("系统未返回该接入点的样本")
        if (result.status != RangingResult.STATUS_SUCCESS) {
            val incompatible = result.status == RangingResult.STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC
            val reason = if (incompatible) "系统报告接入点缺少兼容的 RTT 响应" else "接入点测距失败"
            return failure("$reason（状态码 ${result.status}）", terminal = incompatible)
        }
        val elapsed = result.rangingTimestampMillis
        val time = fromElapsed(elapsed) ?: return failure("系统未返回有效的测距时间")
        val azMeasurement = Build.VERSION.SDK_INT >= 35 && result.is80211azNtbMeasurement
        val interval = if (Build.VERSION.SDK_INT >= 35 && azMeasurement) {
            result.minTimeBetweenNtbMeasurementsMicros.coerceAtLeast(0) / 1_000 + 1
        } else {
            0
        }
        if (elapsed < requestedAt) {
            return RttSample(
                time = time,
                minNextIntervalMs = interval,
                failure = "系统返回 RTT 缓存，未计入有效样本",
            )
        }
        val protocol = when {
            azMeasurement -> "802.11az NTB"
            Build.VERSION.SDK_INT < 31 -> null
            result.is80211mcMeasurement -> "802.11mc FTM"
            else -> return failure("系统返回单边 RTT，未计入双向测距样本", terminal = true)
        }
        val distance = result.distanceMm
        if (distance < 0) return failure("系统未返回有效距离")
        val attempted = result.numAttemptedMeasurements.takeIf { it > 0 }
        val successful = result.numSuccessfulMeasurements
        return RttSample(
            time = time,
            distanceMm = distance,
            deviationMm = result.distanceStdDevMm.takeIf { successful > 1 && it >= 0 },
            successfulMeasurements = successful.takeIf { attempted != null && it in 0..attempted },
            attemptedMeasurements = attempted,
            protocol = protocol,
            minNextIntervalMs = interval,
        )
    }

    private fun failure(message: String, terminal: Boolean = false) =
        RttSample(time = System.currentTimeMillis(), failure = message, terminal = terminal)

    @Suppress("DEPRECATION")
    private fun toAccessPoints(results: List<ScanResult>): List<AccessPoint> = results
        .sortedWith(compareByDescending<ScanResult> { it.timestamp }.thenByDescending { it.level })
        .distinctBy { it.BSSID?.lowercase(Locale.ROOT) }
        .mapNotNull { result ->
            val bssid = result.BSSID?.takeUnless { it.isBlank() || it == REDACTED_MAC }
                ?: return@mapNotNull null
            AccessPoint(
                result = result,
                bssid = bssid,
                name = result.SSID?.takeIf { it.isNotEmpty() } ?: "隐藏 SSID",
                supportsMc = result.is80211mcResponder,
                supportsAz = if (Build.VERSION.SDK_INT >= 35) result.is80211azNtbResponder else null,
                seenAt = fromElapsed(result.timestamp / 1_000L),
            )
        }
        .sortedWith(compareByDescending<AccessPoint> { it.supportsMc || it.supportsAz == true }
            .thenByDescending { it.result.level })

    @Suppress("DEPRECATION")
    private fun readConnection(): WifiConnection? {
        val info = try {
            wifi?.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (info.networkId < 0 && info.supplicantState != SupplicantState.COMPLETED) return null
        return WifiConnection(
            name = info.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }?.removeSurrounding("\"")
                ?.takeUnless { it.isEmpty() },
            bssid = info.bssid?.takeUnless { it == REDACTED_MAC },
            frequency = info.frequency,
            rssi = info.rssi,
            linkSpeed = info.linkSpeed,
            security = if (Build.VERSION.SDK_INT >= 31) securityName(info.currentSecurityType) else null,
            localIpv4 = info.ipAddress.takeIf { it != 0 }?.let { address ->
                (0..3).joinToString(".") { ((address ushr (it * 8)) and 0xff).toString() }
            },
        )
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }
}

private fun fromElapsed(elapsedMs: Long): Long? = elapsedMs.takeIf { it > 0L }
    ?.let { System.currentTimeMillis() - (SystemClock.elapsedRealtime() - it) }

private fun securityName(securityType: Int): String? = when (securityType) {
    WifiInfo.SECURITY_TYPE_OPEN -> "开放网络"
    WifiInfo.SECURITY_TYPE_WEP -> "WEP"
    WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2 个人"
    WifiInfo.SECURITY_TYPE_EAP -> "WPA/WPA2 企业"
    WifiInfo.SECURITY_TYPE_SAE -> "WPA3 个人"
    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3 企业"
    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3 企业 192 位"
    WifiInfo.SECURITY_TYPE_OWE -> "OWE"
    WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI-PSK"
    WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI-CERT"
    WifiInfo.SECURITY_TYPE_UNKNOWN -> null
    else -> "系统类型 $securityType"
}
