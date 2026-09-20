package cn.edu.jxau.tools.core

import cn.edu.jxau.tools.data.model.TimetableGrid
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.model.WeekParser
import cn.edu.jxau.tools.data.net.CasRsa

/**
 * 纯函数自检的汇总入口。
 *
 * ## 为什么要有这个东西
 * 这些逻辑（RSA 分块、周次解析、教学周推算、课表行归纳）全是没有 UI 的纯计算，
 * 而它们最容易「看着对、边界错」——开学前一周算成 0、`1—16`（长破折号）解析成空集、
 * 行序被 `Sjd` 带偏。这类错的共同特征是**不崩、不报错，只是结果悄悄不对**。
 *
 * 所以期望值都是先用 Python 独立算出来再抄进自检向量的，不是把实现结果回填；
 * 跑一遍就能在日志里看到 PASS/FAIL，失败的行以 `[E]` 输出（日志面板会自动展开）。
 *
 * 放在启动时跑而不是只在登录页跑：登录态下 App 直接进主界面，
 * 登录页的 ViewModel 根本不会被创建，自检就永远不执行了。
 */
object SelfTest {

    @Volatile
    private var alreadyRan = false

    /**
     * 跑全部自检并把结果写进日志。
     *
     * @param force 忽略「本次启动已跑过」的标记（登录页的「自检」按钮用）
     * @return 通过的项数 / 总项数
     */
    fun runAll(force: Boolean = false): Pair<Int, Int> {
        if (alreadyRan && !force) return 0 to 0
        alreadyRan = true

        var passed = 0
        var total = 0

        fun report(title: String, results: List<String>) {
            val ok = results.count { it.startsWith("PASS") }
            JxauLog.i("=== $title 自检开始 ===")
            results.forEach { line ->
                if (line.startsWith("PASS")) JxauLog.i(line) else JxauLog.e(line)
            }
            JxauLog.i("=== $title 自检结束：$ok/${results.size} 通过 ===")
            passed += ok
            total += results.size
        }

        JxauLog.i("RSA chunk=${CasRsa.chunkSize}")
        report("RSA 密码加密", CasRsa.selfTest())
        report("周次解析", WeekParser.selfTest())
        report("教学周推算", WeekMath.selfTest())
        report("课表行归纳", TimetableGrid.selfTest())
        JxauLog.i("=== 全部自检：$passed/$total 通过 ===")
        return passed to total
    }
}
