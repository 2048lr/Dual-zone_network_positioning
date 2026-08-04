package com.example.radioarealocator.data.ft8

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/**
 * FT8 解码器：音频 PCM → 音调检测 → 帧同步 → LDPC 解码 → 消息解析
 *
 * 解码流水线:
 *   1. Goertzel 算法对每个符号槽 (1920 samples) 检测 8 个音调的功率
 *   2. 滑动窗口 + Costas 阵列相关搜索确定帧起点与频偏
 *   3. 提取 58 个数据符号 → 174 bits
 *   4. (174, 87) LDPC 置信传播 (BP) 解码 → 87 信息位
 *   5. 解析 77-bit 消息 (含 CRC-14 校验) → 人类可读文本
 */
object Ft8Decoder {

    const val SYMBOL_SAMPLES = Ft8Encoder.SYMBOL_PERIOD_SAMPLES // 1920 @ 12kHz
    const val NUM_SYMBOLS = Ft8Ldpc.NUM_TONES // 79
    const val NUM_TONES = 8
    const val TONE_SPACING = Ft8Encoder.TONE_SPACING_HZ // 6.25
    const val BASE_FREQ = Ft8Encoder.BASE_FREQ_HZ // 200
    const val LDPC_K = Ft8Ldpc.K // 87
    const val LDPC_N = Ft8Ldpc.N // 174

    /** Costas 7-symbol 同步阵列 */
    val COSTAS = intArrayOf(3, 1, 4, 0, 6, 5, 2)

    /** Costas 阵列在 79-symbol 帧中的起始位置 */
    val COSTAS_START = intArrayOf(0, 36, 72)

    data class DecodeResult(
        val success: Boolean,
        val syncScore: Double,
        val freqOffset: Int,
        val messageText: String,
        val raw77Hex: String,
        val crcValid: Boolean,
        val snr: Double,
    )

    /**
     * 解码一段 PCM（12000 Hz mono 16-bit）。
     *
     * @param pcm 音频样本，需包含至少一个完整 FT8 帧（约 15s 或更多）
     * @return 解码结果（成功或失败）
     */
    fun decode(pcm: ShortArray): DecodeResult {
        val sampleRate = Ft8Encoder.SAMPLE_RATE
        val numSlots = pcm.size / SYMBOL_SAMPLES
        if (numSlots < NUM_SYMBOLS) {
            return DecodeResult(false, 0.0, 0, "音频太短 (需至少 ${NUM_SYMBOLS} 个符号槽)", "", false, 0.0)
        }

        // 1. 计算每个符号槽 × 8 音调的功率矩阵
        val power = Array(numSlots) { DoubleArray(NUM_TONES) }
        for (slot in 0 until numSlots) {
            val offset = slot * SYMBOL_SAMPLES
            for (tone in 0 until NUM_TONES) {
                power[slot][tone] = goertzelPower(
                    pcm, offset, SYMBOL_SAMPLES, sampleRate,
                    BASE_FREQ + tone * TONE_SPACING.toDouble()
                )
            }
        }

        // 2. 搜索帧起点 + 频偏（Costas 相关）
        var bestStart = -1
        var bestOffset = 0
        var bestScore = Double.NEGATIVE_INFINITY

        // 搜索所有可能的帧起点（允许帧跨缓冲区部分重叠）
        val maxStart = numSlots - NUM_SYMBOLS
        for (start in 0..maxStart) {
            for (freqOffset in -3..3) {
                val score = costasCorrelation(power, start, freqOffset)
                if (score > bestScore) {
                    bestScore = score
                    bestStart = start
                    bestOffset = freqOffset
                }
            }
        }

        if (bestStart < 0 || bestScore < 2.5) {
            return DecodeResult(false, bestScore, bestOffset, "未检测到 FT8 同步信号", "", false, 0.0)
        }

        // 3. 提取 79 个符号（含频偏补偿）
        val tones79 = IntArray(NUM_SYMBOLS)
        for (i in 0 until NUM_SYMBOLS) {
            tones79[i] = argMax(power[bestStart + i], bestOffset)
        }

        // 4. 去交织 → 58 数据符号 → 174 bits
        val dataSymbols = Ft8Ldpc.deinterleave79to58(tones79)
        val codedBits = symbolsToBits(dataSymbols)

        // 5. LDPC BP 解码
        val infoBits = ldpcDecode(codedBits)
        if (infoBits == null) {
            return DecodeResult(false, bestScore, bestOffset, "LDPC 解码失败 (纠错超出能力)", "", false, 0.0)
        }

        // 6. 解析 77-bit 消息
        val message77 = bitsToLong(infoBits, 0, 77)
        val crcValid = crc14Valid(message77, 77)
        val text = parseMessage77(message77, crcValid)

        val snr = estimateSnr(power, bestStart, bestOffset)

        return DecodeResult(
            success = true,
            syncScore = bestScore,
            freqOffset = bestOffset,
            messageText = text,
            raw77Hex = message77.toString(16).padStart(20, '0'),
            crcValid = crcValid,
            snr = snr,
        )
    }

    /** Goertzel 算法计算单频功率 */
    @Suppress("MagicNumber")
    private fun goertzelPower(
        pcm: ShortArray,
        offset: Int,
        n: Int,
        sampleRate: Int,
        freq: Double
    ): Double {
        val k = (0.5 + n.toDouble() * freq / sampleRate).toInt()
        val w = 2.0 * PI * k / n
        val coeff = 2.0 * cos(w)
        var sPrev = 0.0
        var sPrev2 = 0.0
        for (i in 0 until n) {
            val sample = pcm[offset + i].toDouble()
            val s = sample + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        return sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2
    }

    /** Costas 阵列相关分数（三个阵列，含频偏补偿） */
    private fun costasCorrelation(power: Array<DoubleArray>, start: Int, freqOffset: Int): Double {
        var score = 0.0
        for (arr in COSTAS_START) {
            for (k in 0 until 7) {
                val expected = COSTAS[k]
                val slot = start + arr + k
                if (slot >= power.size) continue
                val detected = argMax(power[slot], freqOffset)
                if (detected == expected) score += 1.0
            }
        }
        return score
    }

    /** 返回某个符号槽内功率最大的音调（含频偏补偿） */
    private fun argMax(power: DoubleArray, freqOffset: Int): Int {
        var best = 0
        var bestPower = Double.NEGATIVE_INFINITY
        for (t in 0 until NUM_TONES) {
            val idx = t + freqOffset
            val p = if (idx in 0 until NUM_TONES) power[idx] else 0.0
            if (p > bestPower) {
                bestPower = p
                best = t
            }
        }
        return best
    }

    /** 58 个 3-bit 符号 → 174 bits (MSB first) */
    private fun symbolsToBits(symbols: IntArray): BooleanArray {
        val bits = BooleanArray(LDPC_N)
        for (i in symbols.indices) {
            val s = symbols[i]
            bits[i * 3] = (s and 4) != 0
            bits[i * 3 + 1] = (s and 2) != 0
            bits[i * 3 + 2] = (s and 1) != 0
        }
        return bits
    }

    /**
     * LDPC (174, 87) 置信传播 (BP) 硬判决解码。
     *
     * 与编码器使用相同的校验矩阵: 每行 4 个变量节点 (v[p], v[p+29], v[p+58], v[87+p])。
     * 迭代奇偶校验修正: 对每行, 若校验失败则翻转"最可疑"的位。
     */
    private fun ldpcDecode(bits: BooleanArray): BooleanArray? {
        val v = bits.copyOf()

        // 生成校验矩阵: H[row][col]
        // row p: cols {p, (p+29)%87, (p+58)%87, 87+p}
        val rows = LDPC_K
        val cols = LDPC_N

        // 预计算每行的变量位置
        val rowVars = Array(rows) { p ->
            intArrayOf(
                p,
                (p + 29) % LDPC_K,
                (p + 58) % LDPC_K,
                LDPC_K + p
            )
        }
        // 预计算每列所在的行
        val colRows = Array(cols) { col ->
            buildList {
                for (p in 0 until rows) {
                    for (pos in rowVars[p]) {
                        if (pos == col) add(p)
                    }
                }
            }.toIntArray()
        }

        // 迭代修正
        val maxIter = 30
        for (iter in 0 until maxIter) {
            // 校验所有行
            var allOk = true
            val failedRows = mutableListOf<Int>()
            for (p in 0 until rows) {
                var parity = false
                for (pos in rowVars[p]) parity = parity xor v[pos]
                if (parity) {
                    allOk = false
                    failedRows.add(p)
                }
            }
            if (allOk) return v

            // 统计每个位的不满足行数，翻转"最可疑"的位
            val violations = IntArray(cols)
            for (p in failedRows) {
                for (pos in rowVars[p]) violations[pos]++
            }
            var maxV = 0
            var maxCol = 0
            for (c in 0 until cols) {
                if (violations[c] > maxV) {
                    maxV = violations[c]
                    maxCol = c
                }
            }
            if (maxV == 0) return null
            v[maxCol] = !v[maxCol]
        }

        // 最终校验
        for (p in 0 until rows) {
            var parity = false
            for (pos in rowVars[p]) parity = parity xor v[pos]
            if (parity) return null
        }
        return v
    }

    /** 从 BooleanArray 提取 Long (LSB 为 [bitIndex]) */
    private fun bitsToLong(bits: BooleanArray, bitIndex: Int, count: Int): Long {
        var result = 0L
        for (i in 0 until count) {
            if (bits[bitIndex + i]) result = result or (1L shl i)
        }
        return result
    }

    /** CRC-14 校验 */
    private fun crc14Valid(message77: Long, bitCount: Int): Boolean {
        val embedded = ((message77 shr 63) and 0x3FFF).toInt()
        return Ft8Encoder.crc14(message77, bitCount) == embedded
    }

    /** 估算信噪比 (dB) */
    private fun estimateSnr(power: Array<DoubleArray>, start: Int, freqOffset: Int): Double {
        var signal = 0.0
        var noise = 0.0
        var signalCount = 0
        var noiseCount = 0
        for (i in 0 until NUM_SYMBOLS) {
            val slot = start + i
            if (slot >= power.size) break
            val detected = argMax(power[slot], freqOffset)
            val idx = detected + freqOffset
            if (idx in 0 until NUM_TONES) {
                signal += power[slot][idx]
                signalCount++
            }
            for (t in 0 until NUM_TONES) {
                val tidx = t + freqOffset
                if (tidx != idx && tidx in 0 until NUM_TONES) {
                    noise += power[slot][tidx]
                    noiseCount++
                }
            }
        }
        if (signalCount == 0 || noiseCount == 0) return 0.0
        val snrRatio = (signal / signalCount) / (noise / noiseCount)
        return if (snrRatio <= 0) 0.0 else 10.0 * ln(snrRatio) / ln(10.0)
    }

    /**
     * 解析 77-bit FT8 消息。
     *
     * 与编码器布局匹配:
     *   - Type 1 (CQ): bit0=1, 网格 bits 44-58, DE 呼号 hash bits 59-71
     *   - 自由文本: bit0=0, 文本编码在低 71 位
     */
    @Suppress("MagicNumber")
    private fun parseMessage77(message77: Long, crcValid: Boolean): String {
        val ntype = message77 and 1L
        return if (ntype == 1L) {
            // CQ 类型
            val grid = encodeGrid15toText(((message77 shr 29) and 0x7FFF).toInt())
            val deHash = ((message77 shr 58) and 0xFFF).toInt()
            "CQ 网格=$grid DE呼号Hash=0x${deHash.toString(16).padStart(3, '0')}"
        } else {
            // 自由文本
            val text = decodeFreeText(message77)
            if (text.isNotBlank()) text else "未知消息 (0x${message77.toString(16)})"
        }
    }

    /** Maidenhead 15-bit → 文本 */
    @Suppress("MagicNumber")
    private fun encodeGrid15toText(encoded: Int): String {
        if (encoded == 0) return "-"
        // 编码: lonField*32400 + lonSquare*1800 + latField*10 + latSquare
        val lonField = encoded / 32400
        val lonSquare = (encoded / 1800) % 18
        val latField = (encoded / 10) % 180
        val latSquare = encoded % 10
        if (lonField !in 0..17 || latField !in 0..17 || lonSquare !in 0..9 || latSquare !in 0..9) return "-"
        return buildString {
            append(('A'.code + lonField).toChar())
            append(('A'.code + latField).toChar())
            append(('0'.code + lonSquare).toChar())
            append(('0'.code + latSquare).toChar())
        }
    }

    /** 自由文本解码 (13 字符 base-42) */
    @Suppress("MagicNumber")
    private fun decodeFreeText(message77: Long): String {
        val charset = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
        val sb = StringBuilder()
        for (i in 0 until 6) {
            val value = ((message77 shr (i * 6)) and 0x3F).toInt()
            sb.append(charset.getOrElse(value) { '?' })
        }
        for (i in 6 until 13) {
            val value = ((message77 shr (36 + (i - 6) * 5)) and 0x1F).toInt()
            sb.append(charset.getOrElse(value) { '?' })
        }
        return sb.toString().trimEnd(' ')
    }
}
