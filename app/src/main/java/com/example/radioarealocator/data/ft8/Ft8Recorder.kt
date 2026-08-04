package com.example.radioarealocator.data.ft8

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * FT8 麦克风录音器：AudioRecord 连续采集，后台线程滑动窗口解码。
 *
 * 采样率 12000 Hz (FT8 标准), 每帧约 15s。录音线程持续写入环形缓冲，
 * 解码线程每 [DECODE_INTERVAL_MS] 从最新数据截取一个窗口尝试解码，
 * 解码结果通过 [onDecoded] 回调。
 */
class Ft8Recorder(
    private val context: Context,
    private val onDecoded: (Ft8Decoder.DecodeResult) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    val isRecording: Boolean
        get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        if (!hasPermission()) {
            onStatus("缺少录音权限")
            return
        }
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private suspend fun CoroutineScope.runLoop() {
        try {
            val sampleRate = Ft8Encoder.SAMPLE_RATE
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 缓冲足够大以容纳一个完整帧 (15s) + 余量
            val bufferSize = maxOf(minBuffer, sampleRate * 16)

            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .build()
            audioRecord = record

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onStatus("录音初始化失败")
                return
            }

            record.startRecording()
            onStatus("正在解码…")

            // 录音线程：持续读入环形缓冲
            val readThread = Thread {
                android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val readBuf = ShortArray(sampleRate / 10) // 0.1s 块
                while (scope.isActive) {
                    val read = record.read(readBuf, 0, readBuf.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        synchronized(ringLock) {
                            for (i in 0 until read) {
                                ringBuffer[ringWrite] = readBuf[i]
                                ringWrite = (ringWrite + 1) % ringBuffer.size
                                if (ringWrite == ringRead) {
                                    ringRead = (ringRead + 1) % ringBuffer.size
                                }
                            }
                        }
                    } else if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                        break
                    }
                }
            }
            readThread.start()

            // 解码线程：每 DECODE_INTERVAL_MS 尝试解码一次
            while (isActive) {
                delay(DECODE_INTERVAL_MS)
                val window = readLatestWindow()
                if (window.size >= Ft8Encoder.SAMPLE_RATE * 12) {
                    val result = withContextSafe(window)
                    onDecoded(result)
                }
            }

            readThread.join(1000)
            runCatching { record.stop() }
            runCatching { record.release() }
            audioRecord = null
        } catch (e: Exception) {
            onStatus("录音错误: ${e.message}")
        }
    }

    private val ringLock = Any()
    private val ringBuffer = ShortArray(Ft8Encoder.SAMPLE_RATE * 16)
    private var ringRead = 0
    private var ringWrite = 0

    private fun readLatestWindow(): ShortArray {
        val windowSamples = Ft8Encoder.SAMPLE_RATE * 16
        val out = ShortArray(windowSamples)
        synchronized(ringLock) {
            val available = if (ringWrite >= ringRead) {
                ringWrite - ringRead
            } else {
                ringBuffer.size - ringRead + ringWrite
            }
            val take = min(available, windowSamples)
            val start = (ringWrite - take + ringBuffer.size) % ringBuffer.size
            for (i in 0 until take) {
                out[i] = ringBuffer[(start + i) % ringBuffer.size]
            }
        }
        return out
    }

    private suspend fun withContextSafe(window: ShortArray): Ft8Decoder.DecodeResult =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            Ft8Decoder.decode(window)
        }

    companion object {
        private const val DECODE_INTERVAL_MS = 1500L
    }
}
