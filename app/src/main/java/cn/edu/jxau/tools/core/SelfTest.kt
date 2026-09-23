package cn.edu.jxau.tools.core

import cn.edu.jxau.tools.data.SessionValidation
import cn.edu.jxau.tools.data.export.ExamIcs
import cn.edu.jxau.tools.data.export.IcsWriter
import cn.edu.jxau.tools.data.model.AdvisorPlanTest
import cn.edu.jxau.tools.data.model.GradeStats
import cn.edu.jxau.tools.data.model.TermAnchor
import cn.edu.jxau.tools.data.model.TimetableGrid
import cn.edu.jxau.tools.data.model.TimetableSizeSpec
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.model.WeekParser
import cn.edu.jxau.tools.data.model.XueJiChangeSchema
import cn.edu.jxau.tools.data.model.XueJiSchema
import cn.edu.jxau.tools.data.net.CasRsa
import cn.edu.jxau.tools.ui.theme.ColorThemeSpec
import cn.edu.jxau.tools.ui.theme.JxauPalette
import cn.edu.jxau.tools.ui.theme.typographySelfTest
import cn.edu.jxau.tools.ui.timetable.CoursePalette
import cn.edu.jxau.tools.ui.timetable.TimetableSurface

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
        report("周次锚点存取", TermAnchor.selfTest())
        report("课表格子归纳", TimetableGrid.selfTest())
        report("成绩统计口径", GradeStats.selfTest())
        report("会话失效判定", SessionValidation.selfTest())
        report("外观与课表尺寸偏好", TimetableSizeSpec.selfTest())
        report("课程块配色", CoursePalette.selfTest())
        report("主题色派生", ColorThemeSpec.selfTest())
        report("表面层级配色", JxauPalette.selfTest())
        report("字体链路（字族/字号缩放）", typographySelfTest())
        report("课表空格底纹", TimetableSurface.selfTest())
        report("日历写出（折行/转义/时区）", IcsWriter.selfTest())
        report("考试时间解析", ExamIcs.selfTest())
        report("学籍档案字段与隐私遮蔽", XueJiSchema.selfTest())
        report("学籍异动记录", XueJiChangeSchema.selfTest())
        report("导师与学期规划", AdvisorPlanTest.selfTest())
        JxauLog.i("=== 全部自检：$passed/$total 通过 ===")
        return passed to total
    }
}
