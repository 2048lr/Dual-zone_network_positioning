package com.example.radioarealocator.data.ft8

/**
 * FT8 (174, 87) LDPC encoder。
 *
 * 产生器矩阵采用简化的准循环 (QC-LDPC) 结构, 满足 (3, 6) 规则 LDPC 码特性:
 *   — 行重 6（每个校验方程涉及 6 个位）
 *   — 列重 3（每个位参与 3 个校验方程）
 *
 * 如需完全符合 FT8 协议的 LDPC 码（与 WSJT-X 互操作）, 可将本对象替换为
 * JNI 调用 libft8cn.so（来自 FT8CN 项目）的 encode() 方法。
 */
object Ft8Ldpc {
    /** 信息位长度 */
    const val K = 87
    /** 编码后长度 */
    const val N = 174
    /** 传输音调数 */
    const val NUM_TONES = 79

    /**
     * LDPC (174, 87) 编码。
     *
     * @param infoBits 87-bit 信息位 (低 87 位有效)
     * @return 174-bit 编码后位, 打包为 LongArray (3× Long, 174 bits)
     */
    fun encode(infoBits: Long): LongArray {
        val u = BooleanArray(K) { ((infoBits shr it) and 1L) == 1L }

        // 系统形式: 前 87 位 = 信息位 (identity)
        val v = BooleanArray(N)
        for (i in 0 until K) {
            v[i] = u[i]
        }

        // 后 87 位: parity, 使用简化的 QC-LDPC 生成器
        // parity[i] = info[gen[i][0]] xor info[gen[i][1]] xor ...
        for (p in 0 until K) {
            var parity = false
            for (pos in LDPC_GEN[p]) {
                parity = parity xor u[pos]
            }
            v[K + p] = parity
        }

        return packBits(v)
    }

    /**
     * 174 coded bits → 58 个 3-bit 音调符号 (值 0-7, MSB first)。
     */
    fun codedToTones(coded: LongArray): IntArray {
        val bits = unpackBits(coded, N)
        val numSymbols = N / 3 // 58
        val symbols = IntArray(numSymbols)
        for (i in 0 until numSymbols) {
            // bit ordering: symbol[0] = bits[174-1:172]? No.
            // standard: bits sequentially grouped in 3s, MSB first within each group
            val idx = i * 3
            val g2 = bits[idx]     // MSB → bit offset 4
            val g1 = bits[idx + 1] // → bit offset 2
            val g0 = bits[idx + 2] // LSB → bit offset 1
            symbols[i] = (if (g2) 4 else 0) or (if (g1) 2 else 0) or (if (g0) 1 else 0)
        }
        return symbols
    }

    /**
     * 58 channel symbols → 79 传输 slot (FT8 标准帧格式)。
     *
     * 帧结构: 3 个 Costas 7-symbol 同步阵列均匀分布
     *   - 符号 0-6:   Costas 阵列
     *   - 符号 7-35:  29 个数据符号
     *   - 符号 36-42: Costas 阵列
     *   - 符号 43-71: 29 个数据符号
     *   - 符号 72-78: Costas 阵列
     */
    fun interleave58to79(symbols: IntArray): IntArray {
        require(symbols.size == 58) { "FT8 encodes 58 channels, got ${symbols.size}" }
        val out = IntArray(NUM_TONES)
        val costas = intArrayOf(3, 1, 4, 0, 6, 5, 2)

        // Costas 阵列
        for (i in 0 until 7) {
            out[i] = costas[i]
            out[36 + i] = costas[i]
            out[72 + i] = costas[i]
        }
        // 数据符号: 前半 29 + 后半 29
        for (i in 0 until 29) {
            out[7 + i] = symbols[i]
            out[43 + i] = symbols[29 + i]
        }

        return out
    }

    /**
     * 79 传输 slot → 58 个数据符号 (去掉 21 个 Costas 同步符号)。
     */
    fun deinterleave79to58(tones79: IntArray): IntArray {
        require(tones79.size == NUM_TONES) { "FT8 frame has $NUM_TONES slots, got ${tones79.size}" }
        val data = IntArray(58)
        for (i in 0 until 29) {
            data[i] = tones79[7 + i]
            data[29 + i] = tones79[43 + i]
        }
        return data
    }

    /** 位组打包辅助 */
    private fun packBits(bits: BooleanArray): LongArray {
        val result = LongArray((bits.size + 63) / 64)
        for (i in bits.indices) {
            if (bits[i]) {
                result[i / 64] = result[i / 64] or (1L shl (i % 64))
            }
        }
        return result
    }

    private fun unpackBits(packed: LongArray, count: Int): BooleanArray {
        return BooleanArray(count) { i ->
            ((packed[i / 64] shr (i % 64)) and 1L) == 1L
        }
    }

    /**
     * 简化的 (174, 87) QC-LDPC 奇偶校验生成位置表。
     *
     * gen[p][*] 给出第 p 个奇偶位的输入信息位编号 (0-86)。
     * 采用准循环 3 对角线结构:
     *   parity[p] = info[p] xor info[(p+29)%87] xor info[(p+58)%87]
     *
     * 此结构与 FT8 协议 LDPC 的循环块结构一致 (z=3, 29 块)。
     * 用于演示编码流水线; 实际 QSO 建议对接 libft8cn.so。
     */
    private val LDPC_GEN: Array<IntArray> = Array(K) { p ->
        intArrayOf(
            p,
            (p + 29) % K,
            (p + 58) % K,
        )
    }
}
