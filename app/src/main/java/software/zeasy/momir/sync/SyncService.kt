package software.zeasy.momir.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import software.zeasy.momir.R
import software.zeasy.momir.data.ArtPack
import software.zeasy.momir.data.CardRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs a resync in the foreground so Android does not kill it halfway through a
 * 23 MB download. A partial sync is safe - the corpus only ever grows and the
 * art pack is append-only - but a sync that dies at card 9,000 every time is not
 * much use.
 */
class SyncService : Service() {

    sealed class Status {
        data class Running(val stage: String, val done: Int = 0, val total: Int = 0) : Status()
        data class Finished(val outcome: ScryfallSync.Outcome) : Status()
        object Idle : Status()
    }

    private val cancelled = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("Starting resync", 0, 0))
        acquireWakeLock()

        worker = thread(name = "momir-sync") {
            val repository = CardRepository(this)
            val artPack = ArtPack(java.io.File(getExternalFilesDir(null), CardRepository.ART_NAME))

            if (!repository.open()) {
                publish(Status.Finished(
                    ScryfallSync.Outcome(0, 0, 0, 0, null, "No corpus on the device yet - push one with momirdeck")
                ))
                finish()
                return@thread
            }
            artPack.open()

            val sync = ScryfallSync(repository, artPack)
            val outcome = sync.run(object : ScryfallSync.Progress {
                override fun onStage(stage: String) {
                    publish(Status.Running(stage))
                    notify(stage, 0, 0)
                }

                override fun onProgress(done: Int, total: Int) {
                    publish(Status.Running("Artwork $done / $total", done, total))
                    if (done % 25 == 0 || done == total) notify("Artwork", done, total)
                }

                override fun isCancelled() = cancelled.get()
            })

            artPack.close()
            repository.close()
            publish(Status.Finished(outcome))
            finish()
        }

        return START_NOT_STICKY
    }

    private fun finish() {
        releaseWakeLock()
        worker = null
        stopForeground(true)
        stopSelf()
    }

    private fun publish(status: Status) {
        Handler(Looper.getMainLooper()).post {
            lastStatus = status
            listener?.invoke(status)
        }
    }

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "momir:sync").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notify(text: String, done: Int, total: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text, done, total))
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Card sync", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .apply { if (total > 0) setProgress(total, done, false) }
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    companion object {
        private const val CHANNEL_ID = "momir_sync"
        private const val NOTIFICATION_ID = 42
        const val ACTION_CANCEL = "software.zeasy.momir.CANCEL_SYNC"

        @Volatile
        var lastStatus: Status = Status.Idle
            private set

        /** The activity is the only listener; a broadcast round-trip would be overkill. */
        @Volatile
        var listener: ((Status) -> Unit)? = null

        fun start(context: Context) {
            context.startService(Intent(context, SyncService::class.java))
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, SyncService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
