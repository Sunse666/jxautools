package checkthemeprefs

import cn.edu.jxau.tools.data.model.TimetableSizeSpec
import cn.edu.jxau.tools.ui.theme.ColorThemeSpec
import cn.edu.jxau.tools.ui.theme.JxauPalette
import cn.edu.jxau.tools.ui.theme.typographySelfTest
import kotlin.system.exitProcess

/**
 * 离线跑一遍本轮 P0 涉及的几组纯函数自检。
 *
 * 不做别的事：把每个 `selfTest()` 的结果原样打出来并统计。之所以要单独跑，
 * 是因为这些自检里的期望值是用 `tools/verify_*.py` 独立算出来**抄进 Kotlin** 的 ——
 * 抄错一个数字不会编译报错，要等真机自检才暴露。这里在 PC 上先跑掉。
 *
 * 输出格式与真机日志一致（`PASS xxx` / `FAIL xxx`），外面套 `probe.sh` 时
 * 直接用 `^  PASS` / `^  FAIL` 做判据。
 */
fun main() {
    var passed = 0
    var failed = 0

    fun report(title: String, results: List<String>) {
        val ok = results.count { it.startsWith("PASS") }
        passed += ok
        failed += results.size - ok
        println("=== $title：$ok/${results.size} ===")
        results.forEach { println("  $it") }
    }

    fun guarded(title: String, body: () -> List<String>) {
        // 自检本身抛异常 = 没跑成，必须算失败而不是静默跳过
        val results = try {
            body()
        } catch (t: Throwable) {
            listOf("FAIL $title 抛异常：${t.javaClass.name}: ${t.message}")
        }
        report(title, results)
    }

    guarded("外观与课表尺寸偏好", TimetableSizeSpec::selfTest)
    guarded("主题色派生", ColorThemeSpec::selfTest)
    guarded("表面层级配色", JxauPalette::selfTest)
    guarded("字体链路（字族/字号缩放）", ::typographySelfTest)

    println("=== 合计：$passed 通过，$failed 失败 ===")
    if (failed > 0) exitProcess(1)
}
