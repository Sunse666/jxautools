package cn.edu.jxau.tools.data

import android.content.Context
import android.content.SharedPreferences
import cn.edu.jxau.tools.data.model.RushConfig
import cn.edu.jxau.tools.data.model.RushState
import cn.edu.jxau.tools.data.model.RushTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 抢课任务的本地存储 + 状态流。
 *
 * 用 SharedPreferences + org.json 手写序列化（项目约定不用 WorkManager/DataStore，
 * kotlinx serialization 的编译插件也不在依赖缓存里）。
 *
 * **必须持久化**：抢课任务可能定在几小时后触发，进程被杀后任务必须还在。
 */
class RushStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tasks = MutableStateFlow<List<RushTask>>(emptyList())
    val tasks: StateFlow<List<RushTask>> = _tasks.asStateFlow()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<RushConfig> = _config.asStateFlow()

    /** 引擎是否在跑（前台服务持有）。UI 据此切「开始/停止」按钮 */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    init {
        _tasks.value = loadTasks()
    }

    // ---------- 任务 ----------

    /** 新任务去重：同一个教学班只允许一条未终结的任务 */
    fun add(task: RushTask): Boolean {
        val current = _tasks.value
        if (current.any {
                it.classNo == task.classNo &&
                    it.state in ACTIVE_STATES
            }) {
            return false
        }
        _tasks.value = current + task
        persist()
        return true
    }

    fun update(transform: (RushTask) -> RushTask) {
        _tasks.value = _tasks.value.map(transform)
        persist()
    }

    fun updateOne(classNo: String, transform: (RushTask) -> RushTask) {
        _tasks.value = _tasks.value.map { if (it.classNo == classNo) transform(it) else it }
        persist()
    }

    fun remove(classNo: String) {
        _tasks.value = _tasks.value.filterNot { it.classNo == classNo }
        persist()
    }

    /** 清掉终态任务（成功/失败/取消），保留进行中的 */
    fun clearFinished() {
        _tasks.value = _tasks.value.filterNot { it.state !in ACTIVE_STATES }
        persist()
    }

    fun pendingTasks(): List<RushTask> = _tasks.value.filter { it.state == RushState.WAITING }

    // ---------- 配置 ----------

    fun setConfig(config: RushConfig) {
        _config.value = config
        prefs.edit()
            .putLong(KEY_INTERVAL, config.intervalMs)
            .putLong(KEY_JITTER, config.jitterMs)
            .putInt(KEY_MAX_ATTEMPTS, config.maxAttempts)
            .putInt(KEY_DEADLINE, config.deadlineMinutes)
            .apply()
    }

    private fun loadConfig(): RushConfig {
        val defaults = RushConfig()
        return RushConfig(
            intervalMs = prefs.getLong(KEY_INTERVAL, defaults.intervalMs).coerceIn(300, 30_000),
            jitterMs = prefs.getLong(KEY_JITTER, defaults.jitterMs).coerceIn(0, 10_000),
            maxAttempts = prefs.getInt(KEY_MAX_ATTEMPTS, defaults.maxAttempts).coerceIn(1, 1000),
            deadlineMinutes = prefs.getInt(KEY_DEADLINE, defaults.deadlineMinutes).coerceIn(1, 240),
        )
    }

    // ---------- 序列化 ----------

    private fun persist() {
        val array = JSONArray()
        _tasks.value.forEach { task ->
            array.put(
                JSONObject()
                    .put("classNo", task.classNo)
                    .put("className", task.className)
                    .put("selectCategory", task.selectCategory)
                    .put("batchId", task.batchId)
                    .put("teacher", task.teacher)
                    .put("credit", task.credit)
                    .put("createdAt", task.createdAt)
                    .put("state", task.state.name)
                    .put("attempts", task.attempts)
                    .put("lastMessage", task.lastMessage)
                    .put("lastAt", task.lastAt)
            )
        }
        prefs.edit().putString(KEY_TASKS, array.toString()).apply()
    }

    private fun loadTasks(): List<RushTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                // 进程死亡时把「进行中」退回「等待中」：引擎重启后能接着跑，而不是永久卡住
                val state = runCatching { RushState.valueOf(o.getString("state")) }
                    .getOrDefault(RushState.WAITING)
                RushTask(
                    classNo = o.getString("classNo"),
                    className = o.optString("className"),
                    selectCategory = o.optString("selectCategory"),
                    batchId = o.optInt("batchId"),
                    teacher = o.optString("teacher"),
                    credit = o.optDouble("credit", 0.0),
                    createdAt = o.getLong("createdAt"),
                    state = if (state == RushState.RUNNING) RushState.WAITING else state,
                    attempts = o.optInt("attempts"),
                    lastMessage = o.optString("lastMessage"),
                    lastAt = o.optLong("lastAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS_NAME = "rush_tasks"
        private const val KEY_TASKS = "tasks_json"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_JITTER = "jitter_ms"
        private const val KEY_MAX_ATTEMPTS = "max_attempts"
        private const val KEY_DEADLINE = "deadline_minutes"

        private val ACTIVE_STATES = setOf(RushState.WAITING, RushState.RUNNING)

        @Volatile
        private var shared: RushStore? = null

        fun get(context: Context): RushStore =
            shared ?: synchronized(this) {
                shared ?: RushStore(context.applicationContext).also { shared = it }
            }
    }
}
