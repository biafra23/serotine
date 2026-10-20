package eth.serotine.ios

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import eth.serotine.ui.App
import eth.serotine.ui.AppGraph
import platform.UIKit.UIViewController

/**
 * The iOS composition root's view, the Xcode app's one screen (xcode/Serotine/SerotineApp.swift):
 * the shared [App] on the [AppGraph] of [wallet], the process's one [SerotineWallet].
 */
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController(wallet: SerotineWallet): UIViewController = ComposeUIViewController(
    // The screens end at the keyboard themselves (imePadding in App.kt); the default would also
    // lift the whole view by the keyboard's height to show the focused field.
    configure = { onFocusBehavior = OnFocusBehavior.DoNothing },
) {
    App(wallet.graph)
    // Runs once the first composition is applied, just before its frame is drawn.
    LaunchedEffect(Unit) { wallet.startupTime("first frame") }
}
