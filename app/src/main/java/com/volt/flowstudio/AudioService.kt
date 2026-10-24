package com.volt.flowstudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 前台服务：屏幕关掉、切到后台时音频线程继续跑。
 * 没有这个，Android 会在几十秒内掐掉音频。
 */
class AudioService : Service() {

    companion object {
        private const val CHANNEL_ID = "flow_playback"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "com.volt.flowstudio.START"
        const val ACTION_STOP = "com.volt.flowstudio.STOP"
        const val ACTION_PAUSE = "com.volt.flowstudio.PAUSE"

        fun start(context: Context) {
            val i = Intent(context, AudioService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AudioService::class.java).apply { action = ACTION_STOP })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                (application as FlowApp).session.pause()
            }
            else -> {
                createChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "播放中",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持音频引擎在前台运行"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 1, Intent(this, AudioService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("FlowStudio")
            .setContentText("音频引擎运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "暂停", pauseIntent)
            .setOngoing(true)
            .build()
    }
}

/** 应用级单例，持有会话。Activity 重建时音频不中断。 */
class FlowApp : android.app.Application() {
    lateinit var session: com.volt.flowstudio.session.StudioSession
        private set

    override fun onCreate() {
        super.onCreate()
        com.volt.flowstudio.audio.NativeEngine.loadLibrary()
        session = com.volt.flowstudio.session.StudioSession(this)
        session.boot()
    }

    override fun onTerminate() {
        session.shutdown()
        super.onTerminate()
    }
}
