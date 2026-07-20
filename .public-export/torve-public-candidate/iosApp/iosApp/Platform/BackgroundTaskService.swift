import BackgroundTasks
import Foundation

/// Registers and manages iOS background tasks for periodic Trakt sync
/// and episode notification checks. iOS counterpart of Android's
/// TraktSyncWorker and EpisodeNotificationWorker.
///
/// Note: Background task identifiers must also be declared in
/// Info.plist under BGTaskSchedulerPermittedIdentifiers.
final class BackgroundTaskService {

    static let traktSyncId = "com.torve.traktSync"
    static let episodeCheckId = "com.torve.episodeCheck"

    // MARK: - Registration

    /// Registers both background task handlers with the system.
    /// Must be called before the app finishes launching (e.g., in
    /// `application(_:didFinishLaunchingWithOptions:)` or the App init).
    static func registerTasks() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: traktSyncId,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handleTraktSync(task: refreshTask)
        }

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: episodeCheckId,
            using: nil
        ) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handleEpisodeCheck(task: processingTask)
        }
    }

    // MARK: - Scheduling

    /// Schedules a background Trakt sync. The system will run this at its
    /// discretion, no sooner than 1 hour from now.
    static func scheduleTraktSync() {
        let request = BGAppRefreshTaskRequest(identifier: traktSyncId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 3600) // 1 hour
        do {
            try BGTaskScheduler.shared.submit(request)
            print("[BackgroundTasks] Trakt sync scheduled.")
        } catch {
            print("[BackgroundTasks] Failed to schedule Trakt sync: \(error.localizedDescription)")
        }
    }

    /// Schedules a background episode check. The system will run this at its
    /// discretion, no sooner than 6 hours from now. Requires network.
    static func scheduleEpisodeCheck() {
        let request = BGProcessingTaskRequest(identifier: episodeCheckId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 3600) // 6 hours
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        do {
            try BGTaskScheduler.shared.submit(request)
            print("[BackgroundTasks] Episode check scheduled.")
        } catch {
            print("[BackgroundTasks] Failed to schedule episode check: \(error.localizedDescription)")
        }
    }

    // MARK: - Handlers

    /// Performs a Trakt sync in the background: watchlist, history,
    /// progress, and ratings.
    private static func handleTraktSync(task: BGAppRefreshTask) {
        // Re-schedule so it keeps running periodically
        scheduleTraktSync()

        let syncTask = Task {
            do {
                // Access KMP repositories through Koin
                // The iOS app should have a KoinHelper that provides these
                try await performTraktSync()
                task.setTaskCompleted(success: true)
            } catch {
                print("[BackgroundTasks] Trakt sync failed: \(error.localizedDescription)")
                task.setTaskCompleted(success: false)
            }
        }

        // Handle expiration — cancel our work if the system needs resources
        task.expirationHandler = {
            syncTask.cancel()
            task.setTaskCompleted(success: false)
        }
    }

    /// Checks for upcoming episodes and schedules local notifications.
    private static func handleEpisodeCheck(task: BGProcessingTask) {
        // Re-schedule so it keeps running periodically
        scheduleEpisodeCheck()

        let checkTask = Task {
            do {
                try await performEpisodeCheck()
                task.setTaskCompleted(success: true)
            } catch {
                print("[BackgroundTasks] Episode check failed: \(error.localizedDescription)")
                task.setTaskCompleted(success: false)
            }
        }

        task.expirationHandler = {
            checkTask.cancel()
            task.setTaskCompleted(success: false)
        }
    }

    // MARK: - Sync Logic

    /// Runs the actual Trakt sync operations. Mirrors TraktSyncWorker.doWork().
    private static func performTraktSync() async throws {
        // TODO: Wire up KMP Koin-provided repositories once KoinHelper exposes them.
        // Example:
        //   let watchlistRepo = KoinHelper.shared.get(WatchlistRepository.self)
        //   let historyRepo = KoinHelper.shared.get(WatchHistoryRepository.self)
        //   let progressRepo = KoinHelper.shared.get(WatchProgressRepository.self)
        //   let traktSyncRepo = KoinHelper.shared.get(TraktSyncRepository.self)
        //
        //   try await watchlistRepo.syncFromTrakt()
        //   try await historyRepo.syncFromTrakt()
        //   try await progressRepo.syncFromTrakt()
        //   try await traktSyncRepo.syncRatingsFromTrakt()
        //   try await traktSyncRepo.flushPendingWrites()

        print("[BackgroundTasks] Trakt sync completed.")
    }

    /// Fetches the Trakt calendar and schedules notifications for
    /// episodes airing in the next 24 hours.
    private static func performEpisodeCheck() async throws {
        // TODO: Wire up KMP Trakt calendar API once available.
        // Example:
        //   let traktApi = KoinHelper.shared.get(TraktAuthorizedApi.self)
        //   let calendar = try await traktApi.getCalendar()
        //   let episodes = calendar.map { entry in
        //       (showTitle: entry.showTitle,
        //        season: Int(entry.season),
        //        episode: Int(entry.episode),
        //        episodeTitle: entry.episodeTitle,
        //        airDate: Date(timeIntervalSince1970: entry.airDateEpoch))
        //   }
        //   EpisodeNotificationService.shared.checkAndScheduleUpcoming(episodes: episodes)

        print("[BackgroundTasks] Episode check completed.")
    }

    // MARK: - Cancel

    /// Cancels all pending background tasks.
    static func cancelAll() {
        BGTaskScheduler.shared.cancelAllTaskRequests()
        print("[BackgroundTasks] All background tasks cancelled.")
    }
}
