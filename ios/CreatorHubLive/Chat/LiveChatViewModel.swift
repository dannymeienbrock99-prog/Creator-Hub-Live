import AVFoundation
import Foundation

struct LiveChatMessage: Identifiable {
    let id = UUID()
    let username: String
    let text: String
    let moderator: Bool
    let subscriber: Bool
}

@MainActor
final class LiveChatViewModel: ObservableObject {
    @Published var messages: [LiveChatMessage] = []
    @Published var status = "Nicht verbunden"
    @Published var socketURL = ""
    @Published var apiKey = ""
    @Published var roomName = ""
    @Published var readAloud = true
    @Published var filterMode = 0
    @Published var selectedVoiceIdentifier = ""
    @Published var speechRate: Float = 0.5
    @Published var pitch: Float = 1.0
    @Published var volume: Float = 1.0
    @Published var allowedUsers = ""

    private var task: URLSessionWebSocketTask?
    private let synthesizer = AVSpeechSynthesizer()

    var availableVoices: [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices().sorted {
            ($0.language, $0.name) < ($1.language, $1.name)
        }
    }

    func toggleConnection() {
        task == nil ? connect() : disconnect()
    }

    func connect() {
        guard let url = URL(string: socketURL), ["ws", "wss"].contains(url.scheme?.lowercased() ?? "") else {
            status = "Gültige WebSocket-Adresse eingeben"
            return
        }
        var request = URLRequest(url: url)
        if !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        if !roomName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            request.setValue(roomName, forHTTPHeaderField: "X-Live-Room")
        }
        let socket = URLSession.shared.webSocketTask(with: request)
        task = socket
        status = "Verbinde …"
        socket.resume()
        status = "Verbunden"
        receiveNext()
    }

    func disconnect() {
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        status = "Getrennt"
    }

    func addTestMessage() {
        handle(LiveChatMessage(username: "Crazy_Batto", text: "Das ist eine Testnachricht", moderator: true, subscriber: true))
    }

    private func receiveNext() {
        task?.receive { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .success(let message):
                    let raw: String
                    switch message {
                    case .string(let value): raw = value
                    case .data(let data): raw = String(data: data, encoding: .utf8) ?? ""
                    @unknown default: raw = ""
                    }
                    if let parsed = self.parse(raw) { self.handle(parsed) }
                    self.receiveNext()
                case .failure(let error):
                    self.status = "Verbindung fehlgeschlagen: \(error.localizedDescription)"
                    self.task = nil
                }
            }
        }
    }

    private func parse(_ raw: String) -> LiveChatMessage? {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let payload = (object["data"] as? [String: Any]) ?? (object["payload"] as? [String: Any]) ?? object
        let text = firstString(payload, keys: ["comment", "message", "text", "content"])
        guard !text.isEmpty else { return nil }
        let username = firstString(payload, keys: ["nickname", "username", "uniqueId", "userName", "user"])
        let moderator = (payload["isModerator"] as? Bool) ?? (payload["moderator"] as? Bool) ?? false
        let subscriber = (payload["isSubscriber"] as? Bool) ?? (payload["subscriber"] as? Bool) ?? (payload["isSub"] as? Bool) ?? false
        return LiveChatMessage(username: username.isEmpty ? "TikTok-Gast" : username, text: text, moderator: moderator, subscriber: subscriber)
    }

    private func firstString(_ json: [String: Any], keys: [String]) -> String {
        for key in keys {
            if let value = json[key] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return value }
        }
        return ""
    }

    private func handle(_ message: LiveChatMessage) {
        messages.insert(message, at: 0)
        if messages.count > 30 { messages.removeLast(messages.count - 30) }
        if shouldSpeak(message) { speak(message) }
    }

    private func shouldSpeak(_ message: LiveChatMessage) -> Bool {
        guard readAloud else { return false }
        switch filterMode {
        case 1: return message.moderator
        case 2: return message.subscriber
        case 3: return message.moderator || message.subscriber
        case 4:
            let users = allowedUsers.split(separator: ",").map {
                $0.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "@", with: "").lowercased()
            }
            return users.contains(message.username.replacingOccurrences(of: "@", with: "").lowercased())
        default: return true
        }
    }

    private func speak(_ message: LiveChatMessage) {
        let utterance = AVSpeechUtterance(string: "\(message.username) sagt: \(message.text)")
        utterance.rate = speechRate
        utterance.pitchMultiplier = pitch
        utterance.volume = volume
        if !selectedVoiceIdentifier.isEmpty {
            utterance.voice = AVSpeechSynthesisVoice(identifier: selectedVoiceIdentifier)
        }
        synthesizer.speak(utterance)
    }
}
