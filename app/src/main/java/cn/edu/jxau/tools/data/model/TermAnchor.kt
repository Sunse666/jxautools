package cn.edu.jxau.tools.data.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 「第一周周一」锚点 —— 把「今天」换算成「第几周」**唯一**的参照点。
 *
 * ## 为什么必须有它
 * 周次的公式是 `第几周 = floor((今天 − 第 1 周周一) / 7) + 1`。「今天」是已知的，
 * 但服务端**不提供**开学日期（实测排除了 7 处候选来源，见 `docs/教务系统接口清单.md` §4.2.1）。
 * 所以这个锚点要么从考试安排反推（覆盖面差：学期初只有补考，只对挂科学生有效），
 * 要么由用户校准一次（`Source.MANUAL`）。
 *
 * ## 为什么不按学期分组存
 * 存成 `Map<学期编码, 锚点>` 看起来更整齐，但会引入一个必须维护的关联：「当前学期是哪个」。
 * 而这个信息只有课表加载后才知道，设置页拿不到，于是要么多发一次请求、要么在别处塞一个
 * "最近学期"的隐藏状态。
 *
 * 实际上**单条就够，而且行为更正确**：锚点过期时（学期更替、放假）算出来的周次必然越界，
 * [WeekMath.positionOf] 会判成开学前 / 已放假 → 提示重新校准，而不是拿旧锚点硬算。
 * 少一个需要同步的状态，就少一处能静默失效的地方。
 */
data class TermAnchor(
    /** 该学期第一周的周一 */
    val monday: LocalDate,
    /** 这个锚点是怎么来的 —— 界面要显示出来，用户才知道可不可信 */
    val source: Source,
    /** 设定时间（epoch millis）。0 = 旧数据没带时间戳 */
    val savedAt: Long = 0L,
) {
    enum class Source(val key: String, val label: String) {
        /** 用户在「我的 → 周次校准」里填「现在第几周」反推得到 */
        MANUAL("manual", "手动校准"),

        /** 由考试安排的「周次 + 日期」反推得到，已落盘缓存 */
        EXAM("exam", "考试安排推算"),
        ;

        companion object {
            /**
             * 认不出的来源返回 null 而不是兜底成某个默认值。
             *
             * 来源决定界面怎么写（「手动校准」和「自动推算」的可信度不是一回事），
             * 猜错来源比认不出来更糟 —— 前者会让用户以为是自己设的。
             */
            fun ofKey(key: String?): Source? = entries.firstOrNull { it.key == key }
        }
    }

    /** 落盘形态：`2026-08-31|manual|1789000000000`。用 ISO 日期而不是 epochDay，出问题时肉眼可读 */
    fun encode(): String = "$monday$SEP${source.key}$SEP$savedAt"

    companion object {
        private const val SEP = "|"

        /**
         * 读存储。任何解析不出来的情况都返回 null（= 没有锚点），绝不抛异常、也不猜一个日期。
         *
         * 返回 null 的后果是「回退到考试安排反推」或「提示用户校准」，两者都是安全行为；
         * 而猜一个日期的后果是整个学期的周次全错，且看起来和正常一模一样。
         */
        fun decode(raw: String?): TermAnchor? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val parts = text.split(SEP)
            if (parts.size < 2) return null
            val monday = parseIso(parts[0]) ?: return null
            val source = Source.ofKey(parts[1]) ?: return null
            return TermAnchor(monday, source, parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L)
        }

        private fun parseIso(text: String): LocalDate? =
            try {
                LocalDate.parse(text.trim())
            } catch (_: DateTimeParseException) {
                null
            }

        private fun check(name: String, actual: Any?, expected: Any?, out: MutableList<String>) {
            out += if (actual == expected) "PASS $name = $actual"
            else "FAIL $name：期望 $expected，实际 $actual"
        }

        /** 期望值由 tools/verify_week_anchor.py 独立重算后抄入，不是把实现结果回填 */
        fun selfTest(): List<String> {
            val out = mutableListOf<String>()
            val a = TermAnchor(LocalDate.of(2026, 8, 31), Source.MANUAL, 1789000000000L)

            // ---- 编码往返 ----
            check("编码", a.encode(), "2026-08-31|manual|1789000000000", out)
            check("解码往返", decode(a.encode()), a, out)
            check(
                "考试来源往返",
                decode(TermAnchor(LocalDate.of(2026, 2, 23), Source.EXAM, 7L).encode()),
                TermAnchor(LocalDate.of(2026, 2, 23), Source.EXAM, 7L),
                out,
            )
            // 时间戳缺失要能读出来（旧版本数据），不能因为少一段就整条丢掉
            check("缺时间戳仍可读", decode("2026-08-31|manual"), a.copy(savedAt = 0L), out)
            check("多余段忽略", decode("2026-08-31|manual|5|垃圾"), a.copy(savedAt = 5L), out)

            // ---- 脏值一律 null，不抛也不猜 ----
            check("解码 空", decode(""), null, out)
            check("解码 null", decode(null), null, out)
            check("解码 只有日期", decode("2026-08-31"), null, out)
            check("解码 日期非法", decode("2026-13-45|manual"), null, out)
            check("解码 日期补零混用", decode("2026-8-31|manual"), null, out)
            check("解码 来源不认识", decode("2026-08-31|whoops"), null, out)
            check("解码 分隔符不足", decode("2026-08-31"), null, out)

            // 来源认不出必须是 null，不能兜底成 MANUAL —— 否则界面会把自动推算说成「你手动设的」
            check("来源 ofKey(manual)", Source.ofKey("manual"), Source.MANUAL, out)
            check("来源 ofKey(exam)", Source.ofKey("exam"), Source.EXAM, out)
            check("来源 ofKey(大写)", Source.ofKey("MANUAL"), null, out)
            check("来源 ofKey(空)", Source.ofKey(""), null, out)
            check("来源 ofKey(null)", Source.ofKey(null), null, out)

            return out
        }
    }
}
