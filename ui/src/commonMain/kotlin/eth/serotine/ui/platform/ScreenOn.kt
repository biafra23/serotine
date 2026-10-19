package eth.serotine.ui.platform

import androidx.compose.runtime.Composable

/**
 * Keeps the display from turning off while the caller is in composition, where the platform
 * lets an app ask: Create account holds it while its new phrase is shown and checked, because
 * a screen that times out is a screen left, and that clears the phrase while the user is still
 * writing it down (docs/keys-and-signing.md, *Screen protections*). The Safe screens hold it
 * while they show "Checking the Safe on chain…" (Import, Sign, Propose, and Approve and Execute's
 * quote): that check can take up to 45 s, longer than a short screen timeout, and the screen
 * turning off stops a check before Sign.
 */
@Composable
expect fun KeepScreenOn()
