package com.esc.irminsul

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

data class UiState(
    val isCapturing: Boolean = false,
    val isPendingStateChange: Boolean = false,
    val showLaunchGameDialog: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val permissionState: PermissionHelper.PermissionState = PermissionHelper.PermissionState(true, true, false, true, false),
    val itemsLoaded: Boolean = false,
    val charactersLoaded: Boolean = false,
    val weaponsLoaded: Boolean = false,
    val achievementsLoaded: Boolean = false,
    val logs: List<String> = emptyList(),
    val canExportGood: Boolean = false,
    val canExportAchievements: Boolean = false,
    val toastMessage: String = "",
    val artifactsCount: Int = 0,
    val charactersCount: Int = 0,
    val materialsCount: Int = 0,
    val weaponsCount: Int = 0,
    val achievementsCount: Int = 0,
    val fakeInitialize4thLine: Boolean = false,
    val includeCharacters: Boolean = true,
    val includeArtifacts: Boolean = true,
    val includeWeapons: Boolean = true,
    val includeMaterials: Boolean = true,
    val minCharacterLevel: Int = 1,
    val minCharacterAscension: Int = 0,
    val minCharacterConstellation: Int = 0,
    val minArtifactLevel: Int = 0,
    val minArtifactRarity: Int = 1,
    val minWeaponLevel: Int = 1,
    val minWeaponRefinement: Int = 1,
    val minWeaponAscension: Int = 0,
    val minWeaponRarity: Int = 1,
    val achievementExportFormat: String = "UIAF",
    val exportHistory: List<LocalStorage.ExportRecord> = emptyList()
)

class MainViewModel(private val context: Context) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private const val QUEUE_CAPACITY = 10000
        private const val EXPORT_FILE_PREFIX = "irminsul_"
        private const val EXPORT_ACHIEVEMENT_PREFIX = "achievements_"
        private const val EXPORT_FILE_EXTENSION_JSON = ".json"
        private const val EXPORT_FILE_EXTENSION_CSV = ".csv"
        private const val EXPORT_FORMAT_UIAF = "UIAF"
        private const val EXPORT_FORMAT_SEELIE = "Seelie"
        private const val EXPORT_FORMAT_CSV = "CSV"
        private const val PREFS_NAME = "irminsul_prefs"
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val dataStore: DataStore = DataStore()
    private val localStorage: LocalStorage = LocalStorage(context)
    private var packetQueue: LinkedBlockingQueue<ByteArray>? = null
    private var packetProcessor: PacketProcessor? = null

    private var isAutoStopping = false

    private val targetPackages = listOf(
        "com.miHoYo.GenshinImpact",
        "com.miHoYo.Yuanshen",
        "com.miHoYo.ys.bilibili"
    )

    private var logList = mutableListOf<String>()

    private fun initProcessing() {
        dataStore.clear()
        packetQueue = LinkedBlockingQueue(QUEUE_CAPACITY)
        packetProcessor = PacketProcessor(
            dataStore,
            packetQueue!!
        ) { items, characters, achievements ->
            onDataUpdated(items, characters, achievements)
        }
        packetProcessor!!.start()
    }

    private fun stopProcessing() {
        packetProcessor?.stopProcessor()
        packetProcessor = null
        packetQueue = null
    }

    private fun startProcessing() {
        stopProcessing()
        initProcessing()
    }

    init {
        NativeLib.setLogCallback(object : NativeLib.LogCallback {
            override fun onLog(message: String) {
                addLog(message)
            }
        })

        NativeLib.setDataCompleteCallback(object : NativeLib.DataCompleteCallback {
            override fun onDataComplete(
                artifactCount: Int,
                weaponCount: Int,
                materialCount: Int,
                characterCount: Int,
                achievementCount: Int
            ) {
                addLog("[SUCCESS] All data collected! Artifacts: $artifactCount, Weapons: $weaponCount, Materials: $materialCount, Characters: $characterCount, Achievements: $achievementCount")
            }
        })

        NativeLib.initLogging()

        if (NativeLib.isAvailable()) {
            val result = NativeLib.createSniffer()
            if (result == 0) {
                addLog("Irminsul native library initialized successfully")
            } else {
                addLog("Failed to initialize native library: $result")
            }
        } else {
            addLog("Warning: Native library not available. Packet parsing disabled.")
            addLog("To enable parsing, compile the Rust library.")
        }

        viewModelScope.launch {
            CaptureStatus.isCapturing.collect { isCapturing ->
                _uiState.value = _uiState.value.copy(
                    isCapturing = isCapturing,
                    isPendingStateChange = false
                )

                if (!isCapturing) {
                    stopProcessing()
                }
            }
        }

        viewModelScope.launch {
            dataStore.dataStatus.collect { status ->
                _uiState.value = _uiState.value.copy(
                    itemsLoaded = status.itemsLoaded,
                    charactersLoaded = status.charactersLoaded,
                    weaponsLoaded = status.weaponsLoaded,
                    achievementsLoaded = status.achievementsLoaded,
                    canExportGood = status.itemsLoaded && status.charactersLoaded,
                    canExportAchievements = status.achievementsLoaded,
                    artifactsCount = status.artifactsCount,
                    charactersCount = status.charactersCount,
                    materialsCount = status.materialsCount,
                    weaponsCount = status.weaponsCount,
                    achievementsCount = status.achievementsCount
                )

                if (status.itemsLoaded && status.charactersLoaded && status.weaponsLoaded && status.achievementsLoaded && !isAutoStopping && _uiState.value.isCapturing) {
                    isAutoStopping = true
                    addLog("[SUCCESS] All data parsed, auto-stopping capture")
                    stopVpnCapture(autoStop = true)
                }
            }
        }

        checkAndShowPermissionDialog()
    }

    fun checkAndShowPermissionDialog() {
        val state = PermissionHelper.checkPermissions(context)
        _uiState.value = _uiState.value.copy(permissionState = state)
        if (!state.allRequiredGranted) {
            ensureCompletionChannelExists()
            _uiState.value = _uiState.value.copy(showPermissionDialog = true)
        } else {
            _uiState.value = _uiState.value.copy(showPermissionDialog = false)
        }
    }

    fun recheckPermissions() {
        val state = PermissionHelper.checkPermissions(context)
        _uiState.value = _uiState.value.copy(permissionState = state)
        if (state.allRequiredGranted && _uiState.value.showPermissionDialog) {
            showToast(context.getString(R.string.permission_required_granted))
        }
    }

    fun dismissPermissionDialog() {
        // 必须权限未通过时不允许关闭
        if (!_uiState.value.permissionState.allRequiredGranted) return
        _uiState.value = _uiState.value.copy(showPermissionDialog = false)
    }

    fun requestVpnPermission(launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>) {
        val intent = PermissionHelper.getVpnPermissionIntent(context)
        if (intent != null) {
            launcher.launch(intent)
        } else {
            // 已有 VPN 权限，重新检查
            recheckPermissions()
        }
    }

    fun openBatteryOptimizationSettings(launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>) {
        try {
            launcher.launch(PermissionHelper.getBatteryOptimizationIntent(context))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
            showToast(context.getString(R.string.toast_cannot_open_settings))
        }
    }

    private fun ensureCompletionChannelExists() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = manager.getNotificationChannel(CaptureService.NOTIFICATION_CHANNEL_COMPLETE_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                CaptureService.NOTIFICATION_CHANNEL_COMPLETE_ID,
                context.getString(R.string.notification_channel_complete_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_complete_desc)
                setShowBadge(true)
                enableLights(true)
                lightColor = Color.GREEN
            }
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Pre-created completion notification channel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pre-create completion channel", e)
        }
    }

    fun requestNotificationPermission(launcher: androidx.activity.result.ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: 尝试重新请求 POST_NOTIFICATIONS 权限
            // 如果用户之前选了"不再询问"，系统会自动引导到设置页
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Android 13 以下不需要运行时权限，直接打开通知设置
            openAppNotificationSettingsWithFallback()
        }
    }

    fun openNotificationSettings() {
        // 通知权限被关闭时，直接打开应用详情页让用户去权限设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // POST_NOTIFICATIONS 被拒绝，打开应用详情页
            openAppDetailsSettings()
        } else {
            openAppNotificationSettingsWithFallback()
        }
    }

    private fun openAppNotificationSettingsWithFallback() {
        // 尝试 ROM 专有 → 标准 APP_NOTIFICATION_SETTINGS → 应用详情页
        val intents = mutableListOf<Intent>()
        RomUtils.getNotificationSettingsIntent(context)?.let { intents.add(it) }
        intents.add(PermissionHelper.getAppNotificationSettingsIntent(context))
        intents.add(PermissionHelper.getAppDetailsSettingsIntent(context))

        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                addLog("Opened notification settings")
                return
            } catch (e: android.content.ActivityNotFoundException) {
                Log.d(TAG, "Notification settings intent not available: ${intent.component ?: intent.action}")
            } catch (e: SecurityException) {
                Log.d(TAG, "Notification settings intent blocked: ${intent.component ?: intent.action}")
            }
        }
        Log.e(TAG, "All notification settings intents failed")
        showToast(context.getString(R.string.toast_cannot_open_settings))
    }

    fun openChannelSettings() {
        // 尝试渠道设置 → APP_NOTIFICATION_SETTINGS → 应用详情页
        val intents = mutableListOf<Intent>()
        intents.add(PermissionHelper.getChannelSettingsIntent(context, CaptureService.NOTIFICATION_CHANNEL_COMPLETE_ID))
        intents.add(PermissionHelper.getAppNotificationSettingsIntent(context))
        intents.add(PermissionHelper.getAppDetailsSettingsIntent(context))

        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                addLog("Opened notification channel settings")
                return
            } catch (e: android.content.ActivityNotFoundException) {
                Log.d(TAG, "Channel settings intent not available: ${intent.component ?: intent.action}")
            } catch (e: SecurityException) {
                Log.d(TAG, "Channel settings intent blocked: ${intent.component ?: intent.action}")
            }
        }
        Log.e(TAG, "All channel settings intents failed")
        showToast(context.getString(R.string.toast_cannot_open_settings))
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = PermissionHelper.getAppDetailsSettingsIntent(context).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            addLog("Opened app details settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details settings", e)
            showToast(context.getString(R.string.toast_cannot_open_settings))
        }
    }

    fun openAutoStartSettings() {
        // 尝试 ROM 专有自启动页 → 应用详情页
        val romIntent = RomUtils.getAutoStartSettingsIntent(context)
        if (romIntent != null) {
            try {
                romIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(romIntent)
                addLog("Opened auto-start settings")
                return
            } catch (e: android.content.ActivityNotFoundException) {
                Log.d(TAG, "Auto-start settings intent not available")
            } catch (e: SecurityException) {
                Log.d(TAG, "Auto-start settings intent blocked")
            }
        }
        // 回退到应用详情页
        openAppDetailsSettings()
    }

    fun testHeadsUpNotification() {
        CaptureService.showCompletionNotification(
            context,
            charactersCount = 1,
            artifactsCount = 2,
            weaponsCount = 3,
            achievementsCount = 4
        )
        addLog(context.getString(R.string.toast_test_notification_sent))
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                manager?.cancel(CaptureService.NOTIFICATION_ID_COMPLETE)
            } catch (_: Exception) {}
        }
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = "")
    }

    fun resetData() {
        dataStore.clear()
        logList.clear()
        _uiState.value = UiState()
        NativeLib.destroySniffer()
        NativeLib.createSniffer()
        initProcessing()
        showToast(context.getString(R.string.data_reset))
        addLog("Data reset!")
    }

    private fun showToast(message: String) {
        _uiState.value = _uiState.value.copy(toastMessage = message)
    }

    fun addLog(message: String) {
        viewModelScope.launch {
            logList.add(message)
            _uiState.value = _uiState.value.copy(logs = logList.toList())
        }
    }

    fun toggleCapture(vpnPermissionLauncher: ActivityResultLauncher<Intent>, onVpnApproved: () -> Unit) {
        val newCapturingState = !_uiState.value.isCapturing
        _uiState.value = _uiState.value.copy(
            isCapturing = newCapturingState,
            isPendingStateChange = true
        )

        if (newCapturingState) {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                onVpnApproved()
            }
        } else {
            stopVpnCapture()
        }
    }

    fun startVpnCapture() {
        Log.d(TAG, "Starting VPN capture")
        isAutoStopping = false
        CaptureStatus.resetParsingProgress()
        stopProcessing()
        initProcessing()

        CaptureService.setPacketQueue(packetQueue)

        val intent = Intent(context, CaptureService::class.java)
        intent.action = CaptureService.ACTION_START
        ContextCompat.startForegroundService(context, intent)

        _uiState.value = _uiState.value.copy(isCapturing = true, isPendingStateChange = false, showLaunchGameDialog = true)
        CaptureStatus.updateCapturingStatus(true)
        addLog("VPN capture started")
    }

    private fun stopVpnCapture(autoStop: Boolean = false) {
        Log.d(TAG, "Stopping VPN capture (autoStop=$autoStop)")
        val intent = Intent(context, CaptureService::class.java)
        intent.action = CaptureService.ACTION_STOP
        context.startService(intent)

        if (autoStop) {
            val status = dataStore.dataStatus.value
            CaptureService.showCompletionNotification(
                context,
                status.charactersCount,
                status.artifactsCount,
                status.weaponsCount,
                status.achievementsCount
            )
        }

        stopProcessing()
        _uiState.value = _uiState.value.copy(isCapturing = false, isPendingStateChange = false, showLaunchGameDialog = false)
        CaptureStatus.updateCapturingStatus(false)
        addLog("VPN capture stopped")
    }

    fun launchGame() {
        val pm = context.packageManager
        for (pkg in targetPackages) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    addLog("Launched game: $pkg")
                    _uiState.value = _uiState.value.copy(showLaunchGameDialog = false)
                    return
                }
            } catch (e: android.content.ActivityNotFoundException) {
                Log.d(TAG, "Package $pkg launch activity not found")
            } catch (e: SecurityException) {
                // 国产ROM关联启动权限缺失
                Log.e(TAG, "Cannot launch $pkg, possibly blocked by ROM", e)
                showToast(context.getString(R.string.toast_launch_blocked))
                _uiState.value = _uiState.value.copy(showLaunchGameDialog = false)
                return
            } catch (e: Exception) {
                Log.d(TAG, "Package $pkg not found")
            }
        }
        showToast(context.getString(R.string.toast_game_not_found))
        _uiState.value = _uiState.value.copy(showLaunchGameDialog = false)
    }

    fun dismissLaunchGameDialog() {
        _uiState.value = _uiState.value.copy(showLaunchGameDialog = false)
    }

    fun openPcapFile(launcher: ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
        intent.putExtra("android.content.extra.MIME_TYPES", arrayOf("application/octet-stream", "application/x-pcap", "*/*"))
        try {
            launcher.launch(Intent.createChooser(intent, "Select PCAP file"))
        } catch (e: Exception) {
            addLog("Error opening file picker: ${e.message}")
        }
    }

    fun handlePcapFileSelected(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val uri = data.data
            if (uri != null) {
                processPcapFile(uri)
            }
        }
    }

    fun processPcapFileByPath(filePath: String) {
        try {
            addLog("Processing PCAP file: $filePath...")
            stopProcessing()
            initProcessing()

            if (File(filePath).exists()) {
                packetProcessor?.readPcapFile(filePath)
            } else {
                addLog("Error: File not found: $filePath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing PCAP file", e)
            addLog("Error: ${e.message}")
        }
    }

    private fun processPcapFile(uri: Uri) {
        try {
            addLog("Processing PCAP file...")
            stopProcessing()
            initProcessing()

            var filePath: String? = uri.path
            if (filePath == null || !File(filePath).exists()) {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val tempFile = File(context.cacheDir, "temp.pcap")
                    val fos = FileOutputStream(tempFile)
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (inputStream.read(buffer).also { len = it } != -1) {
                        fos.write(buffer, 0, len)
                    }
                    fos.close()
                    inputStream.close()
                    filePath = tempFile.absolutePath
                }
            }

            if (filePath != null) {
                packetProcessor?.readPcapFile(filePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing PCAP file", e)
            addLog("Error: ${e.message}")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDataUpdated(items: Boolean, characters: Boolean, achievements: Boolean) {
        if (items) {
            addLog("[SUCCESS] Items data captured!")
        }
        if (characters) {
            addLog("[SUCCESS] Characters data captured!")
        }
        if (achievements) {
            addLog("[SUCCESS] Achievements data captured!")
        }
    }

    fun copyGoodToClipboard() {
        try {
            val settings = buildExportSettings()
            val (json, stats) = dataStore.exportGood(settings)
            copyToClipboard(json)
            val parts = mutableListOf<String>()
            if (settings.includeCharacters) parts.add("${stats.charactersCount}${context.getString(R.string.characters)}")
            if (settings.includeArtifacts) parts.add("${stats.artifactsCount}${context.getString(R.string.artifacts)}")
            if (settings.includeWeapons) parts.add("${stats.weaponsCount}${context.getString(R.string.weapons)}")
            if (settings.includeMaterials) parts.add("${stats.materialsCount}${context.getString(R.string.materials)}")
            showToast(context.getString(R.string.toast_copied_with_stats, parts.joinToString(", ")))
            addLog("GOOD JSON copied to clipboard! Stats: $stats")
        } catch (e: Exception) {
            Log.e(TAG, "Error copying GOOD format", e)
            showToast(context.getString(R.string.toast_copy_failed, e.message ?: ""))
            addLog("Copy failed: ${e.message}")
        }
    }

    fun downloadGood(): String {
        return try {
            val settings = buildExportSettings()
            val (json, stats) = dataStore.exportGood(settings)
            val path = saveExportFile(json, "irminsul_good_")
            val parts = mutableListOf<String>()
            if (settings.includeCharacters) parts.add("${stats.charactersCount}${context.getString(R.string.characters)}")
            if (settings.includeArtifacts) parts.add("${stats.artifactsCount}${context.getString(R.string.artifacts)}")
            if (settings.includeWeapons) parts.add("${stats.weaponsCount}${context.getString(R.string.weapons)}")
            if (settings.includeMaterials) parts.add("${stats.materialsCount}${context.getString(R.string.materials)}")
            showToast(context.getString(R.string.toast_saved_to, path, parts.joinToString(", ")))
            addLog("GOOD JSON saved! Stats: $stats")
            saveExportRecord("GOOD", "json", path, json.length, true)
            path
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading GOOD format", e)
            showToast(context.getString(R.string.toast_download_failed, e.message ?: ""))
            addLog("Download failed: ${e.message}")
            saveExportRecord("GOOD", "json", null, 0, false)
            ""
        }
    }

    fun copyAchievements() {
        try {
            val formatCode = achievementFormatToCode(_uiState.value.achievementExportFormat)
            val json = dataStore.exportAchievements(formatCode)
            copyToClipboard(json)
            showToast(context.getString(R.string.toast_copied_achievements, _uiState.value.achievementsCount))
            addLog("${_uiState.value.achievementExportFormat} copied to clipboard!")
        } catch (e: Exception) {
            Log.e(TAG, "Error copying achievements", e)
            showToast(context.getString(R.string.toast_copy_failed, e.message ?: ""))
            addLog("Copy failed: ${e.message}")
        }
    }

    fun openInCocogoat() {
        viewModelScope.launch {
            try {
                val json = dataStore.exportAchievements(DataStore.FORMAT_UIAF)
                val cocogoatUrl = postToMemoApi(json)
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cocogoatUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    addLog("Opened in Cocogoat")
                } catch (e: android.content.ActivityNotFoundException) {
                    // 没有浏览器应用，复制链接到剪贴板
                    copyToClipboard(cocogoatUrl)
                    showToast(context.getString(R.string.toast_no_browser_copied))
                    addLog("No browser found, URL copied to clipboard")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error opening in Cocogoat", e)
                showToast(context.getString(R.string.toast_cocogoat_failed, e.message ?: ""))
                addLog("Cocogoat failed: ${e.message}")
            }
        }
    }

    fun downloadAchievements() {
        try {
            val formatCode = achievementFormatToCode(_uiState.value.achievementExportFormat)
            val content = dataStore.exportAchievements(formatCode)
            val ext = if (formatCode == DataStore.FORMAT_CSV) EXPORT_FILE_EXTENSION_CSV else EXPORT_FILE_EXTENSION_JSON
            val path = saveExportFile(content, EXPORT_ACHIEVEMENT_PREFIX + _uiState.value.achievementExportFormat.lowercase() + "_", ext)
            showToast(context.getString(R.string.toast_saved_achievements, path, _uiState.value.achievementsCount))
            addLog("${_uiState.value.achievementExportFormat} saved!")
            saveExportRecord("Achievements", _uiState.value.achievementExportFormat, path, content.length, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading achievements", e)
            showToast(context.getString(R.string.toast_download_failed, e.message ?: ""))
            addLog("Download failed: ${e.message}")
            saveExportRecord("Achievements", _uiState.value.achievementExportFormat, null, 0, false)
        }
    }

    private fun achievementFormatToCode(format: String): Int {
        return when (format) {
            EXPORT_FORMAT_UIAF -> DataStore.FORMAT_UIAF
            EXPORT_FORMAT_SEELIE -> DataStore.FORMAT_SEELIE
            EXPORT_FORMAT_CSV -> DataStore.FORMAT_CSV
            else -> DataStore.FORMAT_UIAF
        }
    }

    private fun buildExportSettings(): ExportSettings {
        return ExportSettings().apply {
            includeCharacters = _uiState.value.includeCharacters
            includeArtifacts = _uiState.value.includeArtifacts
            includeWeapons = _uiState.value.includeWeapons
            includeMaterials = _uiState.value.includeMaterials
            minCharacterLevel = _uiState.value.minCharacterLevel
            minCharacterAscension = _uiState.value.minCharacterAscension
            minCharacterConstellation = _uiState.value.minCharacterConstellation
            minArtifactLevel = _uiState.value.minArtifactLevel
            minArtifactRarity = _uiState.value.minArtifactRarity
            minWeaponLevel = _uiState.value.minWeaponLevel
            minWeaponRefinement = _uiState.value.minWeaponRefinement
            minWeaponAscension = _uiState.value.minWeaponAscension
            minWeaponRarity = _uiState.value.minWeaponRarity
            fakeInitialize4thLine = _uiState.value.fakeInitialize4thLine
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Irminsul Export", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
            addLog("Failed to copy: ${e.message}")
        }
    }

    private fun saveExportFile(content: String, prefix: String = EXPORT_FILE_PREFIX, extension: String = EXPORT_FILE_EXTENSION_JSON): String {
        return try {
            val exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val exportFile = File(exportDir, "$prefix$timestamp$extension")

            val fos = FileOutputStream(exportFile)
            fos.write(content.toByteArray())
            fos.close()

            addLog("Saved to: ${exportFile.absolutePath}")
            exportFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file", e)
            addLog("Failed to save file: ${e.message}")
            context.getString(R.string.toast_save_failed, e.message ?: "")
        }
    }

    fun toggleFakeInitialize4thLine() {
        _uiState.value = _uiState.value.copy(
            fakeInitialize4thLine = !_uiState.value.fakeInitialize4thLine
        )
        val status = if (_uiState.value.fakeInitialize4thLine) "enabled" else "disabled"
        addLog("Fake initialize 4th line: $status")
    }

    fun setAchievementExportFormat(format: String) {
        _uiState.value = _uiState.value.copy(achievementExportFormat = format)
        addLog("Achievement export format: $format")
    }

    fun toggleIncludeCharacters() {
        _uiState.value = _uiState.value.copy(
            includeCharacters = !_uiState.value.includeCharacters
        )
    }

    fun toggleIncludeArtifacts() {
        _uiState.value = _uiState.value.copy(
            includeArtifacts = !_uiState.value.includeArtifacts
        )
    }

    fun toggleIncludeWeapons() {
        _uiState.value = _uiState.value.copy(
            includeWeapons = !_uiState.value.includeWeapons
        )
    }

    fun toggleIncludeMaterials() {
        _uiState.value = _uiState.value.copy(
            includeMaterials = !_uiState.value.includeMaterials
        )
    }

    fun updateMinCharacterLevel(level: Int) {
        _uiState.value = _uiState.value.copy(minCharacterLevel = level.coerceIn(1, 90))
    }

    fun updateMinCharacterAscension(ascension: Int) {
        _uiState.value = _uiState.value.copy(minCharacterAscension = ascension.coerceIn(0, 6))
    }

    fun updateMinCharacterConstellation(constellation: Int) {
        _uiState.value = _uiState.value.copy(minCharacterConstellation = constellation.coerceIn(0, 6))
    }

    fun updateMinArtifactLevel(level: Int) {
        _uiState.value = _uiState.value.copy(minArtifactLevel = level.coerceIn(0, 20))
    }

    fun updateMinArtifactRarity(rarity: Int) {
        _uiState.value = _uiState.value.copy(minArtifactRarity = rarity.coerceIn(1, 5))
    }

    fun updateMinWeaponLevel(level: Int) {
        _uiState.value = _uiState.value.copy(minWeaponLevel = level.coerceIn(1, 90))
    }

    fun updateMinWeaponRefinement(refinement: Int) {
        _uiState.value = _uiState.value.copy(minWeaponRefinement = refinement.coerceIn(1, 5))
    }

    fun updateMinWeaponAscension(ascension: Int) {
        _uiState.value = _uiState.value.copy(minWeaponAscension = ascension.coerceIn(0, 6))
    }

    fun updateMinWeaponRarity(rarity: Int) {
        _uiState.value = _uiState.value.copy(minWeaponRarity = rarity.coerceIn(1, 5))
    }

    fun loadExportHistory() {
        val history = localStorage.getExportHistory()
        _uiState.value = _uiState.value.copy(exportHistory = history)
    }

    fun saveExportRecord(type: String, format: String, path: String?, dataSize: Int, success: Boolean) {
        localStorage.saveExportRecord(type, format, path, dataSize, success)
        loadExportHistory()
    }

    fun clearExportHistory() {
        localStorage.clearExportHistory()
        loadExportHistory()
    }

    fun deleteExportRecord(id: String) {
        localStorage.deleteExportRecord(id)
        loadExportHistory()
    }

    fun saveSettingsToStorage() {
        val settings = LocalStorage.ExportSettingsSnapshot(
            includeCharacters = _uiState.value.includeCharacters,
            includeArtifacts = _uiState.value.includeArtifacts,
            includeWeapons = _uiState.value.includeWeapons,
            includeMaterials = _uiState.value.includeMaterials,
            minCharacterLevel = _uiState.value.minCharacterLevel,
            minCharacterAscension = _uiState.value.minCharacterAscension,
            minCharacterConstellation = _uiState.value.minCharacterConstellation,
            minArtifactLevel = _uiState.value.minArtifactLevel,
            minArtifactRarity = _uiState.value.minArtifactRarity,
            minWeaponLevel = _uiState.value.minWeaponLevel,
            minWeaponRefinement = _uiState.value.minWeaponRefinement,
            minWeaponAscension = _uiState.value.minWeaponAscension,
            minWeaponRarity = _uiState.value.minWeaponRarity,
            fakeInitialize4thLine = _uiState.value.fakeInitialize4thLine
        )
        localStorage.saveSettings(settings)
    }

    fun loadSettingsFromStorage() {
        val savedSettings = localStorage.loadSettings()
        if (savedSettings != null) {
            _uiState.value = _uiState.value.copy(
                includeCharacters = savedSettings.includeCharacters,
                includeArtifacts = savedSettings.includeArtifacts,
                includeWeapons = savedSettings.includeWeapons,
                includeMaterials = savedSettings.includeMaterials,
                minCharacterLevel = savedSettings.minCharacterLevel,
                minCharacterAscension = savedSettings.minCharacterAscension,
                minCharacterConstellation = savedSettings.minCharacterConstellation,
                minArtifactLevel = savedSettings.minArtifactLevel,
                minArtifactRarity = savedSettings.minArtifactRarity,
                minWeaponLevel = savedSettings.minWeaponLevel,
                minWeaponRefinement = savedSettings.minWeaponRefinement,
                minWeaponAscension = savedSettings.minWeaponAscension,
                minWeaponRarity = savedSettings.minWeaponRarity,
                fakeInitialize4thLine = savedSettings.fakeInitialize4thLine
            )
        }
    }

    fun getExportCount(): Int {
        return localStorage.getExportCount()
    }

    fun getLastExportTime(): Long {
        return localStorage.getLastExportTime()
    }

    override fun onCleared() {
        super.onCleared()
        stopProcessing()
        NativeLib.destroySniffer()
    }
}
