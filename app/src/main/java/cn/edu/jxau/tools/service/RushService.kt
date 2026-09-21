package cn.edu.jxau.tools.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.edu.jxau.tools.MainActivity
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.RushEngine
import cn.edu.jxau.tools.data.RushStore
import cn.edu.jxau.tools.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 抢课前台服务。
 *
 * 为什么是前台服务而不是 App 内协程：抢课一旦开始就必须**活得比界面久**——
 * 用户锁屏、切走、甚至滑掉 Activity，服务都要继续提交。前台服务配常驻通知，
 * 这也是系统对这类长时间后台工作唯一不压制的形态。
 *
 * 启动方式两种：
 * 1. 用户在抢课页点「开始」（立即执行）；
 * 2. AlarmManager 定时触发（[RushAlarmReceiver] 转发同一个 ACTION）。
 */
class RushService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                JxauLog.i("抢课服务：收到停止指令")
                RushStore.get(applicationContext).setRunning(false)
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startRush()
        }
        // 服务被系统杀掉后重建时，若还有 WAITING 任务就值得继续；没有任务时 runAll 会立刻退出
        return START_STICKY
    }

    private fun startRush() {
        val notification = buildNotification("抢课引擎已启动", "正在排队执行任务…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (engineJob?.isActive == true) {
            JxauLog.w("抢课引擎已在运行，忽略重复启动")
            return
        }

        val repo = SessionRepository.get(applicationContext)
        val store = RushStore.get(applicationContext)
        store.setRunning(true)
        val engine = RushEngine(repo, store)

        engineJob = scope.launch {
            try {
                engine.runAll { task ->
                    updateNotification(
                        "抢课：${task.title}",
                        "${task.state.label} · 第 ${task.attempts} 次 · ${task.lastMessage.take(60)}",
                    )
                }
            } catch (e: Exception) {
                JxauLog.e("抢课引擎异常退出", e)
            } finally {
                JxauLog.i("抢课引擎结束，服务自行停止")
                store.setRunning(false)
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private var engineJob: kotlinx.coroutines.Job? = null

    override fun onDestroy() {
        // scope.cancel 会让引擎里的 delay/挂起点抛 CancellationException，
        // 引擎的 finally 会把 RUNNING 任务退回 WAITING（下次启动接着跑）
        scope.cancel()
        engineJob = null
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun buildNotification(title: String, text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "抢课进度", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "rush_progress"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "cn.edu.jxau.tools.rush.START"
        const val ACTION_STOP = "cn.edu.jxau.tools.rush.STOP"

        fun start(context: Context) {
            val intent = Intent(context, RushService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RushService::class.java).setAction(ACTION_STOP))
        }
    }
}
