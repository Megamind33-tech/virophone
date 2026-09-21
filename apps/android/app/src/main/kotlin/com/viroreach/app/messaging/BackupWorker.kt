package com.viroreach.app.messaging

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.viroreach.app.session.SessionManager
import java.util.concurrent.TimeUnit

/**
 * Backs up once a day, by itself.
 *
 * A backup nobody remembers to run is not a backup, and the whole point of it
 * is the day someone loses their phone without warning. It waits for an
 * unmetered connection and for the phone to be charging: an archive is not
 * worth spending someone's data bundle or their last 10% on.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = SessionManager.get(applicationContext)
        if (!session.isAuthenticated) return Result.success()
        // Nothing to do until the person has turned it on and holds a key.
        if (!session.backup.isOn()) return Result.success()
        return session.backup.backupNow().fold(
            onSuccess = {
                Log.i(TAG, "BACKUP_SCHEDULED_OK messages=${it.messageCount}")
                Result.success()
            },
            onFailure = {
                Log.w(TAG, "BACKUP_SCHEDULED_FAILED ${it.message}")
                Result.retry()
            },
        )
    }

    companion object {
        private const val TAG = "ViroBackup"
        private const val WORK = "viro-chat-backup"

        /** Called at startup; keeps whatever schedule is already running. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .setRequiresCharging(true)
                            .build(),
                    )
                    .build(),
            )
        }
    }
}
