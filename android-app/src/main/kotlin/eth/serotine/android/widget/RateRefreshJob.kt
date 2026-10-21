package eth.serotine.android.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import eth.serotine.android.SerotineApplication
import kotlinx.coroutines.Job
import java.util.concurrent.TimeUnit

/**
 * Refreshes the exchange-rate widget while the app is closed, sparing the battery and metered
 * data: only while a widget is placed, about every [PERIOD_HOURS] hours, and only on an
 * unmetered network with a battery that is not low, which JobScheduler batches with other
 * apps' work and holds back in Doze and Data Saver. A run wakes the engine without the app's
 * other background work ([SerotineApplication.refreshRateInBackground]), reads the rate and
 * the one of a day before, draws it and lets the engine sleep again; it is given a few minutes
 * for the light client to catch up, and one that runs out draws nothing new. Placing a widget
 * also asks for one run as soon as an unmetered network is there, so it does not stay empty
 * until the first period. Persisted, so the periodic job survives a reboot.
 */
class RateRefreshJob : JobService() {

    /** One run of a job: [job] reads the rate, null when there was nothing to read. */
    private class Run {
        var job: Job? = null
    }

    /**
     * The runs in progress, per job id: the periodic one and the one asked for on placing a
     * widget can run at once. Main thread only. A run's callback that finds another run (or
     * none) under its id was stopped meanwhile and does nothing.
     */
    private val running = mutableMapOf<Int, Run>()

    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run()
        running[params.jobId] = run
        // The callback comes on a later turn of the main thread, never within this call.
        run.job = (application as SerotineApplication).refreshRateInBackground {
            if (running[params.jobId] === run) {
                running.remove(params.jobId)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.job?.cancel()
        return false // a periodic job runs again in its next period anyway
    }

    companion object {
        private const val PERIODIC_JOB_ID = 2
        private const val NOW_JOB_ID = 3
        private const val PERIOD_HOURS = 3L
        private const val FLEX_HOURS = 1L

        /**
         * Keeps the periodic refresh scheduled, its period kept when it is already, and with
         * [now] asks for one run on the next unmetered network too. Calls the system's job
         * service: not on a main thread.
         */
        fun schedule(context: Context, now: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val service = ComponentName(context, RateRefreshJob::class.java)
            if (scheduler.getPendingJob(PERIODIC_JOB_ID) == null) {
                scheduler.schedule(
                    JobInfo.Builder(PERIODIC_JOB_ID, service)
                        .setPeriodic(TimeUnit.HOURS.toMillis(PERIOD_HOURS), TimeUnit.HOURS.toMillis(FLEX_HOURS))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .setPersisted(true)
                        .build(),
                )
            }
            if (now) {
                scheduler.schedule(
                    JobInfo.Builder(NOW_JOB_ID, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
            }
        }

        /** Cancels the refresh, once no widget is placed. Calls the system's job service: not on a main thread. */
        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(PERIODIC_JOB_ID)
            scheduler.cancel(NOW_JOB_ID)
        }
    }
}
