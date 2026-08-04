package com.example.radioarealocator.ui.screen.ft8

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.radioarealocator.ui.appViewModel
import com.example.radioarealocator.ui.viewmodel.Ft8ViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun Ft8SettingsScreen(
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = appViewModel<Ft8ViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config = uiState.config

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(32.dp))

        // 顶栏：返回 + 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
            Text(
                "FT8 设置",
                style = MiuixTheme.textStyles.title2,
                color = MiuixTheme.colorScheme.onSurface
            )
        }

        // 身份组
        SectionTitle("身份")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                TextField(
                    value = config.callsign,
                    onValueChange = { newValue ->
                        val filtered = newValue.uppercase().filter { it.isLetterOrDigit() }
                        if (filtered.length <= 12) {
                            viewModel.updateSettings { it.copy(callsign = filtered) }
                        }
                    },
                    label = "呼号 (Callsign)",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = config.grid,
                    onValueChange = { newValue ->
                        val filtered = newValue.uppercase().filter { it.isLetterOrDigit() }
                        if (filtered.length <= 6) {
                            viewModel.updateSettings { it.copy(grid = filtered) }
                        }
                    },
                    label = "网格 (Grid)",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
            }
        }

        // 波段组
        Spacer(Modifier.height(16.dp))
        SectionTitle("波段")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                for (band in com.example.radioarealocator.data.ft8.Ft8Band.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            band.displayName,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = config.band == band,
                            onCheckedChange = { checked ->
                                if (checked) viewModel.setBand(band)
                            }
                        )
                    }
                }
            }
        }

        // 发射开关
        Spacer(Modifier.height(16.dp))
        SectionTitle("发射")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "允许发射",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            "启用后可在 FT8 模式下发送消息",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = config.txEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateSettings { it.copy(txEnabled = enabled) }
                        }
                    )
                }
            }
        }

        // 信息卡
        Spacer(Modifier.height(16.dp))
        SectionTitle("FT8 编码信息")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "FT8 采用 8-FSK 调制, 每周期 15 秒发送 79 个音调。音频采样率 12000 Hz, 音调间隔 6.25 Hz。编码使用 (174,87) LDPC 纠错码。基于 FT8CN (BG7YOZ/N0BOY) 协议实现。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MiuixTheme.textStyles.body1,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
