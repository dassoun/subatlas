import SwiftUI
import Shared

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea()
                // Plongée partagée par un binôme : subatlas://dive?d=…
                .onOpenURL { url in
                    IncomingLink.shared.pending = url.absoluteString
                }
        }
    }
}
