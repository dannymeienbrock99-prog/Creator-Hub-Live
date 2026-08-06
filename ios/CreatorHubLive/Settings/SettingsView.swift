import AVFoundation
import SwiftUI

struct SettingsView: View {
    @ObservedObject var viewModel: LiveChatViewModel
    @AppStorage("overlay_chat") private var showChat = true
    @AppStorage("overlay_gifts") private var showGifts = true
    @AppStorage("overlay_goal") private var showGoal = false
    @AppStorage("overlay_viewers") private var showViewers = true
    @AppStorage("overlay_guests") private var showGuests = true
    @AppStorage("overlay_logo") private var showLogo = true
    @AppStorage("guest_count") private var guestCount = 1
    @AppStorage("guest_auto_layout") private var autoGuestLayout = true
    @AppStorage("guest_host_priority") private var hostPriority = true

    private let filters = ["Alle", "Nur Moderatoren", "Nur Abonnenten", "Mods und Abonnenten", "Ausgewählte Nutzer"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Live-Chat") {
                    TextField("WebSocket-Adresse", text: $viewModel.socketURL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("API-Schlüssel", text: $viewModel.apiKey)
                    TextField("TikTok-Name / Live-Raum", text: $viewModel.roomName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section("Vorlesen") {
                    Toggle("Chat vorlesen", isOn: $viewModel.readAloud)
                    Picker("Filter", selection: $viewModel.filterMode) {
                        ForEach(filters.indices, id: \.self) { index in
                            Text(filters[index]).tag(index)
                        }
                    }
                    TextField("Ausgewählte Nutzer, mit Komma getrennt", text: $viewModel.allowedUsers)
                    Picker("Stimme", selection: $viewModel.selectedVoiceIdentifier) {
                        Text("Systemstandard").tag("")
                        ForEach(viewModel.availableVoices, id: \.identifier) { voice in
                            Text("\(voice.language) · \(voice.name)").tag(voice.identifier)
                        }
                    }
                    VStack(alignment: .leading) {
                        Text("Geschwindigkeit")
                        Slider(value: $viewModel.speechRate, in: AVSpeechUtteranceMinimumSpeechRate...AVSpeechUtteranceMaximumSpeechRate)
                    }
                    VStack(alignment: .leading) {
                        Text("Tonhöhe")
                        Slider(value: $viewModel.pitch, in: 0.5...2.0)
                    }
                    VStack(alignment: .leading) {
                        Text("Lautstärke")
                        Slider(value: $viewModel.volume, in: 0...1)
                    }
                }

                Section("Stream-Overlay") {
                    Toggle("Chat anzeigen", isOn: $showChat)
                    Toggle("Geschenke anzeigen", isOn: $showGifts)
                    Toggle("Live-Ziel anzeigen", isOn: $showGoal)
                    Toggle("Zuschauerzahl anzeigen", isOn: $showViewers)
                    Toggle("Gast-Fenster anzeigen", isOn: $showGuests)
                    Toggle("Creator-Hub-Logo anzeigen", isOn: $showLogo)
                }

                Section("Gast-Live Layout") {
                    Stepper("Gastplätze: \(guestCount)", value: $guestCount, in: 0...8)
                    Toggle("Layout automatisch anpassen", isOn: $autoGuestLayout)
                    Toggle("Host größer darstellen", isOn: $hostPriority)
                }

                Section("USB / Capture") {
                    Text("USB-Capture auf iPhone hängt vom verwendeten USB-C-/UVC-Gerät und der iOS-Unterstützung ab. Gerätezugriff muss später mit echter Hardware getestet werden.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Einstellungen")
        }
    }
}
