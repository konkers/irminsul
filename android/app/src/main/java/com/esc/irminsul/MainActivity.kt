package com.esc.irminsul

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(applicationContext)
    }

    // VPN 权限请求 — 初始权限检查和抓包时共用
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.addLog("VPN 权限已开启")
        } else {
            viewModel.addLog("VPN 权限被拒绝")
        }
        // 无论结果如何都重新检查权限状态
        viewModel.recheckPermissions()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.addLog("通知权限已开启")
        } else {
            viewModel.addLog("通知权限被拒绝")
        }
        viewModel.recheckPermissions()
    }

    private val captureNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.addLog("通知权限被拒绝，通知可能无法显示")
        }
        viewModel.startVpnCapture()
    }

    // 抓包时如果 VPN 权限还没拿到，再请求一次
    private val captureVpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkNotificationPermissionAndStartCapture()
        } else {
            viewModel.addLog("VPN permission denied")
            CaptureStatus.updateCapturingStatus(false)
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.recheckPermissions()
    }

    private val pcapFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handlePcapFileSelected(result.resultCode, result.data)
    }

    private fun checkNotificationPermissionAndStartCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                viewModel.startVpnCapture()
            } else {
                captureNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.startVpnCapture()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            IrminsulTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        vpnPermissionLauncher = vpnPermissionLauncher,
                        batteryOptimizationLauncher = batteryOptimizationLauncher,
                        notificationPermissionLauncher = notificationPermissionLauncher,
                        onToggleCapture = {
                            viewModel.toggleCapture(captureVpnPermissionLauncher) {
                                checkNotificationPermissionAndStartCapture()
                            }
                        },
                        onOpenPcapFile = {
                            viewModel.openPcapFile(pcapFileLauncher)
                        },
                        onResetData = { viewModel.resetData() },
                        onCopyGood = { viewModel.copyGoodToClipboard() },
                        onDownloadGood = { viewModel.downloadGood() },
                        onCopyAchievements = { viewModel.copyAchievements() },
                        onDownloadAchievements = { viewModel.downloadAchievements() },
                        onSetAchievementFormat = { format -> viewModel.setAchievementExportFormat(format) },
                        onOpenAchievements = { viewModel.openInCocogoat() }
                    )
                }
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        viewModel.recheckPermissions()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // ViewModel init 会自动调用 checkAndShowPermissionDialog
    }
}

class MainViewModelFactory(
    private val context: android.content.Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
