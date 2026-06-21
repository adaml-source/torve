import SwiftUI
import Speech
import AVFoundation

/// Voice search button using iOS Speech framework.
/// Requests microphone + speech recognition permissions, streams results
/// to the onResult callback.
struct VoiceSearchButton: View {
    let onResult: (String) -> Void

    @StateObject private var recognizer = SpeechRecognizer()

    var body: some View {
        Button {
            if recognizer.isListening {
                recognizer.stopListening()
            } else {
                recognizer.startListening { transcript in
                    onResult(transcript)
                }
            }
        } label: {
            Image(systemName: recognizer.isListening ? "mic.fill" : "mic")
                .foregroundColor(recognizer.isListening ? SVColor.error : SVColor.amber)
                .font(.body)
        }
        .accessibilityLabel(recognizer.isListening ? "Stop voice search" : "Voice search")
        .alert("Speech Recognition Unavailable",
               isPresented: $recognizer.showPermissionAlert) {
            Button("Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Please enable speech recognition and microphone access in Settings to use voice search.")
        }
    }
}

// MARK: - Speech Recognizer

private final class SpeechRecognizer: ObservableObject {
    @Published var isListening = false
    @Published var showPermissionAlert = false

    private let speechRecognizer = SFSpeechRecognizer(locale: Locale.current)
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private let audioEngine = AVAudioEngine()
    private var onTranscript: ((String) -> Void)?
    private var silenceTimer: Timer?

    func startListening(onResult: @escaping (String) -> Void) {
        self.onTranscript = onResult

        // Check authorization
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            DispatchQueue.main.async {
                guard let self else { return }
                switch status {
                case .authorized:
                    self.requestMicrophoneAndStart()
                case .denied, .restricted:
                    self.showPermissionAlert = true
                case .notDetermined:
                    break
                @unknown default:
                    break
                }
            }
        }
    }

    func stopListening() {
        silenceTimer?.invalidate()
        silenceTimer = nil
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        recognitionRequest?.endAudio()
        recognitionRequest = nil
        recognitionTask?.cancel()
        recognitionTask = nil

        DispatchQueue.main.async { [weak self] in
            self?.isListening = false
        }
    }

    private func requestMicrophoneAndStart() {
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            DispatchQueue.main.async {
                guard let self else { return }
                if granted {
                    self.beginRecognition()
                } else {
                    self.showPermissionAlert = true
                }
            }
        }
    }

    private func beginRecognition() {
        guard let speechRecognizer, speechRecognizer.isAvailable else {
            showPermissionAlert = true
            return
        }

        // Stop any in-progress recognition
        recognitionTask?.cancel()
        recognitionTask = nil

        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.record, mode: .measurement, options: .duckOthers)
            try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            print("[VoiceSearch] Audio session error: \(error)")
            return
        }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true

        if #available(iOS 16, *) {
            request.addsPunctuation = false
        }

        recognitionRequest = request

        recognitionTask = speechRecognizer.recognitionTask(with: request) { [weak self] result, error in
            guard let self else { return }

            if let result {
                let transcript = result.bestTranscription.formattedString.trimmingCharacters(in: .whitespacesAndNewlines)

                // Reset silence timer on each partial result
                self.silenceTimer?.invalidate()
                self.silenceTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: false) { [weak self] _ in
                    // Auto-stop after 1.5s of silence
                    DispatchQueue.main.async {
                        self?.stopListening()
                    }
                }

                if result.isFinal && !transcript.isEmpty {
                    DispatchQueue.main.async {
                        self.onTranscript?(transcript)
                        self.stopListening()
                    }
                }
            }

            if error != nil {
                DispatchQueue.main.async {
                    self.stopListening()
                }
            }
        }

        // Configure audio input
        let inputNode = audioEngine.inputNode
        let recordingFormat = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { buffer, _ in
            request.append(buffer)
        }

        do {
            audioEngine.prepare()
            try audioEngine.start()
            DispatchQueue.main.async { [weak self] in
                self?.isListening = true
            }
        } catch {
            print("[VoiceSearch] Audio engine start error: \(error)")
            stopListening()
        }
    }
}
