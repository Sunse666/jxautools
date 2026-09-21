package cn.edu.jxau.tools.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.jxau.tools.core.JxauLog

/**
 * 定时触发抢课的 AlarmReceiver。
 *
 * 收到闹钟就拉起前台服务，其余交给 [RushService]——收发两端只共享一个 ACTION，
 * 没有「闹钟专用」的第二条执行路径，避免两套逻辑漂移。
 */
class RushAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        JxauLog.i("抢课闹钟触发，拉起前台服务")
        RushService.start(context)
    }
}
