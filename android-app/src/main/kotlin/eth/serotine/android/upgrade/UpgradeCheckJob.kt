package eth.serotine.android.upgrade

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import eth.serotine.android.SerotineApplication
import java.util.concurrent.TimeUnit

/**
 * The daily look for network upgrades while the app is not open: about once a day, on any
 * network and a battery that is not low, it keeps the engine awake for [CHECK_WINDOW_MS],
 * long enough for peers to report an upgrade, and the process's own follower of those
 * reports ([SerotineApplication.holdEngine]) notifies the user. Once the user has been told
 * that an upgrade activated, it ends at once: all is said, and only an update changes it.
 * Persisted, so it survives a reboot; JobScheduler, not a foreground service, since it is
 * short and bounded.
 */
class UpgradeCheckJob : JobService() {

    private val handler = Handler(Looper.getMainLooper())

    /** The check holding the engine, until it ends or is stopped; main thread only. */
    private var running: Runnable? = null

    /** Stopped by the system before it began; main thread only. */
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as SerotineApplication
        stopped = false
        app.whenUpgradeCheckWanted { wanted ->
            when {
                stopped -> Unit
                !wanted -> jobFinished(params, false)
                else -> {
                    app.holdEngine()
                    val end = Runnable {
                        running = null
                        app.releaseEngine()
                        jobFinished(params, false)
                    }
                    running = end
                    handler.postDelayed(end, CHECK_WINDOW_MS)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        running?.let {
            handler.removeCallbacks(it)
            running = null
            (application as SerotineApplication).releaseEngine()
        }
        return false // a periodic job runs again in its next period anyway
    }

    companion object {
        private const val JOB_ID = 1

        /** Long enough for the engine to boot, dial peers and hear their fork ids. */
        private val CHECK_WINDOW_MS = TimeUnit.MINUTES.toMillis(5)

        /** Schedules the daily check, unless it is scheduled already: its period is kept. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpgradeCheckJob::class.java))
                .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(6))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }
    }
}
