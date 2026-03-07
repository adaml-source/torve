import Foundation
import AVFoundation
import Combine

/// 10-band parametric equalizer using AVAudioEngine + AVAudioUnitEQ.
///
/// Standard octave frequencies: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz.
///
/// NOTE: AVPlayer uses its own rendering pipeline for HLS/DASH streams, so
/// AVAudioEngine cannot tap into AVPlayer's audio output directly. To
/// integrate fully you would need an MTAudioProcessingTap on the
/// AVPlayerItem's audioMix — that is left as a TODO. This class provides
/// a complete, self-contained EQ engine that works with AVAudioPlayerNode
/// (local files) and exposes a clean interface for future AVPlayer integration.
final class iOSAudioEqualizer: ObservableObject {

    // MARK: - Constants

    static let bandFrequencies: [Float] = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
    static let bandCount = 10
    static let minGain: Float = -12.0
    static let maxGain: Float = 12.0

    // MARK: - Published state

    @Published var bandGains: [Float] = Array(repeating: 0, count: 10)
    @Published var selectedPreset: EqPreset = .flat
    @Published var bassBoost: Float = 0       // 0 … 1
    @Published var loudnessGain: Float = 0    // 0 … 1
    @Published var isEnabled: Bool = false

    // MARK: - Audio engine

    private let audioEngine = AVAudioEngine()
    private let eqNode = AVAudioUnitEQ(numberOfBands: 10)
    private let playerNode = AVAudioPlayerNode()

    // MARK: - Init

    init() {
        configureBands()
    }

    // MARK: - Band configuration

    private func configureBands() {
        for i in 0..<Self.bandCount {
            let band = eqNode.bands[i]
            band.filterType = .parametric
            band.frequency = Self.bandFrequencies[i]
            band.bandwidth = 1.0   // 1 octave
            band.gain = 0
            band.bypass = false
        }
    }

    // MARK: - Public API

    func applyPreset(_ preset: EqPreset) {
        selectedPreset = preset
        let gains = preset.gains
        for i in 0..<Self.bandCount {
            bandGains[i] = gains[i]
        }
        bassBoost = preset.bassBoostValue
        loudnessGain = preset.loudnessValue
        pushGainsToEngine()
    }

    func setBandGain(band: Int, gain: Float) {
        guard band >= 0 && band < Self.bandCount else { return }
        bandGains[band] = gain.clamped(to: Self.minGain...Self.maxGain)
        selectedPreset = .custom
        pushGainsToEngine()
    }

    func setBassBoost(_ value: Float) {
        bassBoost = value.clamped(to: 0...1)
        pushGainsToEngine()
    }

    func setLoudnessGain(_ value: Float) {
        loudnessGain = value.clamped(to: 0...1)
        pushGainsToEngine()
    }

    func setEnabled(_ enabled: Bool) {
        isEnabled = enabled
        eqNode.bypass = !enabled
    }

    func reset() {
        applyPreset(.flat)
        bassBoost = 0
        loudnessGain = 0
        isEnabled = false
        eqNode.bypass = true
    }

    // MARK: - Engine management

    /// Attach and connect the EQ node into AVAudioEngine.
    /// Call this when setting up local file playback via AVAudioPlayerNode.
    func setupEngine() {
        audioEngine.attach(playerNode)
        audioEngine.attach(eqNode)

        let format = audioEngine.mainMixerNode.outputFormat(forBus: 0)
        audioEngine.connect(playerNode, to: eqNode, format: format)
        audioEngine.connect(eqNode, to: audioEngine.mainMixerNode, format: format)

        eqNode.bypass = !isEnabled
    }

    func startEngine() {
        guard !audioEngine.isRunning else { return }
        do {
            try audioEngine.start()
        } catch {
            print("[iOSAudioEqualizer] Failed to start audio engine: \(error)")
        }
    }

    func stopEngine() {
        audioEngine.stop()
    }

    // TODO: AVPlayer integration via MTAudioProcessingTap
    // To integrate with AVPlayer for HLS streams:
    // 1. Create an MTAudioProcessingTap on the AVPlayerItem's audio track
    // 2. In the tap's process callback, apply the biquad EQ filters
    // 3. Attach the tap via AVMutableAudioMix on the AVPlayerItem
    // This requires working with AudioToolbox C APIs.

    // MARK: - Private

    private func pushGainsToEngine() {
        let bassBoostDb = bassBoost * 6.0   // up to +6 dB on low bands
        let loudnessDb = loudnessGain * 3.0 // up to +3 dB flat boost

        for i in 0..<Self.bandCount {
            var gain = bandGains[i] + loudnessDb

            // Bass boost applies to bands 0-3 (31–250 Hz)
            if i <= 3 {
                let factor = Float(4 - i) / 4.0  // strongest on lowest band
                gain += bassBoostDb * factor
            }

            eqNode.bands[i].gain = gain.clamped(to: -15...15)
        }
    }
}

// MARK: - EQ Presets

enum EqPreset: String, CaseIterable, Identifiable {
    case flat = "Flat"
    case cinematic = "Cinematic"
    case dialogue = "Dialogue Boost"
    case rock = "Rock"
    case pop = "Pop"
    case jazz = "Jazz"
    case classical = "Classical"
    case bass = "Bass Boost"
    case treble = "Treble Boost"
    case voice = "Voice"
    case loudness = "Loudness"
    case headphones = "Headphones"
    case lateNight = "Late Night"
    case custom = "Custom"

    var id: String { rawValue }

    var gains: [Float] {
        switch self {
        case .flat:       return [ 0,  0,  0,  0,  0,  0,  0,  0,  0,  0]
        case .cinematic:  return [ 4,  3,  1, -2,  0,  1,  2,  3,  2,  0]
        case .dialogue:   return [-2, -1,  0, -3,  1,  4,  3,  2,  1,  0]
        case .rock:       return [ 5,  4, -5, -8, -3,  4,  8, 11, 11, 11]
        case .pop:        return [-1,  4,  7,  8,  5,  0, -2, -2, -1, -1]
        case .jazz:       return [ 4,  3,  1,  2, -1, -1,  0,  1,  3,  4]
        case .classical:  return [ 5,  3,  0,  0,  0,  0,  0, -3, -3, -5]
        case .bass:       return [10,  8,  5,  1,  0,  0,  0,  0,  0,  0]
        case .treble:     return [ 0,  0,  0,  0,  0,  1,  3,  6,  9, 11]
        case .voice:      return [-3, -1,  0,  3,  6,  6,  5,  3,  0, -2]
        case .loudness:   return [ 6,  4,  0,  0, -2,  0, -1, -5,  5,  2]
        case .headphones: return [ 4,  3,  0, -3, -2,  1,  4,  6,  8,  9]
        case .lateNight:  return [ 3,  3,  2,  0, -2, -3, -2,  0,  2,  3]
        case .custom:     return [ 0,  0,  0,  0,  0,  0,  0,  0,  0,  0]
        }
    }

    var bassBoostValue: Float {
        switch self {
        case .bass:      return 0.6
        default:         return 0
        }
    }

    var loudnessValue: Float {
        switch self {
        case .loudness:  return 0.5
        case .lateNight: return 0.3
        default:         return 0
        }
    }
}

// MARK: - Float clamping helper

private extension Float {
    func clamped(to range: ClosedRange<Float>) -> Float {
        return Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}
