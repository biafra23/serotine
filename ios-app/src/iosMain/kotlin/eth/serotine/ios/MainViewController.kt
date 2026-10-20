package eth.serotine.ios

import androidx.compose.ui.window.ComposeUIViewController
import eth.serotine.ui.App
import eth.serotine.ui.AppGraph
import platform.UIKit.UIViewController

/**
 * The iOS composition root's view, the Xcode app's one screen (xcode/Serotine/SerotineApp.swift):
 * the shared [App] on the [AppGraph] of [wallet], the process's one [SerotineWallet].
 */
@Suppress("FunctionName", "unused") // called from Swift
fun MainViewController(wallet: SerotineWallet): UIViewController = ComposeUIViewController { App(wallet.graph) }
