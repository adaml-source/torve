import Foundation

final class IOSDownloadService: NSObject {
    static let shared = IOSDownloadService()

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: "com.torve.downloads")
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// Active task tracking: downloadId -> URLSessionTask identifier
    private var taskMap: [String: Int] = [:]
    private let lock = NSLock()

    /// Progress callback: downloadId -> (bytesWritten, totalBytes)
    var onProgress: ((String, Int64, Int64) -> Void)?

    /// Completion callback: downloadId -> localFileURL
    var onComplete: ((String, URL) -> Void)?

    /// Error callback: downloadId -> Error
    var onError: ((String, Error) -> Void)?

    private override init() {
        super.init()
    }

    func startDownload(downloadId: String, url: URL, filename: String) {
        let task = session.downloadTask(with: url)
        task.taskDescription = downloadId
        lock.lock()
        taskMap[downloadId] = task.taskIdentifier
        lock.unlock()
        task.resume()
    }

    func cancelDownload(downloadId: String) {
        lock.lock()
        let taskId = taskMap.removeValue(forKey: downloadId)
        lock.unlock()
        guard let taskId = taskId else { return }
        session.getTasksWithCompletionHandler { _, _, downloads in
            downloads.first { $0.taskIdentifier == taskId }?.cancel()
        }
    }

    func cancelAllDownloads() {
        session.getTasksWithCompletionHandler { _, _, downloads in
            downloads.forEach { $0.cancel() }
        }
        lock.lock()
        taskMap.removeAll()
        lock.unlock()
    }
}

// MARK: - URLSessionDownloadDelegate

extension IOSDownloadService: URLSessionDownloadDelegate {
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let downloadId = downloadTask.taskDescription else { return }

        // Move file to Documents
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let downloadsDir = documentsURL.appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: downloadsDir, withIntermediateDirectories: true)

        let filename = downloadTask.response?.suggestedFilename ?? "\(downloadId).mp4"
        let destURL = downloadsDir.appendingPathComponent(filename)

        // Remove existing file if present
        try? FileManager.default.removeItem(at: destURL)

        do {
            try FileManager.default.moveItem(at: location, to: destURL)
            lock.lock()
            taskMap.removeValue(forKey: downloadId)
            lock.unlock()
            DispatchQueue.main.async {
                self.onComplete?(downloadId, destURL)
            }
        } catch {
            DispatchQueue.main.async {
                self.onError?(downloadId, error)
            }
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let downloadId = downloadTask.taskDescription else { return }
        DispatchQueue.main.async {
            self.onProgress?(downloadId, totalBytesWritten, totalBytesExpectedToWrite)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let downloadId = task.taskDescription, let error = error else { return }
        lock.lock()
        taskMap.removeValue(forKey: downloadId)
        lock.unlock()
        DispatchQueue.main.async {
            self.onError?(downloadId, error)
        }
    }

    /// Called when background session events are delivered after the app relaunches.
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        // Notify the app delegate that background events are complete.
        // In the AppDelegate, call the stored completion handler here.
    }
}
