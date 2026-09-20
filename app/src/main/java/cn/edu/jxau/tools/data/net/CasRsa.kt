package cn.edu.jxau.tools.data.net

import java.math.BigInteger

/**
 * CAS 登录密码的 RSA 加密，逐行移植自 Python 脚本的 `_encrypt_cas_password`。
 *
 * 这不是标准 PKCS#1，而是 CAS 前端常用的「裸 RSA + 自定分块」：
 *  1. 明文按 UTF-16 码元两两成组，小端拼成一个 16 位数字，再按 16 位一位地拼成大整数；
 *  2. 每 [chunkSize] 个字符为一块（由模数位数反推，1024 位模数下 = 126 字符）；
 *  3. 不足整块则补 0 到整块（**补的是 char code 0，不是 PKCS#1 padding**）；
 *  4. 对每块做 modPow(e, n)，输出十六进制、补齐到 4 的倍数（等价于脚本里
 *     base-65536 逆序 + `%04x`），块之间用一个空格连接。
 *
 * 跨语言一致性由 [selfTest] 的固定向量保证——这些期望值是用脚本原算法离线算出来的，
 * 一旦移植有偏差会立刻暴露（而不是等到提交密码时才以"账号密码错误"的形式表现出来）。
 */
object CasRsa {

    private const val MODULUS_HEX =
        "00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5fd22c2e7ac519cab59198ece" +
            "036dcf289ea8201e2a0b9ded307f8fb704136eaeb670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5" +
            "120cc95b6431604bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1"

    private val modulus = BigInteger(MODULUS_HEX, 16)
    private val exponent = BigInteger("10001", 16)

    /**
     * 由模数位数反推分块大小，与脚本的 `2 * (len(_digits_16(modulus)) - 1)` 等价。
     * 1024 位模数 → 64 个 16 位数字 → chunkSize = 126。
     */
    val chunkSize: Int = run {
        val digitCount = (modulus.bitLength() + 15) / 16
        (digitCount - 1) * 2
    }

    fun encrypt(password: String): String {
        val codes = password.map { it.code }.toMutableList()
        if (codes.isEmpty()) codes.add(0)
        while (codes.size % chunkSize != 0) codes.add(0)

        val blocks = ArrayList<String>(codes.size / chunkSize)
        var offset = 0
        while (offset < codes.size) {
            var blockValue = BigInteger.ZERO
            var digitIndex = 0
            var cursor = offset
            while (cursor < offset + chunkSize) {
                // 小端双字符 → 一个 16 位数字（与脚本的 code[c] + (code[c+1] << 8) 一致）
                var digitValue = codes[cursor].toLong()
                cursor++
                digitValue += codes[cursor].toLong() shl 8
                cursor++
                blockValue = blockValue.add(BigInteger.valueOf(digitValue).shiftLeft(16 * digitIndex))
                digitIndex++
            }
            val cipher = blockValue.modPow(exponent, modulus)
            val hex = cipher.toString(16)
            // 补齐到 4 的倍数 == 脚本的「逆序 base-65536 数字各按 %04x 输出」
            val padded = hex.padStart((hex.length + 3) / 4 * 4, '0')
            blocks.add(padded)
            offset += chunkSize
        }
        return blocks.joinToString(" ")
    }

    /** 固定向量：(明文, 期望密文)，期望值由 tools/ref/rsa_ref.py 离线算出 */
    private val vectors: List<Pair<String, String>> = listOf(
        "123456" to
            "3a4ee3990a6ab88388eff6d262a1675aa6a72b550f254fb9d77d926de788e8cdefd087a618ee97e413d7e17" +
            "a874e37aa8255347cd9dfcbaf3f966939751f9e77931597bb42eb90489964fb982f0f2172983f8e86d3103aa" +
            "aece21436bb456e15165c3d161f160ce2679eba9dd0a2d7591fc8b3a0d6bfcbe21b63606133ffb6f4",
        "a" to
            "32744114c528a3d597be661a845db79d382fd417944b555faf4f90eaf50d23904572ea7942ce12ed509fed0" +
            "b39f459a0532625d25b8e1e216036e60f94a51e4937ed1e17e068abf88506319c543b2dfd15bd6e96f1a38a" +
            "c95be62cb8afd125bfef30042bfccb6fb76e4ad6758a14210462404550c1cde390fe3a76ab4456d6b2",
        "密码测试123" to
            "b1eaa80a3e3382d266f77eee86dc61ab17fad69361504528a747faa19082aaf7ca28914d61b1f10e2d95ac3" +
            "2842805b95ba4e7faac8f138e22a4a032bd9abe749135f8bd83c8a58d43b21a8171a033b3d8a274a778d9b6" +
            "e62f525ed9bd92c43263c97cf538676145f895a8b91784490fc19012a733ad230495a0613bc95210ac",
        // 127 字符 → 必须分成 2 块，专门覆盖「补 0 到整块 + 多块拼接」这条路径
        "b".repeat(127) to
            "907002570e7cd5a09435a71bdd2491b95dcfd48ff354e17c9d5aceb4fd58b1c7d6a8f3348f160cbaa50b028" +
            "bcf9d2bffd8708f672867b87dbedea123e80e31a37734c2a5cb017bdba3ef237f7ebf9b701f94701507e981" +
            "efffcff6bb01565456b16b454f0039ba1a5a3e6cc1510822d5b99421d8e6c7949c2bdf1b679cc9a878 " +
            "1a4dfb1cbe583c1793fe1256c39c58fa5e02425320ec4158a5e01a343f02bcd227dd70e5e078c7c8ea21a24" +
            "f40b92cddf005cb34e52433f2365dad0b38be4e1a650b99855f316a48bad6a4b9552969658b9cefaf6436a2" +
            "0a062d2a369cf0cf88fe10e4266be5f0423354b914b914b2256a47c245fcc4ede4e83244a39ba1b97c",
    )

    /**
     * 自检：返回逐条结论。全部 PASS 才说明移植正确。
     *
     * 刻意覆盖：单块 / 多块（127 字符触发分块）/ 非 ASCII（UTF-16 码元超过 8 位）。
     */
    fun selfTest(): List<String> = vectors.mapIndexed { index, (plain, expected) ->
        val actual = encrypt(plain)
        val label = renderLabel(plain)
        if (actual == expected) {
            "PASS #$index $label"
        } else {
            "FAIL #$index $label\n  expect=$expected\n  actual=$actual"
        }
    }

    private fun renderLabel(plain: String): String =
        if (plain.length > 12) "${plain.take(4)}…(${plain.length} chars)" else plain
}
