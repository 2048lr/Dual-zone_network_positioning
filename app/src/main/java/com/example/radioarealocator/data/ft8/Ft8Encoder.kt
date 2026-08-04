package com.example.radioarealocator.data.ft8

/**
 * FT8 编码器：77-bit 消息构建 → CRC-14 → LDPC → 8-FSK 音调生成
 *
 * 遵循 FT4/FT8 通信协议规范（Franke, Taylor, K1JT）。
 * 每个 FT8 模式下每 15 秒传输周期发送 79 个 8-FSK 音调。
 *
 * 消息类型:
 *   Type 1 (标准自由文本): CQ/QRZ + 呼叫者网格 + DE callsign hash
 *   Type 2 (EU VHF): CQ + callsign + grid
 *   Type 3 (RU): CQ + callsign + ...
 *   Type 0.0 (标准 QSO): 呼叫/响应/信号报告
 *   Type 0.5 (Telemetry)
 *
 * 音调生成:
 *   8-FSK at 6.25 Hz 间隔, 基频 200 Hz
 *   每个音调: 1920 samples at 12000 sps = 160 ms
 *   79 音调 × 160ms = 12.64 s, 15s 周期内剩余时间静音
 */
object Ft8Encoder {

    const val SAMPLE_RATE = 12000
    const val SYMBOL_PERIOD_SAMPLES = 1920 // 12000/6.25
    const val TONE_SPACING_HZ = 6.25f
    const val BASE_FREQ_HZ = 200f
    const val NUM_TONES = 79
    const val TOTAL_SAMPLES = SAMPLE_RATE * 15 // 15 seconds

    /**
     * 编码 FT8 消息为 PCM 音频样本 (16-bit signed)。
     *
     * @param messageText 人类可读消息如 "CQ BH4XYZ OM44"
     * @param callsign 本台呼号
     * @param grid 本台网格
     * @return ShortArray PCM 16-bit 音频样本, 12000 sps × 15s
     */
    fun encodeToPcm(
        messageText: String,
        callsign: String,
        grid: String
    ): ShortArray {
        // 1. 构建 77-bit 消息 (含 CRC-14)
        val message77 = buildMessage77(messageText, callsign, grid)

        // 2. 补零到 87 info bits
        val info87 = message77 shl 10 // 77 bits + 10 zero bits = 87

        // 3. LDPC (174, 87) 编码
        val coded174 = Ft8Ldpc.encode(info87)

        // 4. 提取 58 个 3-bit 音调符号
        val symbols58 = Ft8Ldpc.codedToTones(coded174)

        // 5. 交织到 79 个传输 slot
        val tones79 = Ft8Ldpc.interleave58to79(symbols58)

        // 6. 生成 8-FSK 音调 PCM
        return generateFs8Pcm(tones79)
    }

    /**
     * 构建 77-bit FT8 消息（含 CRC-14）。
     *
     * 消息类型自动识别:
     *   - "CQ ..." → Type 1 (CQ: 28-bit hash + grid + 28-bit hash)
     *   - 标准 QSO → Type 0.0 (双呼号 + 报告)
     */
    @Suppress("MagicNumber")
    fun buildMessage77(messageText: String, deCallsign: String, deGrid: String): Long {
        val parts = messageText.trim().split("\\s+".toRegex())
        if (parts.isEmpty()) return 0L

        val firstWord = parts[0].uppercase()

        return when {
            // CQ 呼叫
            firstWord == "CQ" -> {
                val deHash = callsign22Hash(deCallsign)
                val grid = if (parts.size >= 3) feetEncodeGrid(parts[2]) else feetEncodeGrid(deGrid)
                // Type 1: 77 bits = 71 message + 14 CRC embedded
                var msg = 0L
                msg = msg or 1L        // bit 0: ntype = 1
                msg = msg or 0L        // bit 1-28: CQ callsign hash (none for CQ)
                // bits 29-43: first callsign hash (CQ = 0)
                msg = msg or (grid.toLong() shl 29) // bits 44-58: grid
                msg = msg or (deHash.toLong() shl 58) // bits 59-71: DE hash (12 bits)
                // bits 71-76: reserved
                val crc = crc14(msg, 77)
                msg or (crc.toLong() shl 63) // CRC replaces high 14 bits
            }

            // 直接文本消息 (Free text): 最多 13 字符
            messageText.length <= 13 -> {
                encodeFreeText(messageText)
            }

            // 标准 QSO 消息: e.g. "BH4XYZ OM44 -05"
            else -> {
                // Type 0.0: 标准消息
                val toCall = parts.getOrElse(0) { "" }
                val report = parts.getOrElse(1) { "" }.trim()

                val deHash = callsign22Hash(deCallsign)
                val reportBits: Long = when {
                    report.matches(Regex("^([+-]\\d{2}|R[+-]\\d{2})$")) -> {
                        val num = report.replace(Regex("[R]"), "")
                            .replace("+", "").toIntOrNull() ?: 0
                        if (report.startsWith('R')) (num + 50).toLong() // RR73 etc
                        else (num + 50).toLong()
                    }
                    report == "RR73" || report == "RRR" -> 50L
                    report == "73" -> 50L
                    else -> 50L // default -99
                }

                // Type 0.0: i3=4, n3=1, 71 bits total
                var msg = 0L
                msg = msg or call000Hash(toCall).toLong() // bits 0-27 (28-bit hash)
                msg = msg or deHash.toLong() // bits 28-39 (12-bit hash)
                msg = msg or (reportBits shl 40) // bits 40-45 (6-bit report)
                // bits 46-70: reserved
                val crc = crc14(msg, 77)
                msg or (crc.toLong() shl 63)
            }
        }
    }

    /**
     * 自由文本消息 (最多 13 个 ASCII 字符)。
     * FT8 自由文本: i3=0, n3=0, 71 bits:
     *   bits 0-70: 13 × 5.5 bit 字符 ≈ 71 bits
     *   采用 base-42 编码: 13 字符 → 42¹³ ≈ 2^71
     */
    @Suppress("MagicNumber")
    private fun encodeFreeText(text: String): Long {
        val padded = text.padEnd(13, ' ')
        val charset = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
        var msg = 0L

        for ((i, ch) in padded.withIndex()) {
            val idx = charset.indexOf(ch.uppercaseChar())
            val value = if (idx >= 0) idx.toLong() else 0L

            // 前 6 字符: 每字符 6 bits
            if (i < 6) {
                msg = msg or (value shl (i * 6))
            } else {
                // 后 7 字符: 每字符 5 bits
                msg = msg or (value shl (36 + (i - 6) * 5))
            }
        }

        // bit 71-76: ntype=0, reserved
        // msg currently occupies bits 0-70 (71 bits)
        val crc = crc14(msg, 77)
        return msg or (crc.toLong() shl 63)
    }

    /**
     * 呼号 22-bit hash (for FT8 i3.n3 type messages)。
     * 算法: 截取前 11 字符, 每个字符映射到 0-37, 然后 base-38 → 22-bit。
     */
    @Suppress("MagicNumber")
    private fun callsign22Hash(callsign: String): Int {
        val clean = callsign.trim().uppercase().take(11)
        val chars = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var hash = 0L
        for ((i, ch) in clean.withIndex()) {
            val idx = chars.indexOf(ch).coerceAtLeast(0)
            hash = (hash * 38 + idx) % (1L shl 22)
        }
        return hash.toInt() and 0x3FFFFF
    }

    /**
     * 呼号 28-bit hash (call_000, for Type 0.0 messages)。
     * 11 字符 base-38 → 28-bit。
     */
    @Suppress("MagicNumber")
    private fun call000Hash(callsign: String): Int {
        val clean = callsign.trim().uppercase().take(11)
        val chars = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var hash = 0L
        for ((i, ch) in clean.withIndex()) {
            val idx = chars.indexOf(ch).coerceAtLeast(0)
            hash += idx * power38[i]
        }
        return (hash % (1L shl 28)).toInt()
    }

    @Suppress("MagicNumber")
    private val power38: LongArray = LongArray(11) { i ->
        var p = 1L
        repeat(i) { p *= 38 }
        p % (1L shl 28)
    }

    /**
     * Maidenhead 4-字符网格编码为 15-bit (G_000)。
     * 格式: 字母-数字-字母-数字。0 = 空/null。
     */
    @Suppress("MagicNumber")
    private fun encodeGrid15(grid: String): Int {
        val clean = grid.trim().uppercase().take(4)
        if (clean.length < 4) return 0

        val lonField = clean[0].code - 'A'.code
        val latField = clean[1].code - 'A'.code
        val lonSquare = clean[2].code - '0'.code
        val latSquare = clean[3].code - '0'.code

        return if (lonField in 0..17 && latField in 0..17 &&
            lonSquare in 0..9 && latSquare in 0..9
        ) {
            // 编码: (18 * 10 * lat_field + 10 * lat_square) * 18 + lon_field, etc.
            // 使用标准 Maidenhead → 15-bit 公式
            ((lonField * 180 + lonSquare * 10) * 180 + latField * 10 + latSquare) and 0x7FFF
        } else 0
    }

    private fun feetEncodeGrid(grid: String): Int = encodeGrid15(grid)

    /**
     * CRC-14 计算 (多项式: x¹⁴ + x¹³ + x¹¹ + x⁷ + x⁴ + x² + x + 1 = 0x2757)
     * 输入: 77-bit 消息 (低 77 bits 有效)
     *
     * FT8 在已构建的 77 位消息上计算 14 位 CRC, 将 CRC 替换消息的高 14 位。
     */
    @Suppress("MagicNumber")
    fun crc14(message77: Long, bitCount: Int): Int {
        val poly = 0x2757 // CRC-14 多项式
        var crc = 0

        // 比特流: 最高位先 (MSB first)
        for (i in bitCount - 1 downTo 0) {
            val bit = ((message77 shr i) and 1L).toInt()
            val xorBit = (crc shr 13) xor bit
            crc = ((crc shl 1) and 0x3FFF) // 14-bit CRC
            if (xorBit != 0) {
                crc = crc xor poly
            }
        }
        // 再次处理 14 位 0 填充
        for (i in 0 until 14) {
            val xorBit = crc shr 13
            crc = ((crc shl 1) and 0x3FFF)
            if (xorBit != 0) {
                crc = crc xor poly
            }
        }

        return crc and 0x3FFF
    }

    /** FT8 8-FSK 音调生成 */
    @Suppress("MagicNumber")
    private fun generateFs8Pcm(tones79: IntArray): ShortArray {
        val totalSamples = SYMBOL_PERIOD_SAMPLES * NUM_TONES
        val pcm = ShortArray(totalSamples)

        val amplitude = 16000.0 // 16-bit PCM 幅度

        for (t in 0 until NUM_TONES) {
            val tone = tones79[t] and 0x7
            val freq = BASE_FREQ_HZ + tone * TONE_SPACING_HZ
            val startIdx = t * SYMBOL_PERIOD_SAMPLES

            // 在音调边界平滑过渡 (raised cosine 斜坡)
            val rampLen = (SYMBOL_PERIOD_SAMPLES * 0.02).toInt().coerceAtLeast(1)

            for (s in 0 until SYMBOL_PERIOD_SAMPLES) {
                val phase = 2.0 * Math.PI * freq * s / SAMPLE_RATE
                val sample = amplitude * Math.sin(phase)

                // 斜坡系数
                val ramp: Double = when {
                    s < rampLen -> s.toDouble() / rampLen
                    s >= SYMBOL_PERIOD_SAMPLES - rampLen ->
                        (SYMBOL_PERIOD_SAMPLES - s - 1).toDouble() / rampLen
                    else -> 1.0
                }

                pcm[startIdx + s] = (sample * ramp).toInt().toShort()
            }
        }

        return pcm
    }
}
