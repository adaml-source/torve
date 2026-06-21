import UIKit

/// Launches external video players via iOS URL schemes.
/// iOS equivalent of Android's ExternalPlayerLauncher.
enum ExternalPlayerLauncher {

    /// Supported external players with their URL schemes.
    enum ExternalPlayer: String, CaseIterable, Identifiable {
        case vlc = "VLC"
        case infuse = "Infuse"
        case outplayer = "Outplayer"
        case nplayer = "nPlayer"

        var id: String { rawValue }

        /// The URL scheme used to check if the app is installed.
        var canOpenScheme: String {
            switch self {
            case .vlc:       return "vlc://"
            case .infuse:    return "infuse://"
            case .outplayer: return "outplayer://"
            case .nplayer:   return "nplayer-http://"
            }
        }

        /// Build the playback URL for a given stream URL.
        func playbackURL(for streamURL: String) -> URL? {
            let encoded = streamURL.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? streamURL
            switch self {
            case .vlc:
                // VLC accepts vlc://<url> or vlc-x-callback://x-callback-url/stream?url=<encoded>
                return URL(string: "vlc://\(streamURL)")
            case .infuse:
                return URL(string: "infuse://x-callback-url/play?url=\(encoded)")
            case .outplayer:
                return URL(string: "outplayer://\(streamURL)")
            case .nplayer:
                // nPlayer uses nplayer-<scheme>://<rest>
                if streamURL.hasPrefix("https://") {
                    let rest = String(streamURL.dropFirst("https://".count))
                    return URL(string: "nplayer-https://\(rest)")
                } else if streamURL.hasPrefix("http://") {
                    let rest = String(streamURL.dropFirst("http://".count))
                    return URL(string: "nplayer-http://\(rest)")
                }
                return URL(string: "nplayer-http://\(streamURL)")
            }
        }
    }

    // MARK: - Public API

    /// Returns a list of external players that are installed on the device.
    /// Note: The app's Info.plist must include LSApplicationQueriesSchemes
    /// for each scheme (vlc, infuse, outplayer, nplayer-http) for
    /// canOpenURL to return true.
    static func getInstalledPlayers() -> [ExternalPlayer] {
        return ExternalPlayer.allCases.filter { player in
            guard let url = URL(string: player.canOpenScheme) else { return false }
            return UIApplication.shared.canOpenURL(url)
        }
    }

    /// Play a stream URL in the specified external player.
    /// Returns true if the URL was opened, false otherwise.
    @discardableResult
    static func play(url: String, in player: ExternalPlayer) -> Bool {
        guard let playbackURL = player.playbackURL(for: url) else { return false }
        guard UIApplication.shared.canOpenURL(playbackURL) else { return false }
        UIApplication.shared.open(playbackURL, options: [:], completionHandler: nil)
        return true
    }

    /// Copy the stream URL to the clipboard.
    static func copyURL(_ url: String) {
        UIPasteboard.general.string = url
    }

    /// Share the stream URL via the system share sheet.
    /// Call from a SwiftUI context by presenting a UIActivityViewController.
    static func makeShareActivity(url: String, title: String) -> UIActivityViewController {
        let items: [Any] = [title, URL(string: url) as Any].compactMap { $0 }
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        return vc
    }
}
