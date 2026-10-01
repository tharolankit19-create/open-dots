package org.opendots.agent.scheduler

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.opendots.agent.R

class RoutineWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString("text") ?: return Result.failure()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "open_dots_routines",
                "Open Dots routines",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        if (
            Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(applicationContext).notify(
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                NotificationCompat.Builder(applicationContext, "open_dots_routines")
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(applicationContext.getString(R.string.app_name))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build()
            )
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, text: String, delayMinutes: Long) {
            val request = OneTimeWorkRequestBuilder<RoutineWorker>()
                .setInitialDelay(delayMinutes.coerceAtLeast(1), TimeUnit.MINUTES)
                .setInputData(Data.Builder().putString("text", text).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
