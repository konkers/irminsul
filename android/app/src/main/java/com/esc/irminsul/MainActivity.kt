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

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkNotificationPermissionAndStartCapture()
        } else {
            viewModel.addLog("VPN permission denied")
            CaptureStatus.updateCapturingStatus(false)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onNotificationPermissionGranted()
        } else {
            viewModel.addLog("通知权限被拒绝，通知可能无法显示")
        }
    }

    private val captureNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.addLog("通知权限被拒绝，通知可能无法显示")
        }
        viewModel.startVpnCapture()
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
                        onToggleCapture = {
                            viewModel.toggleCapture(vpnPermissionLauncher) {
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
                        onSetAchievementFormat = { format -> viewModel.setAchievementExportFormat(format) }
                    )
                }
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                val prefs = getSharedPreferences("irminsul_prefs", MODE_PRIVATE)
                val hasAskedBefore = prefs.getBoolean("notification_permission_asked", false)
                if (!hasAskedBefore) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    prefs.edit().putBoolean("notification_permission_asked", true).apply()
                } else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.onNotificationPermissionGranted()
                }
            } else {
                viewModel.onNotificationPermissionGranted()
            }
        } else {
            viewModel.onNotificationPermissionGranted()
        }
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
