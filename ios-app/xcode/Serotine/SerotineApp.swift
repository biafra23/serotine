import SwiftUI
import SerotineKit

/// The app's shell: everything on screen is the shared Compose UI, on the wallet the composition
/// root (SerotineWallet, ios-app/src/iosMain) builds once for the process, before the first frame.
@main
struct SerotineApp: App {
    private let wallet = SerotineWallet()

    var body: some Scene {
        WindowGroup {
            ComposeView(wallet: wallet)
                .ignoresSafeArea(.keyboard) // Compose handles its own insets
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    let wallet: SerotineWallet

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(wallet: wallet)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
