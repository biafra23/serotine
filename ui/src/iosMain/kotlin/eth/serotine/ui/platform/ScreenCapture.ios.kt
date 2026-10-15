package eth.serotine.ui.platform

import androidx.compose.runtime.Composable

/**
 * iOS has no API that blocks a screenshot. What rule 8 of docs/keys-and-signing.md asks
 * for there — covering the window when the app resigns active, warning on
 * `UIScreen.isCaptured` and on the screenshot notification — is the iOS host's to wire,
 * and it does not exist yet; until it does, this reports no protection.
 */
@Composable
actual fun protectFromScreenCapture(): Boolean = false
