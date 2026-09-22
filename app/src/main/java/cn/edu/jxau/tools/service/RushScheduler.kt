package cn.edu.jxau.tools.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.PendingTaskStore

/**
 * 抢课的定时触发。
 *
 * 精确闹钟（`setExactAndAllowWhileIdle`）在 Android 12+ 需要 `SCHEDULE_EXACT_ALARM`
 * 且用户可能在设置里关掉——这里不硬扛：`canScheduleExactAlarms()` 为 false 时
 * **显式退化为非精确闹钟**并记日志，让 UI 能把「定时可能偏差最多约 15 分钟」告诉用户。
 * 抢课秒级精度必须依赖精确闹钟，退化的定时只能当提醒用——这一点必须诚实。
 *
 * ## 待触发时刻**必须落盘**（本轮修掉的既有缺陷）
 * 闹钟活在系统的 `AlarmManager` 里，**设备重启就没了**，而内存里的时刻也跟着进程一起没。
 * 之前没落盘 → 重启后既没有闹钟、也没有「本该有个闹钟」的痕迹，
 * 表现是**定时抢课静默失效**（用户以为还挂着，到点什么都没发生）。
 * 现在 [schedule] 顺手落盘、[cancel] 顺手清掉，重启后由 [restorePending] 照它重排。
 */
object RushScheduler {

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    /**
     * 定在 [triggerAtMillis] 拉起抢课服务。返回是否为精确闹钟。
     */
    fun schedule(context: Context, triggerAtMillis: Long): Boolean {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context)
        val exact = canScheduleExact(context)
        if (exact) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        } else {
            // 退化：仅提醒级精度（系统可能推迟到下一个维护窗口，最坏约 15 分钟）
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            JxauLog.w("未授予精确闹钟权限，定时触发已退化为非精确（可能偏差约 15 分钟）")
        }
        PendingTaskStore.get(context).rushTriggerAt = triggerAtMillis
        JxauLog.i("已设定抢课定时触发：$triggerAtMillis（精确=$exact）")
        return exact
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(context))
        PendingTaskStore.get(context).rushTriggerAt = 0L
        JxauLog.i("已取消抢课定时触发")
    }

    /**
     * 重启 / 强制停止 / App 启动后，照落盘的时刻把闹钟补回来。
     *
     * 已经过去的时刻直接丢弃：补一个过去的闹钟只会立刻触发一次（等于在错误的时刻抢课），
     * 而「已经不成立了」这件事必须落进日志和落盘状态里，不能留在那儿当幽灵。
     */
    fun restorePending(context: Context) {
        val store = PendingTaskStore.get(context)
        val at = store.rushTriggerAt
        if (at <= 0L) return
        if (at <= System.currentTimeMillis()) {
            store.rushTriggerAt = 0L
            JxauLog.w("抢课定时的时刻（$at）已经过去，不再补排")
            return
        }
        JxauLog.i("发现未执行的抢课定时，重新排入闹钟")
        schedule(context, at)
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, RushAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val REQUEST_CODE = 4201
}
