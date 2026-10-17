package eth.serotine.android.upgrade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eth.serotine.android.SerotineApplication

/**
 * Shows the upgrade reminder that is due and arms the next one: woken by the alarm
 * [AndroidUpgradeNotifier] sets, and after a reboot or an update of the app, which both clear
 * alarms. Reads the stored record only, so it never starts the engine. After an update the
 * record belongs to the old version and reads as none, which cancels the old version's alarm.
 */
class UpgradeReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REMIND, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val pending = goAsync()
        (context.applicationContext as SerotineApplication).remindOfUpgrades { pending.finish() }
    }

    internal companion object {
        const val ACTION_REMIND = "eth.serotine.action.REMIND_OF_UPGRADE"
    }
}
