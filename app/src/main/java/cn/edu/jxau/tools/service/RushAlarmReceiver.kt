package cn.edu.jxau.tools.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.PendingTaskStore

/**
 * 定时触发抢课的 AlarmReceiver。
 *
 * 收到闹钟就拉起前台服务，其余交给 [RushService]——收发两端只共享一个 ACTION，
 * 没有「闹钟专用」的第二条执行路径，避免两套逻辑漂移。
 */
class RushAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 落盘的「待触发时刻」到此为止：它已经用过一次了。不清掉的话，
        // 下次重启会被 BootReceiver 当成「还没执行的定时」——而那个时刻早已过去，
        // 只会在日志里留下一条自相矛盾的记录。
        PendingTaskStore.get(context).rushTriggerAt = 0L
        JxauLog.i("抢课闹钟触发，拉起前台服务")
        RushService.start(context)
    }
}
