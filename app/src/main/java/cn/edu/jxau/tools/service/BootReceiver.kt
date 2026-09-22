package cn.edu.jxau.tools.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.jxau.tools.core.JxauLog

/**
 * 重启 / 应用更新 / 系统时间变化后，把抢课闹钟补回来。
 *
 * ## 这个接收器是**修一个既有缺陷**，不是为新功能加的
 * 之前 `RushScheduler` 没有它：闹钟活在系统的 `AlarmManager` 里，**设备一重启就全没了**，
 * 而「定时抢课」正是靠闹钟触发的 —— 表现是**重启后定时抢课静默失效**，
 * 用户以为还挂着，到点什么都没发生。
 *
 * ## 为什么要管系统时间变化
 * 闹钟用的是 `RTC_WAKEUP`（墙上时间）。用户改了时间 / 换了时区之后，
 * 原来排的绝对时刻还在，但它对应的**本地时刻**已经不是用户设定的那一个了
 * （比如 07:30 变成 14:30）。这跟重启一样是「闹钟内容不再符合用户意图」的情形，
 * 一并重排才是一致的。
 *
 * ## 为什么**不**声明 `LOCKED_BOOT_COMPLETED`
 * 实测（2026-09-22）：direct boot 阶段（用户解锁前）收到广播时读 `shared_prefs`
 * 会拿到**空数据** —— 那是 credential-encrypted 存储，要等解锁才可读。
 * 于是 [RushScheduler.restorePending] 读到 0、静默返回，闹钟补不回来；
 * 而解锁快的时候同一份代码又能读到 → **表现为随机的成功/失败**（第 1 轮验收就偶发过一次）。
 * 抢课本来就需要已登录的会话（同样在那个存储里），在 direct boot 阶段排闹钟没有意义，
 * 所以只声明解锁后的 `BOOT_COMPLETED`，把竞态从设计里去掉。
 *
 * ## 为什么 exported 是 true
 * 这几个都是**受保护广播**，只有系统能发（第三方应用发不出去），
 * 所以 `exported="true"` 不带来伪造风险；反过来，写成 `false` 会有收不到的风险，
 * 而「收不到」在这里等于功能静默失效 —— 选错得起的那个方向。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> {
                JxauLog.w("BootReceiver 收到不关心的广播：${intent.action}")
                return
            }
        }

        JxauLog.i("收到 ${intent.action}，重新排入抢课闹钟")

        // 只在「落盘的时刻还在未来」时补排（见 RushScheduler.restorePending）：
        // 补一个已经过去的时刻，等于在错误的时刻立刻抢一次课。
        RushScheduler.restorePending(context)
    }
}
