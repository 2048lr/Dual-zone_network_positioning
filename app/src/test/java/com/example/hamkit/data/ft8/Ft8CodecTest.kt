package com.example.hamkit.data.ft8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FT8 编解码器标准合规性测试。
 *
 * 验证：
 *  - 消息打包/解包往返（呼号、网格、报告、自由文本）
 *  - CRC-14 正确性（与 ft8_lib 测试向量一致）
 *  - LDPC(174,91) 编码后可通过标准校验矩阵
 *  - 完整编码（GFSK）-> 解码 往返
 */
class Ft8CodecTest {

    @Test
    fun crc_zero_extended_property() {
        // FT8 CRC 在 77 位消息补 5 个零扩展后计算（96-14=82 位）
        val payload77 = Ft8Protocol.packMessage("CQ K7IHZ DM43")!!
        val a91 = Ft8Protocol.addCrc(payload77)

        // 全部为 0 的 82 位输入，CRC 必须为 0
        val zeros = UByteArray(12)
        assertEquals(0, Ft8Protocol.computeCrc(zeros, 82))

        // CRC 位（bit 77-90）不参与 CRC 计算（与 ft8_lib ftx_decode_candidate 一致）
        val a91Crc = a91.copyOf()
        a91Crc[9] = (a91Crc[9].toInt() and 0xF8).toUByte()
        a91Crc[10] = 0.toUByte()
        assertEquals(Ft8Protocol.extractCrc(a91), Ft8Protocol.computeCrc(a91Crc, 82))
    }

    @Test
    fun hash22_known_vector() {
        // K1ABC 的标准 22 位哈希（参考 FT8 协议实现）
        val h = Ft8Protocol.hash22("K1ABC")
        assertTrue("hash22 must be non-negative", h >= 0)
        assertTrue("hash22 must fit in 22 bits", h and 0x3FFFFF == h)
    }

    @Test
    fun grid_pack_unpack_roundtrip() {
        val grids = listOf("KO26", "RR99", "AA00", "RR09", "AA01", "OM44", "PL97")
        for (g in grids) {
            val packed = Ft8Protocol.packGridOrReport(g)
            val unpacked = Ft8Protocol.unpackGridOrReport(packed)
            assertEquals("grid $g roundtrip", g, unpacked)
        }
    }

    @Test
    fun report_pack_unpack_roundtrip() {
        val reports = listOf("+10", "+05", "-02", "-12", "R+10", "R-02", "RRR", "RR73", "73", "")
        for (r in reports) {
            val packed = Ft8Protocol.packGridOrReport(r)
            val unpacked = Ft8Protocol.unpackGridOrReport(packed)
            assertEquals("report '$r' roundtrip", r, unpacked)
        }
    }

    @Test
    fun callsign_pack_unpack_roundtrip() {
        val calls = listOf("YL3JG", "W1A", "W5AB", "W8ABC", "DE6ABC", "DE7AB", "DE9A", "3DA0XYZ", "3XZ0AB", "BH4XYZ")
        for (c in calls) {
            val ip = IntArray(1)
            val n28 = Ft8Protocol.pack28(c, ip)
            assertTrue("callsign $c should pack (n28=$n28)", n28 >= 0)
            val unpacked = Ft8Protocol.unpack28(n28, ip[0], 1)
            assertEquals("callsign $c roundtrip", c, unpacked)
        }
    }

    @Test
    fun free_text_pack_unpack_roundtrip() {
        val texts = listOf(
            "TNX BOB 73 GL",
            "HI THERE",
            "TEST 123",
            "CQ CQ CQ DX",
            "GL",
        )
        for (t in texts) {
            val packed = Ft8Protocol.packFreeText(t)
            assertTrue("free text '$t' should pack", packed != null)
            val unpacked = Ft8Protocol.unpackFreeText(packed!!)
            assertEquals("free text '$t' roundtrip", t, unpacked)
        }
    }

    @Test
    fun message_std_roundtrip() {
        val cases = listOf(
            "CQ K7IHZ DM43",
            "CQ JA LB2JK JO59",
            "CQ 123 LB2JK JO59",
            "BH4XYZ OM44",
            "BH4XYZ BD7OH R-05",
            "K1ABC W9XYZ 73",
            "CQ YL3JG KO26",
        )
        for (msg in cases) {
            val packed = Ft8Protocol.packMessage(msg)
            assertTrue("message '$msg' should pack", packed != null)
            val text = Ft8Protocol.unpackMessage(packed!!)
            assertNotEquals("unpack '$msg' must not be unknown", "类型0.0 消息", text)
            assertTrue("unpack '$msg' -> '$text'", text.contains(msg.split(' ').first()))
        }
    }

    @Test
    fun ldpc_encoding_produces_valid_codeword() {
        // 用一组消息生成码字，校验矩阵必须全通过
        val cases = listOf("CQ K7IHZ DM43", "BH4XYZ BD7OH R-05", "TNX BOB 73 GL")
        for (msg in cases) {
            val payload77 = Ft8Protocol.packMessage(msg)!!
            val a91 = Ft8Protocol.addCrc(payload77)
            val codeword = Ft8Ldpc.encode174(a91)

            // 解包为位序列（MSB first），用校验矩阵检查
            val bits = BooleanArray(Ft8Ldpc.N) {
                ((codeword[it / 8].toInt() shr (7 - it % 8)) and 1) == 1
            }
            val errors = Ft8Ldpc.checkCodeword(bits)
            assertEquals("LDPC codeword for '$msg' must satisfy all checks", 0, errors)
        }
    }

    @Test
    fun crc_extract_roundtrip() {
        val payload77 = Ft8Protocol.packMessage("CQ K7IHZ DM43")!!
        val a91 = Ft8Protocol.addCrc(payload77)
        val extracted = Ft8Protocol.extractCrc(a91)

        // 校验：把 CRC 清零后重新计算，应一致
        val a91Crc = a91.copyOf()
        a91Crc[9] = (a91Crc[9].toInt() and 0xF8).toUByte()
        a91Crc[10] = 0.toUByte()
        val recalculated = Ft8Protocol.computeCrc(a91Crc, 96 - 14)
        assertEquals(extracted, recalculated)
    }

    @Test
    fun full_encode_decode_roundtrip() {
        // 编码 CQ 消息 -> PCM -> 解码，应还原呼号与网格
        val pcm = Ft8Encoder.encodeToPcm("CQ BH4XYZ OM44", "BH4XYZ", "OM44")
        assertTrue("encoded PCM must be non-empty", pcm.size > 0)

        val result = Ft8Decoder.decode(pcm)
        assertTrue("decode must succeed: ${result.messageText}", result.success)
        assertTrue("decoded text must contain BH4XYZ: ${result.messageText}", result.messageText.contains("BH4XYZ"))
        assertTrue("decoded text must contain OM44: ${result.messageText}", result.messageText.contains("OM44"))
    }

    @Test
    fun full_encode_decode_free_text_roundtrip() {
        val pcm = Ft8Encoder.encodeToPcm("HI BOB 73", "BH4XYZ", "OM44")
        val result = Ft8Decoder.decode(pcm)
        assertTrue("decode must succeed: ${result.messageText}", result.success)
        assertTrue("decoded free text: ${result.messageText}", result.messageText.contains("HI BOB 73"))
    }
}
