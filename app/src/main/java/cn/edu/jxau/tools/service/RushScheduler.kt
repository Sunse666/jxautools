package cn.edu.jxau.tools.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.edu.jxau.tools.core.JxauLog

/**
 * 抢课的定时触发。
 *
 * 精确闹钟（`setExactAndAllowWhileIdle`）在 Android 12+ 需要 `SCHEDULE_EXACT_ALARM`
 * 且用户可能在设置里关掉——这里不硬扛：`canScheduleExactAlarms()` 为 false 时
 * **显式退化为非精确闹钟**并记日志，让 UI 能把「定时可能偏差最多约 15 分钟」告诉用户。
 * 抢课秒级精度必须依赖精确闹钟，退化的定时只能当提醒用——这一点必须诚实。
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
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RushAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = canScheduleExact(context)
        if (exact) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        } else {
            // 退化：仅提醒级精度（系统可能推迟到下一个维护窗口，最坏约 15 分钟）
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            JxauLog.w("未授予精确闹钟权限，定时触发已退化为非精确（可能偏差约 15 分钟）")
        }
        JxauLog.i("已设定抢课定时触发：$triggerAtMillis（精确=$exact）")
        return exact
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RushAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.cancel(pending)
        JxauLog.i("已取消抢课定时触发")
    }

    private const val REQUEST_CODE = 4201
}
