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
 * reports ([SerotineApplication.holdEngine]) notifies the user. Once peers have confirmed an
 * upgrade activated and the user has been told, it ends at once: all is said, and only an
 * update changes it. Persisted, so it survives a reboot; JobScheduler, not a foreground
 * service, since it is short and bounded.
 */
class UpgradeCheckJob : JobService() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The run in progress; main thread only. A callback of a run that was stopped meanwhile
     * finds another one here, or none, and does nothing.
     */
    private var current: Run? = null

    private inner class Run(private val params: JobParameters) {
        var holding = false
        val end = Runnable { finish() }

        fun finish() {
            if (current !== this) return
            current = null
            if (holding) (application as SerotineApplication).releaseEngine()
            jobFinished(params, false)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as SerotineApplication
        val run = Run(params)
        current = run
        app.whenUpgradeCheckWanted { wanted ->
            if (current !== run) return@whenUpgradeCheckWanted
            if (!wanted) {
                run.finish()
                return@whenUpgradeCheckWanted
            }
            app.holdEngine()
            run.holding = true
            handler.postDelayed(run.end, CHECK_WINDOW_MS)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val run = current ?: return false
        current = null
        handler.removeCallbacks(run.end)
        if (run.holding) (application as SerotineApplication).releaseEngine()
        return false // a periodic job runs again in its next period anyway
    }

    companion object {
        private const val JOB_ID = 1

        /** Long enough for the engine to boot, dial peers and hear their fork ids. */
        private val CHECK_WINDOW_MS = TimeUnit.MINUTES.toMillis(5)

        /**
         * Schedules the daily check, unless it is scheduled already: its period is kept. Calls
         * the system's job service: not on a main thread.
         */
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
