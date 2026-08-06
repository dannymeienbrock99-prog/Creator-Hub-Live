import AVFoundation
import SwiftUI

struct CameraView: View {
    @StateObject private var model = CameraViewModel()

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                CameraPreview(session: model.session)
                    .aspectRatio(9.0 / 16.0, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .overlay(alignment: .topLeading) {
                        Text(model.status)
                            .font(.caption)
                            .padding(8)
                            .background(.black.opacity(0.55), in: Capsule())
                            .padding(10)
                    }

                HStack {
                    Button(model.isRunning ? "Vorschau stoppen" : "Vorschau starten") {
                        model.togglePreview()
                    }
                    .buttonStyle(.borderedProminent)

                    Button("Kamera wechseln") {
                        model.switchCamera()
                    }
                    .buttonStyle(.bordered)
                }

                Text("Hochformat ist der Standard. Die Ausrichtung wird automatisch über den Gerätesensor angepasst.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding()
            .navigationTitle("Creator Hub Live")
            .onAppear { model.requestPermissionAndStart() }
            .onDisappear { model.stop() }
        }
    }
}

final class CameraViewModel: ObservableObject {
    let session = AVCaptureSession()
    @Published var status = "Kamera wird vorbereitet"
    @Published var isRunning = false

    private let queue = DispatchQueue(label: "creatorhub.camera")
    private var currentPosition: AVCaptureDevice.Position = .back

    func requestPermissionAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    granted ? self?.configureAndStart() : self?.status = "Kamerazugriff verweigert"
                }
            }
        default:
            status = "Kamerazugriff fehlt"
        }
    }

    func togglePreview() {
        isRunning ? stop() : configureAndStart()
    }

    func switchCamera() {
        currentPosition = currentPosition == .back ? .front : .back
        configureAndStart(reconfigure: true)
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning { self.session.stopRunning() }
            DispatchQueue.main.async {
                self.isRunning = false
                self.status = "Vorschau gestoppt"
            }
        }
    }

    private func configureAndStart(reconfigure: Bool = false) {
        queue.async { [weak self] in
            guard let self else { return }
            if reconfigure || self.session.inputs.isEmpty {
                self.session.beginConfiguration()
                self.session.sessionPreset = .hd1280x720
                self.session.inputs.forEach { self.session.removeInput($0) }

                guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: self.currentPosition),
                      let input = try? AVCaptureDeviceInput(device: device),
                      self.session.canAddInput(input) else {
                    self.session.commitConfiguration()
                    DispatchQueue.main.async { self.status = "Kamera konnte nicht geöffnet werden" }
                    return
                }
                self.session.addInput(input)
                self.session.commitConfiguration()
            }

            if !self.session.isRunning { self.session.startRunning() }
            DispatchQueue.main.async {
                self.isRunning = true
                self.status = self.currentPosition == .back ? "Rückkamera aktiv" : "Frontkamera aktiv"
            }
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.videoPreviewLayer.session = session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        uiView.videoPreviewLayer.session = session
    }
}

final class PreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}
