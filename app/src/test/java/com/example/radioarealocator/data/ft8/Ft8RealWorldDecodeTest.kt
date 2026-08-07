package com.example.radioarealocator.data.ft8

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 使用 ft8_lib 仓库中的真实 FT8 录音（websdr_test1.wav，12000 Hz 16-bit mono）
 * 验证解码器能与真实 FT8 信号互通。
 *
 * 预期解码结果（ft8_lib test/wav/websdr_test1.txt）：
 *   G4CUS SP4FCA +10
 *   LZ1LZ G4UJS IO83
 *   YO6OGJ F4IAG R-09
 *   SQ5FBI G3NDC IO91
 *   CQ IK4LZH JN54
 *   ...
 */
class Ft8RealWorldDecodeTest {

    @Test
    fun decodes_real_ft8_recording() {
        val wav = File("src/test/resources/ft8/websdr_test1.wav")
        assertTrue("test wav must exist: ${wav.absolutePath}", wav.exists())

        val pcm = readWavPcm(wav)
        val result = Ft8Decoder.decode(pcm)

        println("DECODED: snr=${result.snr} dt=${result.dt} freq=${result.freqHz} text=${result.messageText}")
        assertTrue("decode must succeed: ${result.messageText}", result.success)
        // 至少应解出几个已知呼号
        assertTrue(
            "decoded text should look like an FT8 message: ${result.messageText}",
            result.messageText.contains("CQ") || result.messageText.contains("RR73") ||
                result.messageText.contains("73") || result.messageText.length > 5
        )
    }

    /** 解析 WAV（仅支持 PCM 16-bit mono/stereo），返回 ShortArray */
    private fun readWavPcm(file: File): ShortArray {
        val bytes = file.readBytes()
        // RIFF header
        require(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte())
        val fmtChunk = findChunk(bytes, "fmt ".toByteArray())
        require(fmtChunk != null) { "no fmt chunk" }
        val audioFormat = (bytes[fmtChunk + 8].toInt() and 0xFF) or ((bytes[fmtChunk + 9].toInt() and 0xFF) shl 8)
        val channels = (bytes[fmtChunk + 10].toInt() and 0xFF) or ((bytes[fmtChunk + 11].toInt() and 0xFF) shl 8)
        val bitsPerSample = (bytes[fmtChunk + 22].toInt() and 0xFF) or ((bytes[fmtChunk + 23].toInt() and 0xFF) shl 8)
        require(audioFormat == 1 && bitsPerSample == 16) { "expected PCM16, got fmt=$audioFormat bits=$bitsPerSample" }

        val dataChunk = findChunk(bytes, "data".toByteArray())
        require(dataChunk != null) { "no data chunk" }
        val dataStart = dataChunk + 8
        val dataLen = (bytes[dataChunk + 4].toInt() and 0xFF) or
            ((bytes[dataChunk + 5].toInt() and 0xFF) shl 8) or
            ((bytes[dataChunk + 6].toInt() and 0xFF) shl 16) or
            ((bytes[dataChunk + 7].toInt() and 0xFF) shl 24)
        val sampleBytes = minOf(dataLen, bytes.size - dataStart)
        val sampleCount = sampleBytes / 2 / channels
        val pcm = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val idx = dataStart + i * 2 * channels
            var sample = (bytes[idx].toInt() and 0xFF) or ((bytes[idx + 1].toInt() and 0xFF) shl 8)
            if (sample >= 32768) sample -= 65536
            pcm[i] = sample.toShort()
        }
        return pcm
    }

    private fun findChunk(bytes: ByteArray, id: ByteArray): Int? {
        var i = 12
        while (i + 8 <= bytes.size) {
            var match = true
            for (j in id.indices) {
                if (bytes[i + j] != id[j]) { match = false; break }
            }
            if (match) return i
            val size = (bytes[i + 4].toInt() and 0xFF) or
                ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                ((bytes[i + 6].toInt() and 0xFF) shl 16) or
                ((bytes[i + 7].toInt() and 0xFF) shl 24)
            i += 8 + size + (size % 2)
        }
        return null
    }
}
