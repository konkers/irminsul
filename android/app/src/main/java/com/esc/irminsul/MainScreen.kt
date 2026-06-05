package com.esc.irminsul

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onToggleCapture: () -> Unit,
    onOpenPcapFile: () -> Unit,
    onResetData: () -> Unit,
    onCopyGood: () -> Unit,
    onDownloadGood: () -> Unit,
    onCopyAchievements: () -> Unit,
    onDownloadAchievements: () -> Unit,
    onSetAchievementFormat: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAchievementSettingsDialog by remember { mutableStateOf(false) }
    var showExportHistoryDialog by remember { mutableStateOf(false) }
    
    val backgroundBitmap = remember { loadBackgroundImage(context) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        if (backgroundBitmap != null) {
            Image(
                bitmap = backgroundBitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xDD0D1B2A),
                                Color(0x000D1B2A)
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            HeaderSection()

            Spacer(modifier = Modifier.height(28.dp))

            CaptureCard(
                isCapturing = uiState.isCapturing,
                artifactsCount = uiState.artifactsCount,
                charactersCount = uiState.charactersCount,
                materialsCount = uiState.materialsCount,
                weaponsCount = uiState.weaponsCount,
                achievementsCount = uiState.achievementsCount,
                artifactsLoaded = uiState.itemsLoaded,
                charactersLoaded = uiState.charactersLoaded,
                materialsLoaded = uiState.itemsLoaded,
                weaponsLoaded = uiState.weaponsLoaded,
                achievementsLoaded = uiState.achievementsLoaded,
                onToggleCapture = onToggleCapture,
                onOpenPcapFile = onOpenPcapFile,
                onResetData = onResetData
            )

            Spacer(modifier = Modifier.height(20.dp))

            ExportSection(
                title = stringResource(R.string.data_export),
                subtitle = stringResource(R.string.good_format),
                canExport = uiState.canExportGood,
                fakeInitializeEnabled = uiState.fakeInitialize4thLine,
                onCopy = onCopyGood,
                onDownload = onDownloadGood,
                onSettings = { showSettingsDialog = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            AchievementExportSection(
                canExport = uiState.canExportAchievements,
                currentFormat = uiState.achievementExportFormat,
                onCopy = onCopyAchievements,
                onDownload = onDownloadAchievements,
                onSettings = { showAchievementSettingsDialog = true }
            )

            Spacer(modifier = Modifier.height(80.dp))
        }

        AnimatedVisibility(
            visible = uiState.toastMessage.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.9f),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.9f)
        ) {
            ToastDialog(message = uiState.toastMessage) {
                viewModel.clearToast()
            }
        }

        if (uiState.showLaunchGameDialog) {
            LaunchGameDialog(
                onLaunchGame = { viewModel.launchGame() },
                onDismiss = { viewModel.dismissLaunchGameDialog() }
            )
        }

        if (uiState.showHeadsUpSetupDialog) {
            HeadsUpSetupDialog(
                onOpenSettings = { viewModel.openAppNotificationSettings() },
                onTestNotification = { viewModel.testHeadsUpNotification() },
                onDismiss = { viewModel.dismissHeadsUpSetupDialog() }
            )
        }

        if (showExportHistoryDialog) {
            ExportHistoryDialog(
                history = uiState.exportHistory,
                onClear = { viewModel.clearExportHistory() },
                onDelete = { id -> viewModel.deleteExportRecord(id) },
                onDismiss = { showExportHistoryDialog = false }
            )
        }

        if (showSettingsDialog) {
            SettingsDialog(
                uiState = uiState,
                onFakeInitialize4thLineChange = { viewModel.toggleFakeInitialize4thLine() },
                onIncludeCharactersChange = { viewModel.toggleIncludeCharacters() },
                onIncludeArtifactsChange = { viewModel.toggleIncludeArtifacts() },
                onIncludeWeaponsChange = { viewModel.toggleIncludeWeapons() },
                onIncludeMaterialsChange = { viewModel.toggleIncludeMaterials() },
                onMinCharacterLevelChange = { viewModel.updateMinCharacterLevel(it) },
                onMinCharacterAscensionChange = { viewModel.updateMinCharacterAscension(it) },
                onMinCharacterConstellationChange = { viewModel.updateMinCharacterConstellation(it) },
                onMinArtifactLevelChange = { viewModel.updateMinArtifactLevel(it) },
                onMinArtifactRarityChange = { viewModel.updateMinArtifactRarity(it) },
                onMinWeaponLevelChange = { viewModel.updateMinWeaponLevel(it) },
                onMinWeaponRefinementChange = { viewModel.updateMinWeaponRefinement(it) },
                onMinWeaponAscensionChange = { viewModel.updateMinWeaponAscension(it) },
                onMinWeaponRarityChange = { viewModel.updateMinWeaponRarity(it) },
                onDismiss = { showSettingsDialog = false }
            )
        }

        if (showAchievementSettingsDialog) {
            AchievementSettingsDialog(
                currentFormat = uiState.achievementExportFormat,
                onFormatChange = onSetAchievementFormat,
                onDismiss = { showAchievementSettingsDialog = false }
            )
        }
    }
}

@Composable
fun HeaderSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Serif,
            color = TextPrimary,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.app_description),
            fontSize = 13.sp,
            color = TextHint,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun CaptureCard(
    isCapturing: Boolean,
    artifactsCount: Int,
    charactersCount: Int,
    materialsCount: Int,
    weaponsCount: Int,
    achievementsCount: Int,
    artifactsLoaded: Boolean,
    charactersLoaded: Boolean,
    materialsLoaded: Boolean,
    weaponsLoaded: Boolean,
    achievementsLoaded: Boolean,
    onToggleCapture: () -> Unit,
    onOpenPcapFile: () -> Unit,
    onResetData: () -> Unit
) {
    val borderColor = if (isCapturing) Error.copy(alpha = 0.4f) else Border.copy(alpha = 0.3f)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isCapturing) 25.dp else 16.dp,
                spotColor = if (isCapturing) Color(0x50FF6B6B) else Color(0x3000B4D8),
                ambientColor = if (isCapturing) Color(0x20FF6B6B) else Color(0x1500B4D8)
            )
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Surface.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isCapturing) stringResource(R.string.capturing) else stringResource(R.string.packet_capture),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (isCapturing) stringResource(R.string.listening_for_data) else stringResource(R.string.ready_to_capture),
                            fontSize = 12.sp,
                            color = TextHint
                        )
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CaptureButton(
                            isCapturing = isCapturing,
                            onClick = onToggleCapture
                        )

                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (!isCapturing) SurfaceLight else Surface.copy(alpha = 0.5f))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { if (!isCapturing) onResetData() },
                                        onLongPress = { if (!isCapturing) onOpenPcapFile() }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_reset),
                                contentDescription = "Reset Data",
                                tint = if (!isCapturing) TextPrimary else TextDisabled,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Border.copy(alpha = 0.4f))
            )

            Spacer(modifier = Modifier.height(20.dp))

            DataStatsGrid(
                stats = listOf(
                    DataStat(stringResource(R.string.artifacts), artifactsCount, artifactsLoaded),
                    DataStat(stringResource(R.string.characters), charactersCount, charactersLoaded),
                    DataStat(stringResource(R.string.weapons), weaponsCount, weaponsLoaded),
                    DataStat(stringResource(R.string.materials), materialsCount, materialsLoaded),
                    DataStat(stringResource(R.string.achievements), achievementsCount, achievementsLoaded)
                )
            )
        }
    }
}

@Composable
fun CaptureButton(
    isCapturing: Boolean,
    onClick: () -> Unit
) {
    val buttonColor = if (isCapturing) Error else ButtonSuccess
    val iconRes = if (isCapturing) R.drawable.ic_stop else R.drawable.ic_play

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isCapturing) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(buttonColor)
            .shadow(
                elevation = if (isCapturing) 12.dp else 16.dp,
                spotColor = if (isCapturing) Error.copy(alpha = 0.6f) else ButtonSuccess.copy(alpha = 0.6f),
                ambientColor = if (isCapturing) Error.copy(alpha = 0.3f) else ButtonSuccess.copy(alpha = 0.3f)
            )
            .clickable { onClick() }
            .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = if (isCapturing) "Stop" else "Start",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        
        if (isCapturing) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .border(2.dp, Error.copy(alpha = 0.4f), CircleShape)
            )
        }
    }
}

data class DataStat(val label: String, val count: Int, val isLoaded: Boolean)

@Composable
fun DataStatsGrid(stats: List<DataStat>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[0])
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[1])
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[2])
        }
    }
    
    Spacer(modifier = Modifier.height(14.dp))
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[3])
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DataStatItem(stats[4])
        }
    }
}

@Composable
fun DataStatItem(stat: DataStat) {
    AnimatedContent(
        targetState = stat.isLoaded,
        transitionSpec = {
            slideInVertically(initialOffsetY = { -20 }) + fadeIn() togetherWith
            slideOutVertically(targetOffsetY = { 20 }) + fadeOut()
        }
    ) { isLoaded ->
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isLoaded) SurfaceHighlight else SurfaceLight),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isLoaded) stat.count.toString() else "-",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isLoaded) Success else TextHint
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stat.label,
                    fontSize = 9.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
fun ExportSection(
    title: String,
    subtitle: String,
    canExport: Boolean,
    fakeInitializeEnabled: Boolean,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border.copy(alpha = 0.25f), RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Surface.copy(alpha = 0.92f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = TextHint
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (fakeInitializeEnabled) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Accent.copy(alpha = 0.22f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.fourth_line),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Accent
                            )
                        }
                    }

                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceLight)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExportActionButton(
                    icon = R.drawable.ic_copy,
                    label = stringResource(R.string.copy),
                    enabled = canExport,
                    onClick = onCopy
                )
                
                Spacer(modifier = Modifier.width(14.dp))
                
                ExportActionButton(
                    icon = R.drawable.ic_download,
                    label = stringResource(R.string.save),
                    enabled = canExport,
                    onClick = onDownload,
                    isPrimary = true
                )
            }
        }
    }
}

@Composable
fun ExportActionButton(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    isPrimary: Boolean = false
) {
    val bgColor = if (enabled) {
        if (isPrimary) {
            ButtonPrimary
        } else {
            SurfaceLight
        }
    } else {
        Surface.copy(alpha = 0.4f)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clickable(enabled = enabled) { onClick() },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = label,
            tint = if (enabled) {
                if (isPrimary) Color.White else TextPrimary
            } else {
                TextDisabled
            },
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) {
                if (isPrimary) Color.White else TextPrimary
            } else {
                TextDisabled
            }
        )
    }
}

@Composable
fun AchievementExportSection(
    canExport: Boolean,
    currentFormat: String,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border.copy(alpha = 0.25f), RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Surface.copy(alpha = 0.92f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.achievement_export),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = stringResource(
                            when (currentFormat) {
                                "UIAF" -> R.string.format_uiaf
                                "Seelie" -> R.string.format_seelie
                                "CSV" -> R.string.format_csv
                                else -> R.string.format_uiaf
                            }
                        ),
                        fontSize = 12.sp,
                        color = TextHint
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceLight)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExportActionButton(
                    icon = R.drawable.ic_copy,
                    label = stringResource(R.string.copy),
                    enabled = canExport,
                    onClick = onCopy
                )
                
                Spacer(modifier = Modifier.width(14.dp))
                
                ExportActionButton(
                    icon = R.drawable.ic_download,
                    label = stringResource(R.string.save),
                    enabled = canExport,
                    onClick = onDownload,
                    isPrimary = true
                )
            }
        }
    }
}

@Composable
fun ExportHistorySection(
    history: List<LocalStorage.ExportRecord>,
    onClear: () -> Unit,
    onDelete: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border.copy(alpha = 0.25f), RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Surface.copy(alpha = 0.92f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_log),
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.export_history),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.clear_all),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(SurfaceLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_download),
                                contentDescription = null,
                                tint = TextHint,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.no_export_history),
                            fontSize = 14.sp,
                            color = TextHint
                        )
                    }
                }
            } else {
                Column {
                    history.forEachIndexed { index, record ->
                        HistoryItem(
                            record = record,
                            onDelete = { onDelete(record.id) }
                        )
                        if (index < history.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Border.copy(alpha = 0.2f))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItem(
    record: LocalStorage.ExportRecord,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (record.success) Success.copy(alpha = 0.18f) else Error.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    id = if (record.success) R.drawable.ic_check else R.drawable.ic_error
                ),
                contentDescription = null,
                tint = if (record.success) Success else Error,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.type,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(SurfaceLight)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = record.format,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
            Text(
                text = formatTimestamp(record.timestamp),
                fontSize = 12.sp,
                color = TextHint
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_trash),
                contentDescription = "Delete",
                tint = Error.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun ToastDialog(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        onDismiss()
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .border(1.dp, Border.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Success.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_check),
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun LaunchGameDialog(
    onLaunchGame: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .border(1.dp, Border.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ButtonSuccess.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play),
                        contentDescription = null,
                        tint = ButtonSuccess,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.capture_started),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.launch_game_hint),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Border.copy(alpha = 0.3f))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Warning.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.launch_game_tip),
                        fontSize = 11.sp,
                        color = Warning,
                        lineHeight = 16.sp
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceLight)
                            .clickable { onDismiss() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.launch_later),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ButtonPrimary)
                            .clickable { onLaunchGame() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.launch_game),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HeadsUpSetupDialog(
    onOpenSettings: () -> Unit,
    onTestNotification: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .border(1.dp, Border.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Warning.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_error),
                        contentDescription = null,
                        tint = Warning,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.heads_up_setup_title),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.heads_up_setup_message),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceLight.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.heads_up_setup_steps_title),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Accent,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.heads_up_setup_steps),
                            fontSize = 11.sp,
                            color = TextHint,
                            lineHeight = 16.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ButtonSuccess.copy(alpha = 0.15f))
                        .clickable { onTestNotification() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.test_notification),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ButtonSuccess
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceLight)
                            .clickable { onDismiss() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.later),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ButtonPrimary)
                            .clickable { onOpenSettings() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.go_to_settings),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExportHistoryDialog(
    history: List<LocalStorage.ExportRecord>,
    onClear: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .border(1.dp, Border.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_log),
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.export_history),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (history.isNotEmpty()) {
                            TextButton(
                                onClick = onClear,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.clear_all),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Error
                                )
                            }
                        }
                        
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_error),
                                contentDescription = "Close",
                                tint = TextHint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Border.copy(alpha = 0.3f))
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (history.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(SurfaceLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_download),
                                    contentDescription = null,
                                    tint = TextHint,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.no_export_history),
                                fontSize = 15.sp,
                                color = TextHint
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.exported_data_will_appear),
                                fontSize = 12.sp,
                                color = TextHint.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            history.forEachIndexed { index, record ->
                                HistoryItemDialog(
                                    record = record,
                                    onDelete = { onDelete(record.id) }
                                )
                                if (index < history.size - 1) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(Border.copy(alpha = 0.2f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItemDialog(
    record: LocalStorage.ExportRecord,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (record.success) Success.copy(alpha = 0.18f) else Error.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    id = if (record.success) R.drawable.ic_check else R.drawable.ic_error
                ),
                contentDescription = null,
                tint = if (record.success) Success else Error,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.type,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceLight)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = record.format,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatTimestamp(record.timestamp),
                fontSize = 13.sp,
                color = TextHint
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_trash),
                contentDescription = "Delete",
                tint = Error.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SettingsDialog(
    uiState: UiState,
    onFakeInitialize4thLineChange: () -> Unit,
    onIncludeCharactersChange: () -> Unit,
    onIncludeArtifactsChange: () -> Unit,
    onIncludeWeaponsChange: () -> Unit,
    onIncludeMaterialsChange: () -> Unit,
    onMinCharacterLevelChange: (Int) -> Unit,
    onMinCharacterAscensionChange: (Int) -> Unit,
    onMinCharacterConstellationChange: (Int) -> Unit,
    onMinArtifactLevelChange: (Int) -> Unit,
    onMinArtifactRarityChange: (Int) -> Unit,
    onMinWeaponLevelChange: (Int) -> Unit,
    onMinWeaponRefinementChange: (Int) -> Unit,
    onMinWeaponAscensionChange: (Int) -> Unit,
    onMinWeaponRarityChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .border(1.dp, Border.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_error),
                            contentDescription = "Close",
                            tint = TextHint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Border.copy(alpha = 0.3f))
                )

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column {
                        SettingsSection(title = stringResource(R.string.include_data)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SettingsSwitchItem(
                                    title = stringResource(R.string.characters),
                                    subtitle = "",
                                    checked = uiState.includeCharacters,
                                    onCheckedChange = onIncludeCharactersChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SettingsSwitchItem(
                                    title = stringResource(R.string.artifacts),
                                    subtitle = "",
                                    checked = uiState.includeArtifacts,
                                    onCheckedChange = onIncludeArtifactsChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SettingsSwitchItem(
                                    title = stringResource(R.string.weapons),
                                    subtitle = "",
                                    checked = uiState.includeWeapons,
                                    onCheckedChange = onIncludeWeaponsChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SettingsSwitchItem(
                                    title = stringResource(R.string.materials),
                                    subtitle = "",
                                    checked = uiState.includeMaterials,
                                    onCheckedChange = onIncludeMaterialsChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingsSection(title = stringResource(R.string.character_filters)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SliderItem(
                                    label = stringResource(R.string.min_level),
                                    value = uiState.minCharacterLevel,
                                    valueRange = 1..90,
                                    onValueChange = onMinCharacterLevelChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SliderItem(
                                    label = stringResource(R.string.min_ascension),
                                    value = uiState.minCharacterAscension,
                                    valueRange = 0..6,
                                    onValueChange = onMinCharacterAscensionChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            SliderItem(
                                label = stringResource(R.string.min_constellation),
                                value = uiState.minCharacterConstellation,
                                valueRange = 0..6,
                                onValueChange = onMinCharacterConstellationChange
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingsSection(title = stringResource(R.string.artifact_filters)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SliderItem(
                                    label = stringResource(R.string.min_level),
                                    value = uiState.minArtifactLevel,
                                    valueRange = 0..20,
                                    onValueChange = onMinArtifactLevelChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SliderItem(
                                    label = stringResource(R.string.min_rarity),
                                    value = uiState.minArtifactRarity,
                                    valueRange = 1..5,
                                    onValueChange = onMinArtifactRarityChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingsSection(title = stringResource(R.string.weapon_filters)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SliderItem(
                                    label = stringResource(R.string.min_level),
                                    value = uiState.minWeaponLevel,
                                    valueRange = 1..90,
                                    onValueChange = onMinWeaponLevelChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SliderItem(
                                    label = stringResource(R.string.min_refinement),
                                    value = uiState.minWeaponRefinement,
                                    valueRange = 1..5,
                                    onValueChange = onMinWeaponRefinementChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SliderItem(
                                    label = stringResource(R.string.min_ascension),
                                    value = uiState.minWeaponAscension,
                                    valueRange = 0..6,
                                    onValueChange = onMinWeaponAscensionChange,
                                    modifier = Modifier.weight(1f)
                                )
                                SliderItem(
                                    label = stringResource(R.string.min_rarity),
                                    value = uiState.minWeaponRarity,
                                    valueRange = 1..5,
                                    onValueChange = onMinWeaponRarityChange,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingsSection(title = stringResource(R.string.special_options)) {
                            SettingsSwitchItem(
                                title = stringResource(R.string.simulate_4th_substat),
                                subtitle = "",
                                checked = uiState.fakeInitialize4thLine,
                                onCheckedChange = onFakeInitialize4thLineChange,
                                accentColor = Accent
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Accent,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = SurfaceLight.copy(alpha = 0.8f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    accentColor: Color = Success,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable { onCheckedChange() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextHint
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = accentColor,
                checkedTrackColor = accentColor.copy(alpha = 0.35f),
                uncheckedTrackColor = Border.copy(alpha = 0.6f),
                uncheckedThumbColor = TextHint
            ),
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
fun SliderItem(
    label: String,
    value: Int,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceHighlight)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "$value",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = valueRange.last - valueRange.first - 1,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Border.copy(alpha = 0.5f)
            ),
            modifier = Modifier.height(36.dp)
        )
    }
}

@Composable
fun AchievementSettingsDialog(
    currentFormat: String,
    onFormatChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Border.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Surface.copy(alpha = 0.98f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.achievement_export_settings),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_error),
                            contentDescription = stringResource(R.string.close),
                            tint = TextHint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                AchievementFormatOption(
                    format = stringResource(R.string.format_uiaf),
                    description = stringResource(R.string.format_uiaf_desc),
                    isSelected = currentFormat == "UIAF",
                    onClick = { onFormatChange("UIAF") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                AchievementFormatOption(
                    format = stringResource(R.string.format_seelie),
                    description = stringResource(R.string.format_seelie_desc),
                    isSelected = currentFormat == "Seelie",
                    onClick = { onFormatChange("Seelie") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                AchievementFormatOption(
                    format = stringResource(R.string.format_csv),
                    description = stringResource(R.string.format_csv_desc),
                    isSelected = currentFormat == "CSV",
                    onClick = { onFormatChange("CSV") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss
                    ) {
                        Text(
                            text = stringResource(R.string.close),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Accent
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AchievementFormatOption(
    format: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Accent.copy(alpha = 0.15f) else SurfaceLight.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = if (isSelected) Accent else TextHint,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Accent)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = format,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) TextPrimary else TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextHint
                )
            }
        }
    }
}

private fun loadBackgroundImage(context: Context): ImageBitmap? {
    return try {
        context.assets.open("background.webp").use { inputStream ->
            val bitmap = BitmapFactory.decodeStream(inputStream)
            bitmap.asImageBitmap()
        }
    } catch (e: Exception) {
        null
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return format.format(date)
}