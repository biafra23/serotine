package eth.serotine.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Test tag of the network-upgrade banner, for UI tests. */
const val UPGRADE_BANNER_TAG = "upgrade-banner"

/**
 * A network upgrade this version does not support, as a banner: [item]'s title, text and who
 * reports it, in the error colours when [UpgradeBannerItem.urgent] and the tertiary ones
 * otherwise, with the way to the update. The text reads as one message to a screen reader.
 */
@Composable
internal fun UpgradeBanner(item: UpgradeBannerItem, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().testTag(UPGRADE_BANNER_TAG),
        color = if (item.urgent) colors.errorContainer else colors.tertiaryContainer,
        contentColor = if (item.urgent) colors.onErrorContainer else colors.onTertiaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                Text(item.details, style = MaterialTheme.typography.labelSmall)
            }
            GetTheUpdateButton()
        }
    }
}

/** Opens [RELEASES_URL]. */
@Composable
private fun GetTheUpdateButton() {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { openLink(RELEASES_URL, uriHandler::openUri) }) { Text("Get the update") }
}

/**
 * Asks [open] for [url]. A device with no browser throws, and the way to the update must never
 * crash the app that points to it: the update screen shows the address as text too.
 */
internal fun openLink(url: String, open: (String) -> Unit) {
    try {
        open(url)
    } catch (e: Exception) {
        // Nothing opens links here: the address is on the update screen to copy.
    }
}
