import Foundation
import AVFoundation
import AVKit
import MediaPlayer

final class AVPlayerEngine: NSObject, ObservableObject {
    @Published var isPlaying = false
    @Published var currentTime: Double = 0
    @Published var duration: Double = 0
    @Published var isBuffering = false
    @Published var audioTracks: [AVMediaSelectionOption] = []
    @Published var subtitleTracks: [AVMediaSelectionOption] = []

    let player = AVPlayer()

    private var timeObserver: Any?
    private var statusObserver: NSKeyValueObservation?
    private var rateObserver: NSKeyValueObservation?

    override init() {
        super.init()
        setupTimeObserver()
        setupNowPlaying()
        setupRemoteCommands()

        rateObserver = player.observe(\.rate) { [weak self] player, _ in
            DispatchQueue.main.async { self?.isPlaying = player.rate > 0 }
        }
    }

    deinit {
        if let observer = timeObserver {
            player.removeTimeObserver(observer)
        }
        statusObserver?.invalidate()
        rateObserver?.invalidate()
    }

    func load(url: URL, title: String, startPosition: Double = 0) {
        let asset = AVURLAsset(url: url)
        let item = AVPlayerItem(asset: asset)

        statusObserver?.invalidate()
        statusObserver = item.observe(\.status) { [weak self] item, _ in
            guard let self else { return }
            DispatchQueue.main.async {
                if item.status == .readyToPlay {
                    self.duration = item.duration.seconds.isFinite ? item.duration.seconds : 0
                    self.loadTrackInfo(item: item)
                    if startPosition > 0 {
                        self.seek(to: startPosition)
                    }
                }
            }
        }

        player.replaceCurrentItem(with: item)
        player.allowsExternalPlayback = true // AirPlay
    }

    func play() { player.play() }
    func pause() { player.pause() }
    func togglePlayPause() { isPlaying ? pause() : play() }

    func seek(to seconds: Double) {
        let time = CMTime(seconds: seconds, preferredTimescale: 600)
        player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func seekRelative(_ offset: Double) {
        let target = currentTime + offset
        seek(to: max(0, min(target, duration)))
    }

    func selectAudioTrack(_ option: AVMediaSelectionOption) {
        guard let item = player.currentItem,
              let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: .audible) else { return }
        item.select(option, in: group)
    }

    func selectSubtitleTrack(_ option: AVMediaSelectionOption?) {
        guard let item = player.currentItem,
              let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: .legible) else { return }
        item.select(option, in: group)
    }

    // MARK: - Private

    private func setupTimeObserver() {
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            self?.currentTime = time.seconds.isFinite ? time.seconds : 0
        }
    }

    private func loadTrackInfo(item: AVPlayerItem) {
        Task {
            if let audioGroup = try? await item.asset.loadMediaSelectionGroup(for: .audible) {
                await MainActor.run { self.audioTracks = audioGroup.options }
            }
            if let subtitleGroup = try? await item.asset.loadMediaSelectionGroup(for: .legible) {
                await MainActor.run { self.subtitleTracks = subtitleGroup.options }
            }
        }
    }

    private func setupNowPlaying() {
        let audioSession = AVAudioSession.sharedInstance()
        try? audioSession.setCategory(.playback, mode: .moviePlayback)
        try? audioSession.setActive(true)
    }

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            self?.play(); return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.pause(); return .success
        }
        center.skipForwardCommand.preferredIntervals = [15]
        center.skipForwardCommand.addTarget { [weak self] _ in
            self?.seekRelative(15); return .success
        }
        center.skipBackwardCommand.preferredIntervals = [15]
        center.skipBackwardCommand.addTarget { [weak self] _ in
            self?.seekRelative(-15); return .success
        }
    }
}
