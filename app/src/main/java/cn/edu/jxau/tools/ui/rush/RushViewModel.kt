package cn.edu.jxau.tools.ui.rush

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.RushStore
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.model.CourseClass
import cn.edu.jxau.tools.data.model.RushConfig
import cn.edu.jxau.tools.data.model.RushState
import cn.edu.jxau.tools.data.model.RushTask
import cn.edu.jxau.tools.service.RushScheduler
import cn.edu.jxau.tools.service.RushService

/**
 * 抢课页状态都住在 [RushStore]（进程级单例）：任务、配置、引擎是否在跑。
 * ViewModel 只是把它们暴露给界面 + 提供动作，自身不持有状态——
 * 服务、界面、选课页要看到同一份任务列表。
 */
class RushViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RushStore.get(application)
    private val repo = SessionRepository.get(application)

    val tasks = store.tasks
    val config = store.config
    val running = store.running

    /** 当前会话通道（演练模式时界面要提示） */
    val isMock: Boolean get() = repo.isMockActive()

    /** 加入一条任务。同一教学班已有未终结任务时返回 false（去重） */
    fun addTask(course: CourseClass): Boolean {
        if (course.classNo.isBlank()) return false
        val added = store.add(
            RushTask(
                classNo = course.classNo,
                className = course.className,
                selectCategory = course.selectCategory,
                batchId = course.batchId,
                teacher = course.teacher,
                credit = course.credit,
                createdAt = System.currentTimeMillis(),
            )
        )
        if (added) {
            JxauLog.i("已加入抢课队列：${course.className}（${course.classNo}）")
        } else {
            JxauLog.w("抢课任务重复，忽略：${course.classNo}")
        }
        return added
    }

    fun removeTask(classNo: String) {
        store.remove(classNo)
    }

    fun clearFinished() {
        store.clearFinished()
    }

    fun setConfig(config: RushConfig) {
        store.setConfig(config)
    }

    /** 立即开始：拉起前台服务，引擎按顺序处理 WAITING 任务 */
    fun startNow() {
        store.setRunning(true)
        RushService.start(getApplication())
    }

    fun stop() {
        RushService.stop(getApplication())
        // running 标志由服务 onDestroy/引擎结束翻回 false；这里是双保险
        store.setRunning(false)
    }

    /**
     * 定时开始：到点由闹钟拉起服务。
     * @return 是否为精确闹钟（false 时 UI 必须提示「可能偏差约 15 分钟」）
     */
    fun scheduleAt(triggerAtMillis: Long): Boolean =
        RushScheduler.schedule(getApplication(), triggerAtMillis)

    fun cancelSchedule() = RushScheduler.cancel(getApplication())

    /** 有没有正在排队的任务（决定开始按钮的可用性与文案） */
    fun hasPending(): Boolean = tasks.value.any { it.state == RushState.WAITING }
}
