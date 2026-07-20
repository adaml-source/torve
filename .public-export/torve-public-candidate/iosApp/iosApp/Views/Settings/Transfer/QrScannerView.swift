import AVFoundation
import SwiftUI
import UIKit

/// AVFoundation-backed QR scanner exposed as a SwiftUI `View`.
///
/// Wraps `AVCaptureSession` + `AVCaptureMetadataOutput` (QR-only). Calls
/// [onQrDetected] once on the first successful scan. Renders nothing
/// useful when the camera permission is denied, when no rear camera
/// exists, or in the simulator — in those cases [onUnavailable] fires
/// so the calling SwiftUI surface can keep the manual-paste field as
/// the primary action.
///
/// Permission flow is handled inside this view: on first appear it
/// requests the camera permission and reports `.permissionDenied`
/// upstream if the user refuses. Manual paste is *always* still
/// reachable.
struct QrScannerView: UIViewControllerRepresentable {

    enum Unavailable: Equatable {
        case noCamera
        case permissionDenied
    }

    let onQrDetected: (String) -> Void
    let onUnavailable: (Unavailable) -> Void

    func makeUIViewController(context: Context) -> QrScannerViewController {
        let vc = QrScannerViewController()
        vc.onQrDetected = onQrDetected
        vc.onUnavailable = onUnavailable
        return vc
    }

    func updateUIViewController(_ uiViewController: QrScannerViewController, context: Context) {}
}

/// Whether this device has any rear camera worth scanning a QR with.
/// Mirrors the Android `PackageManager.FEATURE_CAMERA_ANY` check —
/// returns false on simulators and on devices without a rear camera.
func deviceHasAnyCamera() -> Bool {
    return AVCaptureDevice.default(for: .video) != nil
}

/// Wrapper UIViewController that owns the AVCaptureSession and metadata
/// pipeline. Kept off the SwiftUI view tree on purpose so we can
/// stop/start the session via lifecycle callbacks.
final class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    var onQrDetected: ((String) -> Void)?
    var onUnavailable: ((QrScannerView.Unavailable) -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var alreadyDelivered = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        requestPermissionAndConfigure()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if session.inputs.count > 0 && !session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.session.startRunning()
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning {
            session.stopRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }

    private func requestPermissionAndConfigure() {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .authorized:
            configureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureSession()
                    } else {
                        self?.onUnavailable?(.permissionDenied)
                    }
                }
            }
        case .denied, .restricted:
            onUnavailable?(.permissionDenied)
        @unknown default:
            onUnavailable?(.permissionDenied)
        }
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video) else {
            onUnavailable?(.noCamera)
            return
        }
        guard let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            onUnavailable?(.noCamera)
            return
        }
        session.beginConfiguration()
        session.addInput(input)

        let metadataOutput = AVCaptureMetadataOutput()
        guard session.canAddOutput(metadataOutput) else {
            session.commitConfiguration()
            onUnavailable?(.noCamera)
            return
        }
        session.addOutput(metadataOutput)
        metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        metadataOutput.metadataObjectTypes = [.qr]
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.layer.bounds
        view.layer.addSublayer(layer)
        previewLayer = layer

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !alreadyDelivered else { return }
        guard let first = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let payload = first.stringValue else { return }
        alreadyDelivered = true
        DispatchQueue.main.async {
            self.onQrDetected?(payload)
        }
    }
}
