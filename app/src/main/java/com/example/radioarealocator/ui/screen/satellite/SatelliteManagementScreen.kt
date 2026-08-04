package com.example.radioarealocator.ui.screen.satellite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.radioarealocator.R
import com.example.radioarealocator.data.satellite.SatelliteCatalog
import com.example.radioarealocator.data.satellite.SatelliteInfo
import com.example.radioarealocator.data.satellite.SatelliteStatusSegmenter
import com.example.radioarealocator.data.satellite.SegmentStatus
import com.example.radioarealocator.data.satellite.SatelliteStatusTracker
import com.example.radioarealocator.ui.LocalMainViewModel
import com.example.radioarealocator.ui.applyFilter
import com.example.radioarealocator.ui.isSatelliteSourceExpired
import com.example.radioarealocator.ui.navigation3.LocalNavigator
import com.example.radioarealocator.ui.theme.LocalCardAlpha
import com.example.radioarealocator.ui.theme.LocalEnableBlur
import com.example.radioarealocator.ui.theme.SafeColors
import com.example.radioarealocator.ui.util.BlurredBar
import com.example.radioarealocator.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 卫星管理页入口：按当前界面风格分发到 Miuix / Material 实现。
 */
@Composable
fun SatelliteManagementScreen() {
    when (com.example.radioarealocator.ui.LocalUiMode.current) {
        com.example.radioarealocator.ui.UiMode.Miuix -> SatelliteManagementMiuix()
        com.example.radioarealocator.ui.UiMode.Material -> SatelliteManagementMaterial()
    }
}

/**
 * 卫星管理页 Miuix 风格：展示附近过境卫星，支持筛选、收藏、实时 AMSAT 状态、
 * BJT 分段时间线、在境倒计时等。
 */
@Composable
fun SatelliteManagementMiuix() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val locationState by mainViewModel.locationState
    val satelliteState by mainViewModel.satelliteState
    val favorites by mainViewModel.favoriteSatellites
    val filter by mainViewModel.satelliteFilter
    var showFilterDialog by rememberSaveable { mutableStateOf(false) }

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                top.yukonga.miuix.kmp.basic.TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.satellite_management),
                    navigationIcon = {
                        Box(modifier = Modifier.padding(start = 12.dp)) {
                            IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        SatelliteManagementContent(
            locationState = locationState,
            satelliteState = satelliteState,
            filter = filter,
            favorites = favorites,
            statusTracker = mainViewModel.statusTracker,
            onToggleFavorite = mainViewModel::toggleFavorite,
            onNameQueryChange = { query ->
                mainViewModel.updateSatelliteFilter(filter.copy(nameQuery = query))
            },
            onShowFilterDialog = { showFilterDialog = true },
            onGetLocation = mainViewModel::refreshLocationOnly,
            onUpdateSource = mainViewModel::refreshSatelliteSourceOnly,
            contentPadding = innerPadding
        )

        if (showFilterDialog) {
            Dialog(
                onDismissRequest = { showFilterDialog = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                top.yukonga.miuix.kmp.basic.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = colorScheme.background
                ) {
                    SatelliteFilterDialogContent(onDismiss = { showFilterDialog = false })
                }
            }
        }
    }
}

@Composable
private fun SatelliteManagementContent(
    locationState: com.example.radioarealocator.ui.LocationUiState,
    satelliteState: com.example.radioarealocator.ui.SatelliteUiState,
    filter: com.example.radioarealocator.ui.SatelliteFilter,
    favorites: Set<Int>,
    statusTracker: SatelliteStatusTracker,
    onToggleFavorite: (Int) -> Unit,
    onNameQueryChange: (String) -> Unit,
    onShowFilterDialog: () -> Unit,
    onGetLocation: () -> Unit,
    onUpdateSource: () -> Unit,
    contentPadding: PaddingValues
) {
    // 订阅 AMSAT 状态字典，状态变化时触发重组以更新 effectiveStatus / isInherited
    @Suppress("UnusedVariable")
    val statusEntries = statusTracker.statusMap.value

    // 应用筛选
    val filteredSatellites = remember(satelliteState.satellites, filter, favorites) {
        satelliteState.satellites.applyFilter(filter, favorites)
    }
    val totalCount = satelliteState.satellites.size
    val favoriteCount = satelliteState.satellites.count { it.catalogNumber in favorites }

    // 统一倒计时时钟：仅当有在境卫星时每 5 秒更新，避免每颗卫星各自 LaunchedEffect
    var inPassNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasInPassSatellites = filteredSatellites.any { it.isCurrentlyVisible }
    LaunchedEffect(hasInPassSatellites) {
        if (hasInPassSatellites) {
            while (true) {
                inPassNowMillis = System.currentTimeMillis()
                delay(5000)
            }
        }
    }

    // 预计算每颗卫星的有效状态（含延续标记），避免 items 块内逐项查询触发重组
    val statusCache = remember(statusEntries, filteredSatellites) {
        filteredSatellites.associate { sat ->
            val amsatName = SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER[sat.catalogNumber]
            val statusQuery = if (amsatName != null) statusTracker.queryStatus(amsatName) else null
            val effectiveStatus = statusQuery?.status?.takeIf { it.isNotBlank() } ?: sat.status
            val isInherited = statusQuery?.isInherited ?: false
            sat.catalogNumber to (effectiveStatus to isInherited)
        }
    }

    // 排序：收藏优先 → 在境优先 → AOS 升序
    val sortedSatellites = remember(filteredSatellites, favorites) {
        filteredSatellites.sortedWith(
            compareByDescending<SatelliteInfo> { it.catalogNumber in favorites }
                .thenByDescending { it.isCurrentlyVisible }
                .thenBy { it.aosTime }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        overscrollEffect = null,
    ) {
        // 数据源刷新 + 统计 + 筛选入口合并为一张卡（减少卡片间距，列表起点上移）
        item {
            SatelliteOverviewCard(
                isLoading = locationState.isLoading,
                isSatelliteLoading = satelliteState.isSatelliteLoading,
                lastLocationTime = locationState.lastLocationUpdateTime,
                lastLocationCity = locationState.lastLocationCity,
                lastSatelliteTime = satelliteState.lastSatelliteUpdateTime,
                totalCount = totalCount,
                filteredCount = filteredSatellites.size,
                favoriteCount = favoriteCount,
                filter = filter,
                onNameQueryChange = onNameQueryChange,
                onShowFilterDialog = onShowFilterDialog,
                onGetLocation = onGetLocation,
                onUpdateSource = onUpdateSource
            )
        }

        // 占位态 / 列表
        when {
            satelliteState.isSatelliteLoading && filteredSatellites.isEmpty() -> {
                item { SatellitePlaceholderCard { Text(stringResource(R.string.processing)) } }
            }
            satelliteState.satelliteError != null -> {
                item {
                    SatellitePlaceholderCard {
                        Text(
                            text = stringResource(R.string.satellite_load_failed, satelliteState.satelliteError),
                            color = colorScheme.onError
                        )
                    }
                }
            }
            locationState.result == null -> {
                item { SatellitePlaceholderCard { Text(stringResource(R.string.satellite_need_location)) } }
            }
            filteredSatellites.isEmpty() -> {
                item {
                    SatellitePlaceholderCard {
                        Text(
                            stringResource(
                                if (filter.isActive) R.string.no_satellites_filtered
                                else R.string.no_satellites
                            )
                        )
                    }
                }
            }
            else -> {
                items(items = sortedSatellites, key = { it.catalogNumber }) { sat ->
                    val (effectiveStatus, isInherited) = statusCache[sat.catalogNumber]
                        ?: (sat.status to false)
                    SatelliteManagementItem(
                        satellite = sat,
                        effectiveStatus = effectiveStatus,
                        isFavorite = sat.catalogNumber in favorites,
                        isStatusInherited = isInherited,
                        nowMillis = if (sat.isCurrentlyVisible) inPassNowMillis else 0L,
                        statusSegments = satelliteState.segmentStatuses[sat.catalogNumber],
                        onToggleFavorite = { onToggleFavorite(sat.catalogNumber) }
                    )
                }
            }
        }
    }
}

// ---- 概览卡（数据源刷新 + 统计 + 筛选入口合并）----

@Composable
private fun SatelliteOverviewCard(
    isLoading: Boolean,
    isSatelliteLoading: Boolean,
    lastLocationTime: Instant?,
    lastLocationCity: String,
    lastSatelliteTime: Instant?,
    totalCount: Int,
    filteredCount: Int,
    favoriteCount: Int,
    filter: com.example.radioarealocator.ui.SatelliteFilter,
    onNameQueryChange: (String) -> Unit,
    onShowFilterDialog: () -> Unit,
    onGetLocation: () -> Unit,
    onUpdateSource: () -> Unit
) {
    val dateTimeFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") }
    val zoneId = remember { ZoneId.systemDefault() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 名称搜索框（移自筛选页，置于顶部最易触达位置）
            top.yukonga.miuix.kmp.basic.TextField(
                value = filter.nameQuery,
                onValueChange = onNameQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.filter_search_hint),
                singleLine = true
            )

            // 描述 + 统计
            Text(
                text = stringResource(R.string.satellite_management_desc),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ManagementStat(
                    label = stringResource(R.string.satellite_count, totalCount),
                    value = totalCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                ManagementStat(
                    label = stringResource(R.string.favorites_count),
                    value = favoriteCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            // 筛选计数 + 筛选按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 筛选按钮：始终固定在左侧
                SatelliteFilterButton(
                    filter = filter,
                    onClick = onShowFilterDialog
                )

                // 计数文字：激活时从按钮右侧淡入，不改变按钮位置
                androidx.compose.animation.AnimatedVisibility(
                    visible = filter.isActive && totalCount > 0,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally()
                ) {
                    Text(
                        text = stringResource(R.string.satellite_count_filtered, filteredCount, totalCount),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }

            // 数据源刷新操作
            ActionRow(
                buttonText = stringResource(R.string.sat_action_get_location),
                isLoading = isLoading,
                enabled = !isLoading,
                onClick = onGetLocation,
                primaryText = if (lastLocationTime != null) {
                    lastLocationTime.atZone(zoneId).format(dateTimeFormatter)
                } else {
                    stringResource(R.string.sat_no_location_time)
                },
                secondaryText = lastLocationCity.ifBlank { stringResource(R.string.sat_no_city) }
            )
            ActionRow(
                buttonText = stringResource(R.string.sat_action_update_source),
                isLoading = isSatelliteLoading,
                enabled = !isSatelliteLoading,
                onClick = onUpdateSource,
                primaryText = if (lastSatelliteTime != null) {
                    lastSatelliteTime.atZone(zoneId).format(dateTimeFormatter)
                } else {
                    stringResource(R.string.sat_no_source_time)
                },
                secondaryText = if (isSatelliteSourceExpired(lastSatelliteTime)) {
                    stringResource(R.string.sat_source_expired)
                } else {
                    stringResource(R.string.sat_source_fresh)
                },
                secondaryColor = if (isSatelliteSourceExpired(lastSatelliteTime)) {
                    colorScheme.onError
                } else {
                    colorScheme.onSurfaceVariantSummary
                }
            )
        }
    }
}

@Composable
private fun ActionRow(
    buttonText: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    primaryText: String,
    secondaryText: String,
    secondaryColor: Color = colorScheme.onSurfaceVariantSummary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Text(buttonText)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primaryText,
                fontSize = 14.sp,
                color = colorScheme.onSurface
            )
            Text(
                text = secondaryText,
                fontSize = 12.sp,
                color = secondaryColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ManagementStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colorScheme.primary
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary
        )
    }
}

// ---- 筛选入口 ----

@Composable
private fun SatelliteFilterButton(
    filter: com.example.radioarealocator.ui.SatelliteFilter,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.FilterList,
            contentDescription = null,
            tint = if (filter.isActive) colorScheme.primary else colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = stringResource(R.string.filter_title),
            fontSize = 13.sp,
            color = if (filter.isActive) colorScheme.primary else colorScheme.onSurfaceVariantSummary
        )
        if (filter.isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primary)
            )
        }
    }
}

// ---- 卫星列表项 ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SatelliteManagementItem(
    satellite: SatelliteInfo,
    effectiveStatus: String,
    isFavorite: Boolean,
    isStatusInherited: Boolean,
    nowMillis: Long,
    statusSegments: List<SegmentStatus>?,
    onToggleFavorite: () -> Unit
) {
    // 分段时间线默认折叠，点击卡片展开
    var expanded by rememberSaveable(satellite.catalogNumber) { mutableStateOf(false) }

    val timeInfo = remember(satellite.aosTime, satellite.losTime, satellite.isCurrentlyVisible, nowMillis) {
        val formatter = satelliteTimeFormatter
        val zone = ZoneId.systemDefault()
        if (satellite.isCurrentlyVisible) {
            val losTime = satellite.losTime.atZone(zone).format(formatter)
            val now = if (nowMillis > 0) Instant.ofEpochMilli(nowMillis) else Instant.now()
            val remainingSeconds = Duration.between(now, satellite.losTime).seconds
            val remainingText = formatRemainingTime(remainingSeconds)
            SatelliteTimeInfo.InPass(losTime, remainingText)
        } else {
            val aosTime = satellite.aosTime.atZone(zone).format(formatter)
            SatelliteTimeInfo.Upcoming(aosTime)
        }
    }

    // 强调色：收藏用 tertiaryContainer 背景；在境仅加 primary 边框，背景保持 surface
    val cardContainerColor = when {
        isFavorite -> colorScheme.tertiaryContainer
        else -> colorScheme.surface
    }
    val cardContentColor = when {
        isFavorite -> colorScheme.onTertiaryContainer
        else -> colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (satellite.isCurrentlyVisible) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = {
                // 点击切换展开/折叠，分段时间线默认隐藏，降低卡片高度
                expanded = !expanded
            }),
        colors = CardDefaults.defaultColors(
            color = cardContainerColor.copy(alpha = LocalCardAlpha.current),
            contentColor = cardContentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 第一行：名 + 仰角 + 收藏按钮（删除重复星标和状态点）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = satellite.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = cardContentColor
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${satellite.maxElevation.toInt()}°",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cardContentColor
                    )
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = null,
                            tint = if (isFavorite) colorScheme.onTertiaryContainer else colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Chips 流：Source / Status / Mode
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (effectiveStatus.isNotEmpty()) {
                    StatusChip(status = effectiveStatus, isStatusInherited = isStatusInherited)
                }
                if (satellite.modes.isEmpty()) {
                    ModeChip(mode = stringResource(R.string.mode_unknown))
                } else {
                    satellite.modes.forEach { mode -> ModeChip(mode = mode) }
                }
            }

            // BJT 分段状态时间线：默认折叠，点击卡片展开
            if (expanded) {
                SatelliteStatusSegments(statusSegments)
            }

            Spacer(Modifier.height(10.dp))

            // 时间徽章 + 在境进度条
            when (timeInfo) {
                is SatelliteTimeInfo.InPass -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TimeBadge(
                            label = stringResource(R.string.los_time),
                            value = timeInfo.losTime,
                            isActive = true
                        )
                        TimeBadge(
                            label = stringResource(R.string.time_remaining),
                            value = timeInfo.remainingText,
                            isActive = true
                        )
                    }
                }
                is SatelliteTimeInfo.Upcoming -> {
                    TimeBadge(
                        label = stringResource(R.string.aos_time),
                        value = timeInfo.aosTime,
                        isActive = false
                    )
                }
            }
        }
    }
}

// ---- 时间相关辅助 ----

private val satelliteTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private sealed class SatelliteTimeInfo {
    data class InPass(val losTime: String, val remainingText: String) : SatelliteTimeInfo()
    data class Upcoming(val aosTime: String) : SatelliteTimeInfo()
}

private fun formatRemainingTime(seconds: Long): String {
    if (seconds <= 0) return "0秒"
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) "${minutes}分${remainingSeconds}秒" else "${remainingSeconds}秒"
}

@Composable
private fun TimeBadge(label: String, value: String, isActive: Boolean) {
    val containerColor = if (isActive) {
        colorScheme.primary.copy(alpha = 0.15f)
    } else {
        colorScheme.surfaceVariant
    }
    val contentColor = if (isActive) colorScheme.primary else colorScheme.onSurfaceVariantSummary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$label：",
            fontSize = 11.sp,
            color = contentColor
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}

// ---- Chips ----

@Composable
private fun StatusChip(status: String, isStatusInherited: Boolean = false) {
    val baseText = when (status) {
        "Heard" -> stringResource(R.string.status_heard)
        "Telemetry Only" -> stringResource(R.string.status_telemetry_only)
        "Not Heard" -> stringResource(R.string.status_not_heard)
        "Crew Active" -> stringResource(R.string.status_crew_active)
        else -> status
    }
    val displayText = if (isStatusInherited) "$baseText *" else baseText
    val (bgColor, contentColor) = when (status) {
        "Heard" -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        "Telemetry Only" -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
        "Not Heard" -> SafeColors.errorContainer to SafeColors.errorIcon
        "Crew Active" -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
        else -> colorScheme.surfaceVariant to colorScheme.onSurfaceVariantSummary
    }
    val finalBg = if (isStatusInherited) bgColor.copy(alpha = 0.85f) else bgColor
    val finalContent = if (isStatusInherited) contentColor.copy(alpha = 0.85f) else contentColor
    Chip(text = displayText, bgColor = finalBg, contentColor = finalContent)
}

@Composable
private fun ModeChip(mode: String) {
    val (bgColor, contentColor) = when (mode.uppercase()) {
        "FM" -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        "SSTV" -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
        "DSTAR" -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
        "CW" -> SafeColors.errorContainer to SafeColors.errorIcon
        else -> colorScheme.surfaceVariant to colorScheme.onSurfaceVariantSummary
    }
    Chip(text = mode, bgColor = bgColor, contentColor = contentColor)
}

@Composable
private fun Chip(text: String, bgColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = contentColor,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---- BJT 分段状态时间线 ----

@Composable
private fun SatelliteStatusSegments(segments: List<SegmentStatus>?) {
    if (segments.isNullOrEmpty()) return
    val today = SatelliteStatusSegmenter.dateOf(Instant.now())
    val daySegments = remember(segments, today) {
        SatelliteStatusSegmenter.segmentsForDate(segments, today)
            .ifEmpty { segments.takeLast(4) }
    }
    if (daySegments.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.status_segment_title),
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            daySegments.forEach { seg ->
                SegmentCell(segment = seg, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SegmentCell(segment: SegmentStatus, modifier: Modifier = Modifier) {
    val displayText = when (segment.status) {
        "Heard" -> stringResource(R.string.status_heard)
        "Telemetry Only" -> stringResource(R.string.status_telemetry_only)
        "Not Heard" -> stringResource(R.string.status_not_heard)
        "Crew Active" -> stringResource(R.string.status_crew_active)
        null -> stringResource(R.string.status_no_data)
        else -> segment.status
    }
    val bgColor = when (segment.status) {
        "Heard" -> colorScheme.primaryContainer
        "Telemetry Only" -> colorScheme.secondaryContainer
        "Not Heard" -> SafeColors.errorContainer
        "Crew Active" -> colorScheme.tertiaryContainer
        else -> colorScheme.surfaceVariant
    }
    val contentColor = when (segment.status) {
        "Heard" -> colorScheme.onPrimaryContainer
        "Telemetry Only" -> colorScheme.onSecondaryContainer
        "Not Heard" -> SafeColors.errorIcon
        "Crew Active" -> colorScheme.onTertiaryContainer
        else -> colorScheme.onSurfaceVariantSummary
    }
    val rangeLabel = remember(segment.segment) {
        "${segment.segment.startHour.toString().padStart(2, '0')}" +
            "-${segment.segment.endHour.toString().padStart(2, '0')}"
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = rangeLabel,
            fontSize = 11.sp,
            color = contentColor,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = displayText,
            fontSize = 11.sp,
            color = contentColor
        )
    }
}

// ---- 占位卡 ----

@Composable
private fun SatellitePlaceholderCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}
