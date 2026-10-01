package org.opendots.agent.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import org.opendots.agent.R

object AgentCancellation {
    private val cancelled = AtomicBoolean(false)
    fun reset() = cancelled.set(false)
    fun cancel() = cancelled.set(true)
    fun isCancelled(): Boolean = cancelled.get()
}

class AgentControlService : Service() {
    companion object {
        private const val CHANNEL = "open_dots_device_control"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "org.opendots.agent.STOP_CONTROL"

        fun start(context: Context) {
            AgentCancellation.reset()
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentControlService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentControlService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Device control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown only while Open Dots is actively controlling the device."
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AgentCancellation.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val stopIntent = Intent(this, AgentControlService::class.java).setAction(ACTION_STOP)
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Agent controlling device")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
