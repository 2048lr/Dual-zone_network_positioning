package com.example.hamkit.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.ft8.Ft8AudioPlayer
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.data.ft8.Ft8Config
import com.example.hamkit.data.ft8.Ft8Decoder
import com.example.hamkit.data.ft8.Ft8Encoder
import com.example.hamkit.data.ft8.Ft8QsoRecord
import com.example.hamkit.data.ft8.Ft8Recorder
import com.example.hamkit.data.ft8.Ft8SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Ft8DecodedEntry(
    val time: Long = System.currentTimeMillis(),
    val result: Ft8Decoder.DecodeResult,
)
data class Ft8UiState(
    val config: Ft8Config = Ft8Config(),
    val isEncoding: Boolean = false,
    val generatedPcm: ShortArray? = null,
    val lastMessageText: String = "",
    val isPlaying: Boolean = false,
    val isDecoding: Boolean = false,
    val decodeStatus: String = "",
    val decodedEntries: List<Ft8DecodedEntry> = emptyList(),
    val qsoRecords: List<Ft8QsoRecord> = emptyList(),
    val lastError: String? = null,
)

class Ft8ViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = Ft8SettingsStore(application)
    private val audioPlayer = Ft8AudioPlayer()
    private lateinit var recorder: Ft8Recorder

    private val _uiState = MutableStateFlow(
        Ft8UiState(config = settingsStore.toConfig())
    )
    val uiState: StateFlow<Ft8UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(config = settingsStore.toConfig()) }
        }
    }

    fun updateSettings(transform: (Ft8Config) -> Ft8Config) {
        val newConfig = transform(_uiState.value.config)
        settingsStore.fromConfig(newConfig)
        _uiState.update { it.copy(config = newConfig) }
    }

    fun setBand(band: Ft8Band) {
        updateSettings { it.copy(band = band) }
    }

    fun encodeMessage(messageText: String) {
        val config = _uiState.value.config
        if (config.callsign.isBlank()) {
            _uiState.update { it.copy(lastError = "请先设置呼号") }
            return
        }

        _uiState.update { it.copy(isEncoding = true, lastError = null) }

        viewModelScope.launch {
            try {
                val pcm = withContext(Dispatchers.Default) {
                    Ft8Encoder.encodeToPcm(
                        messageText = messageText,
                        callsign = config.callsign,
                        grid = config.grid
                    )
                }
                _uiState.update {
                    it.copy(
                        isEncoding = false,
                        generatedPcm = pcm,
                        lastMessageText = messageText,
                    )
                }
                playPcm(pcm)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isEncoding = false,
                        lastError = "编码失败: ${e.message}",
                    )
                }
            }
        }
    }

    fun playPcm(pcm: ShortArray?) {
        val data = pcm ?: _uiState.value.generatedPcm ?: return
        if (_uiState.value.isPlaying) return
        _uiState.update { it.copy(isPlaying = true, lastError = null) }
        audioPlayer.play(data) {
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    fun stopPlayback() {
        audioPlayer.stop()
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
    }

    fun notifyPermissionDenied() {
        _uiState.update { it.copy(decodeStatus = "未授予录音权限，无法解码", lastError = "需要麦克风权限才能解码 FT8 信号") }
    }

    fun startDecoding() {
        if (_uiState.value.isDecoding) return
        stopPlayback()
        recorder = Ft8Recorder(
            context = getApplication(),
            onDecoded = { result ->
                _uiState.update {
                    val entries = it.decodedEntries + Ft8DecodedEntry(result = result)
                    it.copy(
                        decodedEntries = entries.takeLast(MAX_DECODED_ENTRIES),
                        decodeStatus = if (result.success) {
                            "解码成功: ${result.messageText} (SNR ${result.snr} dB)"
                        } else {
                            "未检测到信号: ${result.messageText}"
                        },
                    )
                }
            },
            onStatus = { status ->
                _uiState.update { it.copy(decodeStatus = status) }
            },
        )
        _uiState.update { it.copy(isDecoding = true, decodeStatus = "启动录音…", lastError = null) }
        recorder.start()
    }

    fun stopDecoding() {
        if (!_uiState.value.isDecoding) return
        recorder.stop()
        _uiState.update { it.copy(isDecoding = false, decodeStatus = "已停止解码") }
    }

    fun clearDecoded() {
        _uiState.update { it.copy(decodedEntries = emptyList()) }
    }

    override fun onCleared() {
        audioPlayer.stop()
        if (::recorder.isInitialized) {
            runCatching { recorder.stop() }
        }
        super.onCleared()
    }

    companion object {
        private const val MAX_DECODED_ENTRIES = 50
    }
}
