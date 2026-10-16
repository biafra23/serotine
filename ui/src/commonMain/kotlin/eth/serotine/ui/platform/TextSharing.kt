package eth.serotine.ui.platform

import androidx.compose.runtime.Composable

/**
 * Puts a text on the system clipboard: what Copy does with an address. A public address
 * only; nothing secret is ever copied through this (CLAUDE.md, *Trust*).
 */
@Composable
expect fun rememberCopyText(): (String) -> Unit

/**
 * Hands a text to the platform's share sheet, or null where there is none (the desktop), in
 * which case the screen offers no Share button. A public address only.
 */
@Composable
expect fun rememberShareText(): ((String) -> Unit)?
