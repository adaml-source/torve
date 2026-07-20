import Foundation
import AVFoundation
import VideoToolbox

/// Detects the device's hardware video codec capabilities.
/// iOS counterpart of Android's DeviceCodecProbe, using VideoToolbox
/// for accurate hardware decoder detection.
///
/// Unlike Android, iOS handles most codec negotiation internally,
/// but this probe lets StreamSelector pre-filter streams the device
/// cannot play (e.g., AV1 on older chips).
final class DeviceCodecProbe {

    /// Device capability tier, used to make quick decisions about
    /// stream quality without checking individual codec flags.
    enum DeviceTier: String {
        /// Older devices — H.264 only, no HDR
        case basic
        /// Mid-range — H.264 + HEVC, no AV1
        case standard
        /// Flagship — HEVC + AV1 + HDR
        case highEnd
    }

    /// Snapshot of codec capabilities for the current device.
    struct CodecCaps {
        let supportsHEVC: Bool
        let supportsHDR: Bool
        let supportsAV1: Bool
        let deviceTier: DeviceTier
    }

    // MARK: - Singleton

    static let shared = DeviceCodecProbe()
    private(set) lazy var caps: CodecCaps = probe()

    private init() {}

    // MARK: - Probe

    /// Queries VideoToolbox for hardware decoder availability and
    /// uses device model heuristics for HDR support.
    private func probe() -> CodecCaps {
        let hevc = checkHardwareDecode(codecType: kCMVideoCodecType_HEVC)
        let hdr = checkHDRSupport()
        let av1 = checkAV1Support()

        let tier: DeviceTier
        switch (hevc, av1, hdr) {
        case (true, true, _):
            tier = .highEnd
        case (true, false, _):
            tier = .standard
        default:
            tier = .basic
        }

        let result = CodecCaps(
            supportsHEVC: hevc,
            supportsHDR: hdr,
            supportsAV1: av1,
            deviceTier: tier
        )

        print("[DeviceCodecProbe] Caps: HEVC=\(hevc), HDR=\(hdr), AV1=\(av1), tier=\(tier.rawValue)")
        return result
    }

    // MARK: - Hardware Decode Check

    /// Uses `VTIsHardwareDecodeSupported` for accurate hardware decoder detection.
    private func checkHardwareDecode(codecType: CMVideoCodecType) -> Bool {
        return VTIsHardwareDecodeSupported(codecType)
    }

    // MARK: - HDR

    /// Checks if the device supports HDR content playback.
    /// Uses UIScreen on iOS to check for HDR display capabilities.
    private func checkHDRSupport() -> Bool {
        #if os(iOS) || os(tvOS)
        if #available(iOS 16.0, tvOS 16.0, *) {
            // Modern check: the device's main screen advertises
            // an extended-range capable display.
            if let screen = screenForHDRCheck() {
                // potentialEDRHeadroom > 1.0 indicates HDR display capability
                return screen.potentialEDRHeadroom > 1.0
            }
        }

        // Fallback: check if the display supports extended dynamic range
        if let screen = screenForHDRCheck() {
            return screen.currentEDRHeadroom > 1.0
        }
        #endif
        return false
    }

    #if os(iOS) || os(tvOS)
    /// Returns the main UIScreen (must be called carefully in a context
    /// that has access to UIKit). Returns nil if not available.
    @MainActor
    private func screenForHDRCheck() -> UIScreen? {
        // Use the key window's screen, falling back to UIScreen.main
        // (deprecated but widely available)
        return UIScreen.main
    }
    #endif

    // MARK: - AV1

    /// AV1 hardware decode is supported on A15 Bionic and later
    /// (iPhone 13+, iPad mini 6+, M1+ Macs). iOS 16+ required.
    private func checkAV1Support() -> Bool {
        guard #available(iOS 16.0, tvOS 16.0, *) else {
            return false
        }
        // kCMVideoCodecType_AV1 = 'av01' — available in iOS 16+
        // VTIsHardwareDecodeSupported will return true only on
        // devices with AV1 hardware decoder (A15+, M1+).
        let av1CodecType: CMVideoCodecType = 0x61763031 // 'av01'
        return VTIsHardwareDecodeSupported(av1CodecType)
    }

    // MARK: - KMP Bridge

    /// Converts the probe result into the KMP shared `DeviceCodecCaps`
    /// model so the shared StreamSelector can use it for filtering.
    func toSharedDeviceCodecCaps() -> shared.DeviceCodecCaps {
        let c = caps
        return shared.DeviceCodecCaps(
            supportsH264: true, // All iOS devices support H.264
            supportsHevc: c.supportsHEVC,
            supportsHevcMain10: c.supportsHEVC && c.supportsHDR,
            supportsVp9: true, // iOS software-decodes VP9 (via AVFoundation)
            supportsAv1: c.supportsAV1,
            maxHevcMainLevel: nil, // iOS doesn't expose granular HEVC levels
            maxHevcMain10Level: nil,
            isWeakHevcDevice: !c.supportsHEVC
        )
    }
}
