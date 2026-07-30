package com.example.radioarealocator.ui.screen.satellite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.radioarealocator.R
import com.example.radioarealocator.ui.LocalMainViewModel
import com.example.radioarealocator.ui.LocalUiMode
import com.example.radioarealocator.ui.SatelliteFilter
import com.example.radioarealocator.ui.UiMode
import com.example.radioarealocator.ui.navigation3.LocalNavigator
import com.example.radioarealocator.ui.theme.LocalCardAlpha
import com.example.radioarealocator.ui.theme.LocalEnableBlur
import com.example.radioarealocator.ui.util.BlurredBar
import com.example.radioarealocator.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 卫星筛选子页面入口：按名称/来源/模式/状态筛选卫星列表。
 * 由卫星管理页的筛选按钮导航进入，修改即时写回 [com.example.radioarealocator.ui.MainViewModel]，
 * 返回（pop）后管理页列表自动反映新筛选条件。
 */
@Composable
fun SatelliteFilterScreen() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SatelliteFilterMiuix()
        UiMode.Material -> SatelliteFilterMaterial()
    }
}

// ---------------- Miuix 风格 ----------------

@Composable
private fun SatelliteFilterMiuix() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val filter by mainViewModel.satelliteFilter
    val onFilterChange: (SatelliteFilter) -> Unit = mainViewModel::updateSatelliteFilter

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()

    val unknownModeLabel = stringResource(R.string.filter_mode_unknown)
    val modeOptions = listOf(
        "FM" to "FM",
        "SSTV" to "SSTV",
        "DSTAR" to "D-Star",
        "CW" to "CW",
        "USB" to "USB",
        "LSB" to "LSB",
        "" to unknownModeLabel
    )
    val sourceOptions = listOf(
        "CT" to stringResource(R.string.source_ct),
        "SNOGS" to stringResource(R.string.source_snogs),
        "ALL" to stringResource(R.string.source_all)
    )

    MiuixScaffold(
        topBar = {
            BlurredBar(backdrop) {
                MiuixTopAppBar(
                    color = barColor,
                    title = stringResource(R.string.filter_title),
                    navigationIcon = {
                        Box(modifier = Modifier.padding(start = 12.dp)) {
                            MiuixIconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                                MiuixIcon(
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .overScrollVertical()
                .scrollEndHaptic(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null,
        ) {
            // 顶部重置行
            if (filter.isActive) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        MiuixTextButton(
                            text = stringResource(R.string.filter_reset),
                            onClick = { onFilterChange(SatelliteFilter()) }
                        )
                    }
                }
            }

            // 名称搜索
            item {
                MiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = MiuixCardDefaults.defaultColors(
                        color = colorScheme.surface.copy(alpha = LocalCardAlpha.current)
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        MiuixTextField(
                            value = filter.nameQuery,
                            onValueChange = { onFilterChange(filter.copy(nameQuery = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.filter_search_hint),
                            singleLine = true
                        )
                    }
                }
            }

            // 数据来源
            item {
                MiuixFilterSectionCard(title = stringResource(R.string.filter_source_section)) {
                    sourceOptions.forEach { (value, label) ->
                        val selected = value in filter.sources
                        MiuixFilterSelectableRow(
                            label = label,
                            selected = selected,
                            onClick = {
                                val newSources = if (selected) filter.sources - value else filter.sources + value
                                onFilterChange(filter.copy(sources = newSources))
                            }
                        )
                    }
                }
            }

            // 工作模式
            item {
                MiuixFilterSectionCard(title = stringResource(R.string.filter_mode_section)) {
                    modeOptions.forEach { (value, label) ->
                        val selected = value in filter.modes
                        MiuixFilterSelectableRow(
                            label = label,
                            selected = selected,
                            onClick = {
                                val newModes = if (selected) filter.modes - value else filter.modes + value
                                onFilterChange(filter.copy(modes = newModes))
                            }
                        )
                    }
                }
            }

            // 开关筛选
            item {
                MiuixFilterSectionCard(title = stringResource(R.string.filter_status_section)) {
                    MiuixFilterSwitchRow(
                        label = stringResource(R.string.filter_only_in_pass),
                        checked = filter.onlyInPass,
                        onClick = {
                            onFilterChange(filter.copy(onlyInPass = !filter.onlyInPass, onlyUpcoming = false))
                        }
                    )
                    MiuixFilterSwitchRow(
                        label = stringResource(R.string.filter_only_upcoming),
                        checked = filter.onlyUpcoming,
                        onClick = {
                            onFilterChange(filter.copy(onlyUpcoming = !filter.onlyUpcoming, onlyInPass = false))
                        }
                    )
                    MiuixFilterSwitchRow(
                        label = stringResource(R.string.filter_only_amsat),
                        checked = filter.onlyAmsat,
                        onClick = { onFilterChange(filter.copy(onlyAmsat = !filter.onlyAmsat)) }
                    )
                    MiuixFilterSwitchRow(
                        label = stringResource(R.string.filter_only_favorites),
                        checked = filter.onlyFavorites,
                        onClick = { onFilterChange(filter.copy(onlyFavorites = !filter.onlyFavorites)) }
                    )
                }
            }

            // 应用筛选
            item {
                MiuixButton(
                    onClick = { navigator.pop() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    MiuixText(text = stringResource(R.string.filter_apply))
                }
            }
        }
    }
}

@Composable
private fun MiuixFilterSectionCard(title: String, content: @Composable () -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        colors = MiuixCardDefaults.defaultColors(
            color = colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            MiuixText(
                text = title,
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun MiuixFilterSelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MiuixText(
            text = label,
            fontSize = 14.sp,
            color = colorScheme.onSurface
        )
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) colorScheme.primary else colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                MiuixText(
                    text = "✓",
                    fontSize = 13.sp,
                    color = colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MiuixFilterSwitchRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MiuixText(
            text = label,
            fontSize = 14.sp,
            color = colorScheme.onSurface
        )
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (checked) colorScheme.primary else colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .then(if (checked) Modifier.offset(x = 18.dp) else Modifier)
            )
        }
    }
}

// ---------------- Material 风格 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SatelliteFilterMaterial() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val filter by mainViewModel.satelliteFilter
    val onFilterChange: (SatelliteFilter) -> Unit = mainViewModel::updateSatelliteFilter

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val unknownModeLabel = stringResource(R.string.filter_mode_unknown)
    val modeOptions = listOf(
        "FM" to "FM",
        "SSTV" to "SSTV",
        "DSTAR" to "D-Star",
        "CW" to "CW",
        "USB" to "USB",
        "LSB" to "LSB",
        "" to unknownModeLabel
    )
    val sourceOptions = listOf(
        "CT" to stringResource(R.string.source_ct),
        "SNOGS" to stringResource(R.string.source_snogs),
        "ALL" to stringResource(R.string.source_all)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.filter_title)) },
                navigationIcon = {
                    IconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    if (filter.isActive) {
                        TextButton(onClick = { onFilterChange(SatelliteFilter()) }) {
                            Text(
                                text = stringResource(R.string.filter_reset),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 名称搜索
            item {
                MaterialFilterSectionCard(title = stringResource(R.string.filter_search_hint)) {
                    OutlinedTextField(
                        value = filter.nameQuery,
                        onValueChange = { onFilterChange(filter.copy(nameQuery = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.filter_search_hint)) }
                    )
                }
            }

            // 数据来源
            item {
                MaterialFilterSectionCard(title = stringResource(R.string.filter_source_section)) {
                    sourceOptions.forEach { (value, label) ->
                        val selected = value in filter.sources
                        FilterCheckRowMaterial(
                            label = label,
                            checked = selected,
                            onToggle = {
                                val newSources = if (selected) filter.sources - value else filter.sources + value
                                onFilterChange(filter.copy(sources = newSources))
                            }
                        )
                    }
                }
            }

            // 工作模式
            item {
                MaterialFilterSectionCard(title = stringResource(R.string.filter_mode_section)) {
                    modeOptions.forEach { (value, label) ->
                        val selected = value in filter.modes
                        FilterCheckRowMaterial(
                            label = label,
                            checked = selected,
                            onToggle = {
                                val newModes = if (selected) filter.modes - value else filter.modes + value
                                onFilterChange(filter.copy(modes = newModes))
                            }
                        )
                    }
                }
            }

            // 开关筛选
            item {
                MaterialFilterSectionCard(title = stringResource(R.string.filter_status_section)) {
                    FilterSwitchRowMaterial(
                        label = stringResource(R.string.filter_only_in_pass),
                        checked = filter.onlyInPass,
                        onToggle = {
                            onFilterChange(filter.copy(onlyInPass = !filter.onlyInPass, onlyUpcoming = false))
                        }
                    )
                    FilterSwitchRowMaterial(
                        label = stringResource(R.string.filter_only_upcoming),
                        checked = filter.onlyUpcoming,
                        onToggle = {
                            onFilterChange(filter.copy(onlyUpcoming = !filter.onlyUpcoming, onlyInPass = false))
                        }
                    )
                    FilterSwitchRowMaterial(
                        label = stringResource(R.string.filter_only_amsat),
                        checked = filter.onlyAmsat,
                        onToggle = { onFilterChange(filter.copy(onlyAmsat = !filter.onlyAmsat)) }
                    )
                    FilterSwitchRowMaterial(
                        label = stringResource(R.string.filter_only_favorites),
                        checked = filter.onlyFavorites,
                        onToggle = { onFilterChange(filter.copy(onlyFavorites = !filter.onlyFavorites)) }
                    )
                }
            }

            // 应用筛选
            item {
                Button(
                    onClick = { navigator.pop() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.filter_apply))
                }
            }
        }
    }
}

@Composable
private fun MaterialFilterSectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun FilterCheckRowMaterial(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun FilterSwitchRowMaterial(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
