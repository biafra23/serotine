package eth.serotine.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * A capture of a protected screen the platform saw happen but could not block: iOS, where no API
 * blocks a screenshot, tells the app after one is taken and while the screen is recorded,
 * mirrored or shared (rule 8 of docs/keys-and-signing.md).
 */
enum class ScreenCaptureEvent {
    /** A screenshot was just taken. */
    SCREENSHOT,

    /** The screen is being recorded, mirrored or shared. */
    RECORDING,
}

/**
 * The warning a protected screen (one that shows a recovery phrase or takes the wallet password)
 * raises on [event]: what was captured, and what to do about it.
 */
@Composable
fun ScreenCaptureWarningDialog(event: ScreenCaptureEvent, onDismiss: () -> Unit) {
    val (title, text) = when (event) {
        ScreenCaptureEvent.SCREENSHOT -> "Screenshot taken" to
            "A screenshot was just taken of a screen that can show your recovery phrase or your wallet " +
            "password as you type it. If it shows either, delete it from Photos, then from Recently Deleted " +
            "and from any cloud copy: anyone who sees a recovery phrase can take everything in its accounts."
        ScreenCaptureEvent.RECORDING -> "Your screen is being recorded" to
            "Something is recording, mirroring or sharing your screen, and this screen can show your recovery " +
            "phrase or your wallet password as you type it. Stop the recording before you go on."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
