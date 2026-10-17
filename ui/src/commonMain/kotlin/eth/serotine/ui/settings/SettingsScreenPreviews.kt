package eth.serotine.ui.settings

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Network
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Settings screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. SettingsScreenPreviewsTest renders each one in settingsScreenPreviews, at the end.

private val network = Network.SEPOLIA.displayName

private const val VERSION = "0.1.0"

private val synced = SettingsUiState(
    network,
    LightClientItem("Verified at block 8,412,307 · 8 s ago", null, false),
    VERSION,
    tokens = "3 built in · 1 added",
    currency = "EUR",
)

@Composable
private fun SettingsPreview(state: SettingsUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SettingsScreen(state = state, onCurrency = {}, onTokens = {})
    }
}

@Preview
@Composable
private fun SettingsSyncedPreview() = SettingsPreview(synced)

@Preview
@Composable
private fun SettingsSyncedDarkPreview() = SettingsPreview(synced, darkTheme = true)

@Preview
@Composable
private fun SettingsStartingPreview() =
    SettingsPreview(SettingsUiState(network, LightClientItem("Starting the light client…", null, true), VERSION))

@Preview
@Composable
private fun SettingsUnreadablePreview() = SettingsPreview(
    synced.copy(
        tokens = "3 built in · added ones cannot be read",
        currency = "USD (your choice cannot be read)",
    ),
)

/** Every preview above, which SettingsScreenPreviewsTest renders: a new preview goes in here too. */
internal val settingsScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SettingsSyncedPreview() },
    { SettingsSyncedDarkPreview() },
    { SettingsStartingPreview() },
    { SettingsUnreadablePreview() },
)
