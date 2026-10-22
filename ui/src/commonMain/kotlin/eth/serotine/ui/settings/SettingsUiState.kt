package eth.serotine.ui.settings

import eth.serotine.ui.components.LightClientItem

/**
 * Everything the Settings screen renders (docs/wallet-ux.md, screen 18), as far as the
 * wallet has settings yet: the display [currency] ("EUR"), the [tokens] it knows ("3 built
 * in · 1 added"), the [network] it works on, the [lightClient]'s state, whether the Debug tab
 * is on ([debugScreen]), and the app's [version]. The security settings join it with the
 * features they belong to.
 *
 * [screenCaptureAllowed] is the debug build's switch for screen recordings of the protected
 * screens (`ScreenCaptureDebugSwitch`): whether it allows capture, or null where there is no
 * such switch, a release build or a host whose screens block nothing, and Settings shows no row.
 */
data class SettingsUiState(
    val network: String,
    val lightClient: LightClientItem,
    val version: String,
    val tokens: String = "",
    val currency: String = "",
    val screenCaptureAllowed: Boolean? = null,
    val debugScreen: Boolean = false,
)
