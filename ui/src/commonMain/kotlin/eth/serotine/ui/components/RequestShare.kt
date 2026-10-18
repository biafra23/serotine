package eth.serotine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Test tag of a signing request's QR code, for UI tests. */
const val SAFE_TX_QR_TAG = "safe-tx-qr"

/** Test tag of a signing request's text, for UI tests. */
const val SAFE_TX_REQUEST_TAG = "safe-tx-request"

/**
 * The request as it is shared with another device (docs/wallet-ux.md, *Co-signing a Safe
 * transaction*): its [text]; [qr] when it fits one QR code (otherwise it is shared as text); and
 * [note], what it carries.
 */
data class ShareItem(val text: String, val qr: Boolean, val note: String)

/**
 * A signing request's [text] (what another device imports) as it is shared, carrying [signatures]
 * signatures: one QR code when it fits one, the text always.
 */
internal fun shareItem(text: String, signatures: Int): ShareItem {
    val carries = "The raw transaction and " + when (signatures) {
        0 -> "no signature yet"
        1 -> "1 signature"
        else -> "$signatures signatures"
    } + ": the other device decodes it and checks it against the chain itself."
    val fits = QrMatrix.fits(text.encodeToByteArray().size)
    return ShareItem(text, fits, if (fits) carries else "Too large for one QR code: share it as text. $carries")
}

/**
 * The request as it travels to another device: one QR code where it fits, its text always, Copy
 * and, where the platform has a share sheet, Share; Close where [onClose] is given.
 */
@Composable
internal fun RequestShareContent(
    share: ShareItem,
    onCopy: (String) -> Unit,
    onShareText: ((String) -> Unit)?,
    onClose: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Heading("Share request")
        if (share.qr) {
            QrCode(
                share.text,
                description = "QR code of the signing request",
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag(SAFE_TX_QR_TAG),
            )
        }
        Note(share.note)
        SelectionContainer {
            Text(
                share.text,
                Modifier.fillMaxWidth().testTag(SAFE_TX_REQUEST_TAG),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 4,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onCopy(share.text) }) {
                Icon(SerotineIcons.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Copy text")
            }
            if (onShareText != null) {
                OutlinedButton(onClick = { onShareText(share.text) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Share")
                }
            }
            onClose?.let { TextButton(onClick = it) { Text("Close") } }
        }
    }
}
