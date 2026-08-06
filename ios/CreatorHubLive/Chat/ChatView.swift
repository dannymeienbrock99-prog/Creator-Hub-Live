import SwiftUI

struct ChatView: View {
    @ObservedObject var viewModel: LiveChatViewModel

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                HStack {
                    Text(viewModel.status)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(viewModel.status == "Verbunden" ? "Trennen" : "Verbinden") {
                        viewModel.toggleConnection()
                    }
                    .buttonStyle(.borderedProminent)
                }

                List(viewModel.messages) { message in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            if message.moderator { Text("MOD").font(.caption2).bold() }
                            if message.subscriber { Text("SUB").font(.caption2).bold() }
                            Text(message.username).bold()
                        }
                        Text(message.text)
                    }
                }
                .listStyle(.plain)

                Button("Testnachricht") {
                    viewModel.addTestMessage()
                }
                .buttonStyle(.bordered)
            }
            .padding()
            .navigationTitle("Live-Chat")
        }
    }
}
