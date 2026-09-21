package cn.edu.jxau.tools.data

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.CourseClass
import cn.edu.jxau.tools.data.model.RushDecision
import cn.edu.jxau.tools.data.model.RushPolicy
import cn.edu.jxau.tools.data.model.RushState
import cn.edu.jxau.tools.data.model.RushTask
import cn.edu.jxau.tools.data.model.SelectionScope
import cn.edu.jxau.tools.data.model.WriteResult
import cn.edu.jxau.tools.data.net.JwglApi
import cn.edu.jxau.tools.data.net.SiteProfiles
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 抢课引擎：把排队中的任务按顺序打完。
 *
 * ## 结构
 * - 每个任务一个循环：提交 → [RushPolicy.decide] → 按决策行动，直到终态或上限。
 * - 任务之间**串行**。页面 JS 本来就限制一次只提交一门，并行齐射只会让服务端
 *   的判定顺序不可控（两门课抢同一个名额时谁先谁后说不清）。
 * - 提交请求统一包一层 try/catch 翻译成 `WriteResult(delivered=false)`：
 *   网络异常和会话失效对策略来说是同一件事——「这发没送达」，下个间隔先自愈再试。
 *
 * ## 与「我的页-立即校验」的关系
 * 引擎不自建会话管理，全部走 [SessionRepository.ensureHealthy]：
 * 送达失败 → force 续期一次；续期也救不回来 → 任务失败并如实写明原因。
 */
class RushEngine(
    private val repo: SessionRepository,
    private val store: RushStore,
) {

    /**
     * 跑完所有 WAITING 任务。挂起直到全部到达终态（或协程被取消）。
     *
     * @param onTaskUpdate 每次任务状态变化后的回调（刷新通知用）。在调用方的协程上下文执行。
     */
    suspend fun runAll(onTaskUpdate: (RushTask) -> Unit) {
        while (coroutineContext.isActive) {
            val task = store.tasks.value.firstOrNull { it.state == RushState.WAITING } ?: break
            runOne(task, onTaskUpdate)
        }
    }

    private suspend fun runOne(task0: RushTask, onTaskUpdate: (RushTask) -> Unit) {
        val config = store.config.value
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + config.deadlineMinutes * 60_000L
        var unresolved = 0

        store.updateOne(task0.classNo) {
            it.copy(state = RushState.RUNNING, lastMessage = "开始执行", lastAt = startedAt)
        }
        onTaskUpdate(store.tasks.value.first { it.classNo == task0.classNo })
        JxauLog.i("抢课开始：${task0.title}（${task0.classNo}）间隔=${config.intervalMs}ms " +
            "抖动=${config.jitterMs}ms 上限=${config.maxAttempts}次 截止=${config.deadlineMinutes}分钟")

        var attempts = task0.attempts
        var lastMessage = ""
        var finalState = RushState.FAILED

        try {
            loop@ while (coroutineContext.isActive) {
                // ---- 上限与截止 ----
                if (attempts >= config.maxAttempts) {
                    lastMessage = "已达最大尝试次数（${config.maxAttempts}）仍未选上"
                    break@loop
                }
                if (System.currentTimeMillis() >= deadline) {
                    lastMessage = "已超过截止时间（${config.deadlineMinutes} 分钟）仍未选上"
                    break@loop
                }

                // ---- 会话 ----
                val session = currentSession()
                if (session == null) {
                    lastMessage = "没有可用会话（未登录或续期失败），任务中止"
                    break@loop
                }
                val api = JwglApi(SiteProfiles.of(session.channel), session.uuid, session.cookie)

                // ---- 提交 ----
                attempts++
                val result: WriteResult = withContext(Dispatchers.IO) {
                    try {
                        api.selectCourse(task0.toCourseClass())
                    } catch (e: Exception) {
                        JxauLog.e("抢课提交异常：${task0.title}", e)
                        WriteResult(delivered = false, ok = null)
                    }
                }
                JxauLog.i(
                    "抢课提交 #${attempts}：${task0.title} delivered=${result.delivered} " +
                        "ok=${result.ok} message=\"${result.message}\""
                )
                lastMessage = describe(result, attempts)
                store.updateOne(task0.classNo) {
                    it.copy(attempts = attempts, lastMessage = lastMessage, lastAt = System.currentTimeMillis())
                }
                onTaskUpdate(store.tasks.value.first { it.classNo == task0.classNo })

                // ---- 决策 ----
                when (val decision = RushPolicy.decide(result)) {
                    is RushDecision.Done -> {
                        finalState = RushState.SUCCESS
                        lastMessage = "已选上（经服务端已选列表确认）"
                        break@loop
                    }
                    is RushDecision.GiveUp -> {
                        lastMessage = decision.reason
                        break@loop
                    }
                    is RushDecision.Verify -> {
                        // 回查已选列表：服务端真相，不信本地回执
                        val selected = withContext(Dispatchers.IO) {
                            try {
                                api.fetchCourses(SelectionScope.MINE)
                            } catch (e: Exception) {
                                JxauLog.e("抢课回查异常：${task0.title}", e)
                                null
                            }
                        }
                        if (selected == null) {
                            // 回查失败不等于没选上，也不等于选上了——存疑计数，超限放弃
                            unresolved++
                            JxauLog.w("抢课回查失败（第 $unresolved 次）：${task0.title}")
                            val v = RushPolicy.verify(found = false, unresolvedCount = unresolved)
                            if (v is RushDecision.GiveUp) {
                                lastMessage = v.reason
                                break@loop
                            }
                        } else {
                            val found = selected.any { it.classNo == task0.classNo }
                            JxauLog.i(
                                "抢课回查：${task0.title} → ${if (found) "在已选列表中" else "不在已选列表中"}" +
                                    "（列表 ${selected.size} 条）"
                            )
                            when (val v = RushPolicy.verify(found, unresolved)) {
                                is RushDecision.Done -> {
                                    finalState = RushState.SUCCESS
                                    lastMessage = "已选上（经服务端已选列表确认）"
                                    break@loop
                                }
                                is RushDecision.GiveUp -> {
                                    lastMessage = v.reason
                                    break@loop
                                }
                                else -> Unit
                            }
                        }
                    }
                    is RushDecision.SubmitAgain -> Unit
                }

                // ---- 送达失败 → 先自愈再等 ----
                if (!result.delivered) {
                    val profile = SiteProfiles.of(session.channel)
                    val healed = repo.ensureHealthy(profile, force = true)
                    if (healed == null) {
                        lastMessage = "会话已失效且自动续期失败，任务中止。请重新登录后重试"
                        break@loop
                    }
                    JxauLog.i("会话已自愈，继续重试：${task0.title}")
                }

                // ---- 间隔 + 抖动 ----
                val wait = config.intervalMs + Random.nextLong(0, config.jitterMs + 1)
                delay(wait)
            }
        } finally {
            // 协程被取消（用户停止服务/进程被杀）≠ 失败：退回等待，下次引擎启动接着跑
            val cancelled = !coroutineContext.isActive
            store.updateOne(task0.classNo) {
                it.copy(
                    state = when {
                        cancelled -> RushState.WAITING
                        finalState == RushState.SUCCESS -> RushState.SUCCESS
                        else -> RushState.FAILED
                    },
                    attempts = attempts,
                    lastMessage = when {
                        cancelled -> "引擎已停止，任务退回等待队列"
                        lastMessage.isBlank() -> it.lastMessage
                        else -> lastMessage
                    },
                    lastAt = System.currentTimeMillis(),
                )
            }
            onTaskUpdate(store.tasks.value.first { it.classNo == task0.classNo })
            JxauLog.i(
                "抢课结束：${task0.title} → ${if (cancelled) "被取消" else finalState.label}，" +
                    "共 $attempts 次提交。$lastMessage"
            )
        }
    }

    /** 取当前可用会话；会话形同虚设时先走一次带节流的健康检查 */
    private suspend fun currentSession() =
        repo.ensureHealthy(SiteProfiles.of(repo.currentChannel()))

    private fun describe(result: WriteResult, attempts: Int): String = when {
        !result.delivered -> "第 $attempts 次提交未送达（会话或网络问题），自愈后重试"
        result.ok == true -> "第 $attempts 次提交回执成功，正在回查确认…"
        result.ok == false -> "第 $attempts 次被拒：${result.message}"
        else -> "第 $attempts 次回执不明，正在回查确认…"
    }
}

/** 引擎只关心提交三件套（JxbBh/Xklb/pcid），其余字段填零值即可 */
private fun RushTask.toCourseClass() = CourseClass(
    classNo = classNo,
    className = className,
    teacher = teacher,
    credit = credit,
    selectCategory = selectCategory,
    batchId = batchId,
)
