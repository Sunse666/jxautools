package cn.edu.jxau.tools.data.model

/**
 * 抢课任务的状态机。
 *
 * [RUNNING] 只是「引擎正在处理这个任务」，不代表会成功；
 * 终态只有 [SUCCESS] / [FAILED] / [CANCELLED] 三个。
 */
enum class RushState(val label: String) {
    /** 排队中，等引擎处理（或等定时触发） */
    WAITING("等待中"),

    /** 引擎正在重试这个任务 */
    RUNNING("进行中"),

    /** 已选上，并经服务端列表回查确认 */
    SUCCESS("已选上"),

    /** 达到上限仍未选上（原因见 [RushTask.lastMessage]） */
    FAILED("未成功"),

    /** 用户手动取消 */
    CANCELLED("已取消"),
}

/** 一条抢课任务。[classNo]/[selectCategory]/[batchId] 三件套正是 `XkInfo` 的提交参数 */
data class RushTask(
    val classNo: String,
    val className: String,
    /** 提交参数 `Xklb`：行数据里的选课类别（`任选` 等），不是查询用的范围名 */
    val selectCategory: String,
    /** 提交参数 `pcid`：选课批次（`Xkpc`） */
    val batchId: Int,
    val teacher: String = "",
    val credit: Double = 0.0,
    val createdAt: Long,
    val state: RushState = RushState.WAITING,
    /** 已提交的次数（含失败与回执不明的） */
    val attempts: Int = 0,
    /** 最近一次给人看的状态描述 */
    val lastMessage: String = "",
    val lastAt: Long = 0,
) {
    /** 界面上的主标题 */
    val title: String get() = className.ifBlank { classNo }
}

/**
 * 引擎的全局参数。界面上可调；重试节奏默认对齐「抢课是按秒算的」的现实：
 * 1.2~2s 一发，人手速差不多就是这个量级，也不会把服务端打到封禁。
 */
data class RushConfig(
    /** 两次提交的基准间隔（毫秒） */
    val intervalMs: Long = 1500,
    /** 抖动上限（毫秒）。0~jitter 随机加在间隔上，避免多任务同相位齐射 */
    val jitterMs: Long = 500,
    /** 单个任务的最大提交次数 */
    val maxAttempts: Int = 40,
    /** 单个任务的最长持续时间（分钟）。到点未成即失败，防止无限挂着 */
    val deadlineMinutes: Int = 10,
)

/**
 * 引擎对一次提交结果的**决策**。纯函数，见 [RushPolicy.decide]。
 */
sealed class RushDecision {
    /** 不直接信回执，先回查「已选课程」列表用服务端真相裁决 */
    object Verify : RushDecision()

    /** 等一个间隔后再提交 */
    object SubmitAgain : RushDecision()

    /** 回执已确认成功（回查也通过），进入下一个任务 */
    object Done : RushDecision()

    /** 不用再试了：原因要么是明确拒绝，要么是续不回来的会话 */
    data class GiveUp(val reason: String) : RushDecision()
}

/**
 * 提交回执 → 下一步动作的决策表。**这是抢课引擎的脑子**，纯函数、可穷举自检。
 *
 * 决策原则（每条都对应一类真实事故）：
 * 1. 回执说成功 → **必须回查**。本地乐观认定成功、实际没选上，比失败更糟。
 * 2. 名额满 → 继续等位。抢课场景里「满」是常态，放位瞬间就是机会。
 * 3. 「已选过」→ 回查而不是放弃。重复提交可能踩服务端的并发判定，先确认真相。
 * 4. 其它明确拒绝 → 放弃。比如「不在选课对象内」，重试一万次结果一样，只会白打。
 * 5. 回执不明（无 Result/success）→ 回查。宁可信其有。
 * 6. 请求没送达 → 换个间隔再试（引擎负责先自愈会话）。
 */
object RushPolicy {

    /** 这些字样 = 名额问题，等位重试是有意义的 */
    private val CAPACITY_KEYWORDS = listOf("满", "名额", "容量", "人数")

    /** 这些字样 = 可能其实已经选上了 */
    private val ALREADY_KEYWORDS = listOf("已选", "重复", "已存在", "已修")

    fun decide(result: WriteResult?): RushDecision = when {
        // 请求没发出去（网络断/会话失效页）。引擎会先自愈，然后原样重试
        result == null || !result.delivered -> RushDecision.SubmitAgain
        result.ok == true -> RushDecision.Verify
        result.ok == false -> {
            val msg = result.message
            when {
                CAPACITY_KEYWORDS.any { msg.contains(it) } -> RushDecision.SubmitAgain
                ALREADY_KEYWORDS.any { msg.contains(it) } -> RushDecision.Verify
                else -> RushDecision.GiveUp("服务端明确拒绝：${msg.ifBlank { "未说明原因" }}")
            }
        }
        // 回执既无 Result 也无 success：可能是某些服务端分支只给 Status。
        // 先回查：真选上了就不许再提交（重复提交的后果不可控）
        else -> RushDecision.Verify
    }

    /**
     * 回查裁决：目标在服务端「已选课程」列表里吗。
     *
     * @param found 目标 JxbBh 是否在已选列表中
     * @param unresolvedCount 此前「回执不明且回查没找到」的连续次数
     * @param maxUnresolved 连续存疑上限。超过说明「回执成功但列表没有」不是抖动，
     *   继续提交可能造成重复选课，停下来让人看。
     */
    fun verify(found: Boolean, unresolvedCount: Int, maxUnresolved: Int = 3): RushDecision =
        if (found) {
            RushDecision.Done
        } else if (unresolvedCount >= maxUnresolved) {
            RushDecision.GiveUp("连续 $unresolvedCount 次回执存疑且已选列表里没有这门课，停止提交以防重复。请到教务系统页面确认")
        } else {
            RushDecision.SubmitAgain
        }

    /** 自检向量：每个分支一条，对应真实回执形态。与其它纯函数一样返回 PASS/FAIL 行 */
    fun selfTest(): List<String> {
        val out = mutableListOf<String>()
        fun check(name: String, ok: Boolean, detail: String = "") {
            out += if (ok) "PASS $name $detail" else "FAIL $name $detail"
        }

        val okTrue = WriteResult(delivered = true, ok = true, message = "选课成功")
        check("成功→回查", decide(okTrue) == RushDecision.Verify)

        val capacity = WriteResult(delivered = true, ok = false, message = "该教学班人数已满，请选择其他班级")
        check("名额满→等位重试", decide(capacity) == RushDecision.SubmitAgain)

        val already = WriteResult(delivered = true, ok = false, message = "您已选择该课程，请勿重复提交")
        check("已选过→回查", decide(already) == RushDecision.Verify)

        val rejected = WriteResult(delivered = true, ok = false, message = "您不在该课程的选课对象范围内")
        check("明确拒绝→放弃", decide(rejected) is RushDecision.GiveUp)

        val rejectedBlank = WriteResult(delivered = true, ok = false, message = "")
        check("拒绝无原因→放弃", decide(rejectedBlank) is RushDecision.GiveUp)

        val ambiguous = WriteResult(delivered = true, ok = null, message = "")
        check("回执不明→回查", decide(ambiguous) == RushDecision.Verify)

        val lost = WriteResult(delivered = false, ok = null, message = "")
        check("没送达→自愈后重试", decide(lost) == RushDecision.SubmitAgain)
        check("null结果→重试", decide(null) == RushDecision.SubmitAgain)

        check("回查命中→完成", verify(found = true, unresolvedCount = 0) == RushDecision.Done)
        check("回查未命中→重试", verify(found = false, unresolvedCount = 1) == RushDecision.SubmitAgain)
        check("连续存疑达上限→放弃", verify(found = false, unresolvedCount = 3) is RushDecision.GiveUp)

        return out
    }
}
