package com.example.hamkit.data.ft8

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class Ft8AudioPlayer {

    private val lock = Any()
    @Volatile private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    private var currentPcm: ShortArray? = null

    /**
     * 播放一段已生成的 FT8 PCM（16-bit mono）。
     *
     * @param pcm 编码后的音频样本（[Ft8Encoder.SAMPLE_RATE] Hz）
     * @param onComplete 播放完成或停止后的回调
     */
    fun play(pcm: ShortArray, onComplete: () -> Unit) {
        synchronized(lock) {
            if (isPlaying) return
            currentPcm = pcm
            isPlaying = true
        }

        Thread {
            try {
                val sampleRate = Ft8Encoder.SAMPLE_RATE
                val bufferSize = maxOf(
                    AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ),
                    pcm.size * 2
                )

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                synchronized(lock) {
                    if (!isPlaying) {
                        track.release()
                        return@Thread
                    }
                    audioTrack = track
                }

                track.write(pcm, 0, pcm.size)
                track.play()

                // MODE_STATIC 的 play() 为异步，按样本数计算播放时长并等待完成
                val playDurationMs = (pcm.size * 1000L) / sampleRate
                val startMs = System.currentTimeMillis()
                while (isPlaying) {
                    val elapsed = System.currentTimeMillis() - startMs
                    if (elapsed >= playDurationMs) break
                    Thread.sleep(50)
                }

                synchronized(lock) {
                    audioTrack = null
                    track.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isPlaying = false
                try { onComplete() } catch (_: Exception) {}
            }
        }.start()
    }

    fun stop() {
        synchronized(lock) {
            isPlaying = false
            try { audioTrack?.pause() } catch (_: Exception) {}
            try { audioTrack?.flush() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
        }
    }

    fun isPlaying(): Boolean = isPlaying
}
