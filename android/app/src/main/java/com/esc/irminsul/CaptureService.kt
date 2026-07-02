package com.esc.irminsul

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CaptureStatus {
    private const val TAG = "CaptureStatus"

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _itemsLoaded = MutableStateFlow(false)
    val itemsLoaded: StateFlow<Boolean> = _itemsLoaded.asStateFlow()

    private val _charactersLoaded = MutableStateFlow(false)
    val charactersLoaded: StateFlow<Boolean> = _charactersLoaded.asStateFlow()

    private val _weaponsLoaded = MutableStateFlow(false)
    val weaponsLoaded: StateFlow<Boolean> = _weaponsLoaded.asStateFlow()

    private val _achievementsLoaded = MutableStateFlow(false)
    val achievementsLoaded: StateFlow<Boolean> = _achievementsLoaded.asStateFlow()

    private val _artifactsCount = MutableStateFlow(0)
    val artifactsCount: StateFlow<Int> = _artifactsCount.asStateFlow()

    private val _charactersCount = MutableStateFlow(0)
    val charactersCount: StateFlow<Int> = _charactersCount.asStateFlow()

    private val _weaponsCount = MutableStateFlow(0)
    val weaponsCount: StateFlow<Int> = _weaponsCount.asStateFlow()

    private val _achievementsCount = MutableStateFlow(0)
    val achievementsCount: StateFlow<Int> = _achievementsCount.asStateFlow()

    fun updateCapturingStatus(running: Boolean) {
        _isCapturing.value = running
        Log.d(TAG, "Capture status updated: $running")
    }

    fun updateParsingProgress(
        itemsLoaded: Boolean,
        charactersLoaded: Boolean,
        weaponsLoaded: Boolean,
        achievementsLoaded: Boolean,
        artifactsCount: Int,
        charactersCount: Int,
        weaponsCount: Int,
        achievementsCount: Int
    ) {
        _itemsLoaded.value = itemsLoaded
        _charactersLoaded.value = charactersLoaded
        _weaponsLoaded.value = weaponsLoaded
        _achievementsLoaded.value = achievementsLoaded
        _artifactsCount.value = artifactsCount
        _charactersCount.value = charactersCount
        _weaponsCount.value = weaponsCount
        _achievementsCount.value = achievementsCount
    }

    fun resetParsingProgress() {
        _itemsLoaded.value = false
        _charactersLoaded.value = false
        _weaponsLoaded.value = false
        _achievementsLoaded.value = false
        _artifactsCount.value = 0
        _charactersCount.value = 0
        _weaponsCount.value = 0
        _achievementsCount.value = 0
    }

    val allDataLoaded: Boolean
        get() = _itemsLoaded.value && _charactersLoaded.value && _weaponsLoaded.value && _achievementsLoaded.value
}

class CaptureService : VpnService() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "com.esc.irminsul.START_CAPTURE"
        const val ACTION_STOP = "com.esc.irminsul.STOP_CAPTURE"
        const val NOTIFICATION_CHANNEL_COMPLETE_ID = "irminsul_complete_v2"
        const val NOTIFICATION_ID_COMPLETE = 2
        private const val NOTIFICATION_CHANNEL_ID = "irminsul_capture"
        private const val NOTIFICATION_ID = 1

        private const val VPN_MTU = 1500
        private const val VPN_IP4_ADDRESS = "10.215.173.1"
        private const val VPN_IP4_PREFIX = 30
        private const val VPN_DNS_SERVER = "10.215.173.2"
        private const val VPN_IP6_ADDRESS = "fd00:2:fd00:1:fd00:1:fd00:1"
        private const val VPN_IP6_PREFIX = 128
        private const val VPN_IP6_DNS_SERVER = "fd00:2:fd00:1:fd00:1:fd00:2"

        private val TARGET_PACKAGES = listOf(
            "com.miHoYo.GenshinImpact",
            "com.miHoYo.Yuanshen",
            "com.miHoYo.ys.bilibili"
        )

        private val FALLBACK_DNS_LIST = listOf("223.5.5.5", "119.29.29.29", "114.114.114.114")

        @Volatile
        private var _isRunning = false
        val isRunning: Boolean get() = _isRunning

        @Volatile
        private var _packetQueue: LinkedBlockingQueue<ByteArray>? = null

        @Synchronized
        fun setPacketQueue(queue: LinkedBlockingQueue<ByteArray>?) {
            _packetQueue = queue
        }

        @Synchronized
        fun offerPacket(packetData: ByteArray) {
            _packetQueue?.offer(packetData)
        }

        val packetQueue: LinkedBlockingQueue<ByteArray>?
            @Synchronized
            get() = _packetQueue

        fun showCompletionNotification(
            context: Context,
            charactersCount: Int,
            artifactsCount: Int,
            weaponsCount: Int,
            achievementsCount: Int
        ) {
            Log.d(TAG, "showCompletionNotification: chars=$charactersCount artifacts=$artifactsCount weapons=$weaponsCount achievements=$achievementsCount")

            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager == null) {
                Log.e(TAG, "NotificationManager is null")
                return
            }

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_COMPLETE_ID,
                context.getString(R.string.notification_channel_complete_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_complete_desc)
                setShowBadge(true)
                enableLights(true)
                lightColor = Color.GREEN
                setBypassDnd(false)
            }
            manager.createNotificationChannel(channel)

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val contentPendingIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val content = context.getString(R.string.notification_complete_content, charactersCount, artifactsCount, weaponsCount, achievementsCount)

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_COMPLETE_ID)
                .setContentTitle(context.getString(R.string.notification_complete_title))
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(contentPendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            try {
                manager.notify(NOTIFICATION_ID_COMPLETE, notification)
                Log.d(TAG, "Completion notification sent, id=$NOTIFICATION_ID_COMPLETE, fullScreenIntent=$fullScreenPendingIntent")
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to post notification - permission missing?", e)
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var bytesSent: Long = 0

    @Volatile
    private var bytesReceived: Long = 0

    @Volatile
    private var numConnections: Int = 0

    private external fun nativeRunPacketLoop(tunfd: Int)
    private external fun nativeStopCapture()
    private external fun nativeSetDnsServer(dnsIp: String, dnsPort: Int, ipver: Int)

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("capture")
        Log.d(TAG, "CaptureService created, native library loaded")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (_isRunning) {
            Log.w(TAG, "Capture already running")
            return
        }

        createNotificationChannel()
        val notification = buildNotification(0, 0, 0)
        startForeground(NOTIFICATION_ID, notification)

        val realDnsV4 = getSystemDnsServerV4()
        val realDnsV6 = getSystemDnsServerV6()
        val hasIPv6 = hasIPv6Connectivity()
        val privateDnsMode = getPrivateDnsMode()
        val networkType = getNetworkType()
        Log.d(TAG, "=== VPN Startup Diagnostics ===")
        Log.d(TAG, "Network type: $networkType")
        Log.d(TAG, "System DNS v4: $realDnsV4, v6: $realDnsV6")
        Log.d(TAG, "IPv6 connectivity: $hasIPv6")
        Log.d(TAG, "Private DNS mode: $privateDnsMode")
        Log.d(TAG, "MTU: $VPN_MTU")

        val builder = Builder()
        builder.setMtu(VPN_MTU)
        builder.addAddress(VPN_IP4_ADDRESS, VPN_IP4_PREFIX)
        builder.addRoute("0.0.0.0", 1)
        builder.addRoute("128.0.0.0", 1)
        builder.addDnsServer(VPN_DNS_SERVER)

        if (hasIPv6) {
            builder.addAddress(VPN_IP6_ADDRESS, VPN_IP6_PREFIX)
            builder.addRoute("2000::", 3)
            builder.addRoute("fc00::", 7)
            builder.addDnsServer(VPN_IP6_DNS_SERVER)
        } else {
            Log.d(TAG, "No IPv6 connectivity, skipping IPv6 VPN configuration")
        }

        for (pkg in TARGET_PACKAGES) {
            try {
                builder.addAllowedApplication(pkg)
                Log.d(TAG, "Added allowed application: $pkg")
            } catch (e: Exception) {
                Log.w(TAG, "Package not found, skipping: $pkg")
            }
        }

        builder.setSession("Irminsul")

        val configureIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        builder.setConfigureIntent(PendingIntent.getActivity(
            this, 0, configureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }

        _isRunning = true
        CaptureStatus.updateCapturingStatus(true)
        Log.d(TAG, "VPN interface established, starting capture")

        val tunfd = vpnInterface!!.fd
        Thread {
            nativeSetDnsServer(realDnsV4, 53, 4)
            if (hasIPv6 && realDnsV6 != null) {
                nativeSetDnsServer(realDnsV6, 53, 6)
            }
            nativeRunPacketLoop(tunfd)
        }.start()
    }

    fun stopCapture() {
        if (!_isRunning) return

        Log.d(TAG, "Stopping capture")
        _isRunning = false

        try {
            nativeStopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native capture", e)
        }

        Thread {
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {}

            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing VPN interface", e)
            }
            vpnInterface = null

            setPacketQueue(null)
            CaptureStatus.updateCapturingStatus(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.d(TAG, "Capture stopped")
        }.start()
    }

    fun onPacketCaptured(packetData: ByteArray) {
        offerPacket(packetData)
    }

    fun onCaptureStats(sent: Long, received: Long, connections: Int) {
        bytesSent = sent
        bytesReceived = received
        numConnections = connections
        mainHandler.post { updateNotification() }
    }

    fun protectSocket(fd: Int): Boolean {
        return try {
            protect(fd)
        } catch (e: Exception) {
            Log.e(TAG, "protect() failed for fd=$fd", e)
            false
        }
    }

    private fun getSystemDnsServerV4(): String {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network != null) {
                val lp = cm.getLinkProperties(network)
                if (lp != null) {
                    for (addr in lp.dnsServers) {
                        if (addr is Inet4Address) {
                            val ip = addr.hostAddress
                            if (ip != null) {
                                Log.d(TAG, "Found system IPv4 DNS: $ip")
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get system IPv4 DNS", e)
        }

        for (dns in FALLBACK_DNS_LIST) {
            Log.w(TAG, "No system IPv4 DNS found, trying fallback: $dns")
            return dns
        }
        return FALLBACK_DNS_LIST.first()
    }

    private fun getSystemDnsServerV6(): String? {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network != null) {
                val lp = cm.getLinkProperties(network)
                if (lp != null) {
                    for (addr in lp.dnsServers) {
                        if (addr is Inet6Address) {
                            val ip = addr.hostAddress
                            if (ip != null) {
                                Log.d(TAG, "Found system IPv6 DNS: $ip")
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get system IPv6 DNS", e)
        }

        Log.w(TAG, "No system IPv6 DNS found, IPv6 will be disabled")
        return null
    }

    private fun hasIPv6Connectivity(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network != null) {
                val lp = cm.getLinkProperties(network)
                if (lp != null) {
                    return lp.linkAddresses.any { it.address is Inet6Address && !it.address.isLinkLocalAddress }
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check IPv6 connectivity", e)
            false
        }
    }

    private fun getPrivateDnsMode(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                if (network != null) {
                    val lp = cm.getLinkProperties(network)
                    if (lp != null) {
                        return when {
                            lp.privateDnsServerName != null -> "strict (${lp.privateDnsServerName})"
                            lp.isPrivateDnsActive -> "opportunistic"
                            else -> "off"
                        }
                    }
                }
            }
            "unknown"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun getNetworkType(): String {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network != null) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null) {
                    return when {
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        else -> "Other"
                    }
                }
            }
            "No active network"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun updateNotification() {
        if (!_isRunning) return
        val notification = buildNotification(bytesSent, bytesReceived, numConnections)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_capture_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_capture_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(sent: Long, received: Long, connections: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalBytes = sent + received
        val bytesStr = formatBytes(totalBytes)
        val text = "↑${formatBytes(sent)} ↓${formatBytes(received)} | ${getString(R.string.notification_capture_connections, connections)}"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Irminsul - $bytesStr")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024))
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked")
        stopCapture()
        super.onRevoke()
    }
}
