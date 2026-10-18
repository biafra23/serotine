package eth.serotine.android.keys

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * The app's activity in front of the user, if one is: what the biometric prompt is shown on
 * ([PlatformBiometricPrompter]). Main thread only, as the lifecycle callbacks are.
 */
class ForegroundActivity : Application.ActivityLifecycleCallbacks {

    /** The resumed activity, or null while none of the app's is. */
    var current: Activity? = null
        private set

    override fun onActivityResumed(activity: Activity) {
        current = activity
    }

    override fun onActivityPaused(activity: Activity) {
        if (current === activity) current = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
