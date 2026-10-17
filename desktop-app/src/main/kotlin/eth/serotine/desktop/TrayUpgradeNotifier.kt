package eth.serotine.desktop

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UpgradeNotice
import eth.serotine.domain.repository.UpgradeNotifier
import eth.serotine.ui.update.UpgradeNotificationText
import java.awt.Color
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * [UpgradeNotifier] on the desktop: a message from the system tray, while the app runs, on a
 * desktop that has a tray (macOS, Windows and most Linux desktops; GNOME only with an
 * extension). The tray icon appears with the first notice and stays until the app quits.
 * Nothing wakes a closed desktop app: a reminder due meanwhile is shown at the next start,
 * from the record, and the window says it all along.
 */
class TrayUpgradeNotifier(private val network: Network) : UpgradeNotifier {

    /** Touched on the AWT event thread only. */
    private var icon: TrayIcon? = null

    override fun show(notice: UpgradeNotice) {
        EventQueue.invokeLater {
            try {
                if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return@invokeLater
                val text = UpgradeNotificationText.from(notice, network)
                val tray = icon ?: TrayIcon(mark(), "Serotine").also {
                    it.isImageAutoSize = true
                    SystemTray.getSystemTray().add(it)
                    icon = it
                }
                tray.displayMessage(text.title, text.text, TrayIcon.MessageType.WARNING)
            } catch (e: Exception) {
                // A tray that refuses the icon, or a notice that cannot be put into words: the
                // window still says it.
                println("Serotine: no tray notice (${e::class.simpleName})")
            }
        }
    }

    /** Nothing to arm: reminders come while the app runs, or from the record at its next start. */
    override fun remindAt(at: Long?) = Unit

    /** The tray's mark: an amber disc, drawn here since the app has no icon file yet. */
    private fun mark(): BufferedImage = BufferedImage(MARK_SIZE, MARK_SIZE, BufferedImage.TYPE_INT_ARGB).apply {
        val graphics = createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color(0xFF, 0xB3, 0x00)
            graphics.fillOval(4, 4, MARK_SIZE - 8, MARK_SIZE - 8)
        } finally {
            graphics.dispose()
        }
    }

    private companion object {
        const val MARK_SIZE = 64
    }
}
