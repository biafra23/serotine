package eth.serotine.domain.repository

import eth.serotine.domain.model.UpgradeNotice

/**
 * The port through which the user hears about an upgrade outside the app's window: the
 * platform's notifications. Each host implements it with its own (Android's notification
 * manager and alarms, the desktop's system tray), since that needs the host's own activity,
 * icon and window; the decision what to show and when is the domain's
 * ([eth.serotine.domain.usecase.UpgradeNotices]).
 */
interface UpgradeNotifier {
    /**
     * Shows [notice], replacing any upgrade notice shown before. Never throws: where the
     * platform cannot show it (the user turned notifications off, no system tray), it is
     * dropped; the app's own screens still say it.
     */
    fun show(notice: UpgradeNotice)

    /**
     * Asks to be woken at [at] (unix seconds) to show the reminders due then, even when the
     * app is not running, replacing any earlier request; null cancels it. Where the platform
     * cannot wake the app, the reminders come when it next runs.
     */
    fun remindAt(at: Long?)
}
