package eth.serotine.ui.settings

import eth.serotine.ui.components.LightClientItem

/**
 * Everything the Settings screen renders (docs/wallet-ux.md, screen 18), as far as the
 * wallet has settings yet: the [network] it works on, the [lightClient]'s state, and the
 * app's [version]. The display currency, the tokens and the security settings join it with
 * the features they belong to.
 */
data class SettingsUiState(val network: String, val lightClient: LightClientItem, val version: String)
