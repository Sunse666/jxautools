package cn.edu.jxau.tools.data

import android.content.Context

/**
 * 「活不过进程、但重启后还要接着做」的后台任务状态。
 *
 * ## 为什么需要它（这是一次既有缺陷修复，不是一个新功能）
 * 闹钟活在系统的 `AlarmManager` 里，**设备一重启就全没了**；而「待触发的抢课时刻」
 * 原本只活在内存里，于是重启后既没有闹钟、也没有「本该有个闹钟」的痕迹 ——
 * 表现是**定时抢课静默失效**：用户以为还挂着，到点什么都没发生。
 *
 * 落盘之后，由 [cn.edu.jxau.tools.service.RushScheduler.restorePending] 在
 * 「开机 / 应用更新 / 系统改时间 / App 启动」四个时机补排。
 *
 * ## 为什么与 `jxau_session` 分开
 * 退出登录会把会话整个扫掉。这里存的是**任务状态**而不是**身份凭据**，
 * 两者的清理时机不同：会话该被清掉，任务状态则由任务自己清
 * （[cn.edu.jxau.tools.service.RushScheduler.cancel]，以及 `ProfileViewModel.logout`）。
 *
 * ## 为什么单独一个类而不是散在调用方
 * 写它的地方（`schedule` / `cancel` / `RushAlarmReceiver` / `restorePending`）与读它的地方
 * 分属不同进程生命周期，键名和「0 = 没有」这条约定必须是**一处定义**。
 */
class PendingTaskStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 待触发的抢课闹钟时刻，毫秒时间戳。**0 = 没有**。
     *
     * 用 0 而不是 null，是因为 SharedPreferences 没有可空 long；
     * 时间戳为 0 在现实中不存在（1970 年），不会与真实值撞上。
     */
    var rushTriggerAt: Long
        get() = prefs.getLong(KEY_RUSH_TRIGGER, 0L)
        set(value) = prefs.edit().putLong(KEY_RUSH_TRIGGER, value).apply()

    companion object {
        private const val PREFS_NAME = "jxau_cache"
        private const val KEY_RUSH_TRIGGER = "rush_trigger_at"

        @Volatile
        private var shared: PendingTaskStore? = null

        /**
         * 全局唯一。闹钟接收器、开机接收器、界面三处读写同一份状态；
         * 各建实例虽然最终落到同一个 prefs 文件，但读到的内存缓存可能不同步。
         */
        fun get(context: Context): PendingTaskStore =
            shared ?: synchronized(this) {
                shared ?: PendingTaskStore(context.applicationContext).also { shared = it }
            }
    }
}
