package eth.serotine.ui.settings

import androidx.compose.runtime.Composable
import eth.serotine.domain.model.Network
import eth.serotine.ui.components.LightClientHealth.NOT_VERIFIED
import eth.serotine.ui.components.LightClientHealth.STALE
import eth.serotine.ui.components.LightClientHealth.VERIFIED
import eth.serotine.ui.components.LightClientItem
import eth.serotine.ui.theme.SerotineTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

// The Settings screen in Android Studio's preview pane, one preview per state it draws
// differently. Built from display-ready values, as the screen tests are, never from the
// ViewModel. SettingsScreenPreviewsTest renders each one in settingsScreenPreviews, at the end.

private val network = Network.SEPOLIA.displayName

private const val VERSION = "0.1.0"

private val starting = LightClientItem("Starting the light client…", null, true, NOT_VERIFIED)

private val synced = SettingsUiState(
    network,
    LightClientItem("Verified at block 8,412,307 · 8 s ago", null, false, VERIFIED),
    VERSION,
    tokens = "11 built in · 1 added",
    currency = "EUR",
)

@Composable
private fun SettingsPreview(state: SettingsUiState, darkTheme: Boolean = false) {
    SerotineTheme(darkTheme = darkTheme) {
        SettingsScreen(
            state = state,
            onCurrency = {},
            onTokens = {},
            onSecurity = {},
            onScreenCaptureChange = {},
            onDebugScreenChange = {},
        )
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
    SettingsPreview(SettingsUiState(network, starting, VERSION))

/** Synced, but the verified head too old to read at: the light client's dot yellow. */
private val headTooOld = synced.copy(
    lightClient = LightClientItem(
        "Synced · the verified head is 4 min old, waiting for a fresh one",
        null,
        true,
        STALE,
    ),
)

@Preview
@Composable
private fun SettingsHeadTooOldPreview() = SettingsPreview(headTooOld)

@Preview
@Composable
private fun SettingsHeadTooOldDarkPreview() = SettingsPreview(headTooOld, darkTheme = true)

@Preview
@Composable
private fun SettingsUnreadablePreview() = SettingsPreview(
    synced.copy(
        tokens = "11 built in · added ones cannot be read",
        currency = "USD (your choice cannot be read)",
    ),
)

/** The debug build as it starts: its screen-recording switch off. */
@Preview
@Composable
private fun SettingsDebugBuildPreview() = SettingsPreview(synced.copy(screenCaptureAllowed = false))

/** The debug build, its screen-recording switch on. */
@Preview
@Composable
private fun SettingsDebugBuildCaptureAllowedPreview() = SettingsPreview(synced.copy(screenCaptureAllowed = true))

/** The Debug tab turned on. */
@Preview
@Composable
private fun SettingsDebugScreenPreview() = SettingsPreview(synced.copy(debugScreen = true))

/** A switch of the Debug tab that could not be saved. */
@Preview
@Composable
private fun SettingsDebugScreenNotSavedPreview() = SettingsPreview(synced.copy(debugScreenNotSaved = true))

/** Every preview above, which SettingsScreenPreviewsTest renders: a new preview goes in here too. */
internal val settingsScreenPreviews: List<@Composable () -> Unit> = listOf(
    { SettingsSyncedPreview() },
    { SettingsSyncedDarkPreview() },
    { SettingsStartingPreview() },
    { SettingsUnreadablePreview() },
    { SettingsHeadTooOldPreview() },
    { SettingsHeadTooOldDarkPreview() },
    { SettingsDebugBuildPreview() },
    { SettingsDebugBuildCaptureAllowedPreview() },
    { SettingsDebugScreenPreview() },
    { SettingsDebugScreenNotSavedPreview() },
)
