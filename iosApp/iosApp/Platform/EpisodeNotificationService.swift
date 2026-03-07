import Foundation
import UserNotifications

/// Manages local notifications for upcoming TV show episodes.
/// iOS counterpart of Android's EpisodeNotificationWorker.
final class EpisodeNotificationService {

    static let shared = EpisodeNotificationService()

    private static let categoryId = "EPISODE_REMINDER"
    private static let watchNowActionId = "WATCH_NOW"
    private static let notificationPrefix = "episode_"

    private init() {}

    // MARK: - Permission

    /// Requests notification authorization from the user. Call once at app launch or
    /// when the user enables episode notifications in Settings.
    func requestPermission(completion: ((Bool) -> Void)? = nil) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if let error = error {
                print("[EpisodeNotifications] Permission error: \(error.localizedDescription)")
            }
            completion?(granted)
        }
    }

    /// Registers the notification category with a "Watch Now" action button.
    func registerCategory() {
        let watchAction = UNNotificationAction(
            identifier: Self.watchNowActionId,
            title: "Watch Now",
            options: .foreground
        )
        let category = UNNotificationCategory(
            identifier: Self.categoryId,
            actions: [watchAction],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    // MARK: - Schedule

    /// Schedules a local notification for a single upcoming episode.
    ///
    /// - Parameters:
    ///   - showTitle: The name of the TV show.
    ///   - seasonNumber: Season number (1-based).
    ///   - episodeNumber: Episode number (1-based).
    ///   - episodeTitle: Optional episode title for richer notification text.
    ///   - airDate: The date/time the episode airs.
    func scheduleEpisodeNotification(
        showTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String? = nil,
        airDate: Date
    ) {
        // Don't schedule for episodes that already aired
        guard airDate > Date() else { return }

        let content = UNMutableNotificationContent()
        content.title = "New Episode Available"
        let epCode = String(format: "S%02dE%02d", seasonNumber, episodeNumber)
        if let epTitle = episodeTitle {
            content.body = "\(showTitle) \(epCode) — \(epTitle)"
        } else {
            content.body = "\(showTitle) \(epCode) is now available."
        }
        content.sound = .default
        content.categoryIdentifier = Self.categoryId

        // Trigger at the air date/time
        let components = Calendar.current.dateComponents(
            [.year, .month, .day, .hour, .minute],
            from: airDate
        )
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)

        let identifier = "\(Self.notificationPrefix)\(showTitle.hashValue)_\(seasonNumber)_\(episodeNumber)"
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("[EpisodeNotifications] Failed to schedule: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Cancel

    /// Removes all pending episode notifications.
    func cancelAllEpisodeNotifications() {
        let center = UNUserNotificationCenter.current()
        center.getPendingNotificationRequests { requests in
            let ids = requests
                .filter { $0.identifier.hasPrefix(Self.notificationPrefix) }
                .map(\.identifier)
            center.removePendingNotificationRequests(withIdentifiers: ids)
        }
    }

    // MARK: - Check & Schedule Upcoming

    /// Checks for episodes airing within the next 24 hours and schedules
    /// notifications for each. Intended to be called from a background task
    /// or when the app enters the foreground.
    ///
    /// - Parameter episodes: Array of tuples describing upcoming episodes.
    ///   Each element provides (showTitle, season, episode, episodeTitle, airDate).
    func checkAndScheduleUpcoming(
        episodes: [(showTitle: String, season: Int, episode: Int, episodeTitle: String?, airDate: Date)]
    ) {
        let now = Date()
        let cutoff = now.addingTimeInterval(24 * 3600) // 24 hours from now

        // Cancel stale notifications first
        cancelAllEpisodeNotifications()

        let upcoming = episodes.filter { $0.airDate > now && $0.airDate <= cutoff }
        for ep in upcoming {
            scheduleEpisodeNotification(
                showTitle: ep.showTitle,
                seasonNumber: ep.season,
                episodeNumber: ep.episode,
                episodeTitle: ep.episodeTitle,
                airDate: ep.airDate
            )
        }

        if !upcoming.isEmpty {
            print("[EpisodeNotifications] Scheduled \(upcoming.count) upcoming episode notification(s).")
        }
    }
}
