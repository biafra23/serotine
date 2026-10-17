package eth.serotine.ui.platform

import androidx.compose.runtime.Composable
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** AWT's system clipboard. */
@Composable
actual fun rememberCopyText(): (String) -> Unit = { text ->
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/** A desktop has no share sheet. */
@Composable
actual fun rememberShareText(): ((String) -> Unit)? = null
