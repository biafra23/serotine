package eth.serotine.android.upgrade

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import eth.serotine.android.R
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.repository.UpgradeNotifier
import eth.serotine.ui.update.UpgradeNotificationText

/**
 * [UpgradeNotifier] on Android: a notification on the "Network upgrades" channel, which
 * opens the app, and an inexact alarm ([AlarmManager.setAndAllowWhileIdle], no exact-alarm
 * permission) that wakes [UpgradeReminderReceiver] for the next reminder, the app running or
 * not. Alarms do not survive a reboot or an update; the receiver arms the next one again
 * then. Without the notification permission (Android 13 and later), a notice is dropped: the
 * app's own screens still say it.
 */
class AndroidUpgradeNotifier(private val context: Context, private val network: Network) : UpgradeNotifier {

    override fun show(notice: UpgradeNotice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.upgrade_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.upgrade_channel_description) },
        )
        val text = UpgradeNotificationText.from(notice, network)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_upgrade)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setStyle(Notification.BigTextStyle().bigText(text.text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            // One notification id: a newer notice replaces the one before.
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // The permission was revoked in between: the app's screens still say it.
        }
    }

    override fun remindAt(at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val remind = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, UpgradeReminderReceiver::class.java).setAction(UpgradeReminderReceiver.ACTION_REMIND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            alarms.cancel(remind)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1_000, remind)
        }
    }

    private companion object {
        const val CHANNEL_ID = "network-upgrades"
        const val NOTIFICATION_ID = 1
    }
}
