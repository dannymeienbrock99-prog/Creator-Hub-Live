import SwiftUI

struct ContentView: View {
    @StateObject private var chat = LiveChatViewModel()
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            CameraView()
                .tabItem { Label("Live", systemImage: "video.fill") }
                .tag(0)

            ChatView(viewModel: chat)
                .tabItem { Label("Chat", systemImage: "message.fill") }
                .tag(1)

            SettingsView(viewModel: chat)
                .tabItem { Label("Einstellungen", systemImage: "gearshape.fill") }
                .tag(2)
        }
        .preferredColorScheme(.dark)
    }
}
