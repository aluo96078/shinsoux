import Foundation
import SwiftUI
import ShinsouKit
#if canImport(Translation)
import Translation
#endif

/// Apple's on-device Translation framework, offered to the shared reader as "system translation".
///
/// Translating without a view needs `TranslationSession(installedSource:target:)` (iOS 26), so the
/// bridge is registered only there. Downloading languages needs the system's own sheet, which is
/// shown through `.translationTask` on the root view: see [AppleTranslationDownloads].
enum AppleTranslation {
    static func register() {
        #if canImport(Translation)
        if #available(iOS 26.0, *) {
            IosSystemTranslationKt.registerAppleTranslation(bridge: AppleTranslationBridgeImpl())
        }
        #endif
    }
}

#if canImport(Translation)
@available(iOS 26.0, *)
private final class AppleTranslationBridgeImpl: NSObject, AppleTranslationBridge {
    /// The low-latency models (iOS 26.4) translate several times faster than the default
    /// high-fidelity ones, which take minutes for a chapter. They are separate downloads, and
    /// their translations are stored apart from the high-fidelity ones.
    static var lowLatency: Bool {
        if #available(iOS 26.4, *) { return true }
        return false
    }

    var engineName: String { Self.lowLatency ? "apple-fast" : "apple" }

    static func session(from: String, to: String) -> TranslationSession {
        let source = Locale.Language(identifier: from), target = Locale.Language(identifier: to)
        if #available(iOS 26.4, *) {
            return TranslationSession(installedSource: source, target: target, preferredStrategy: .lowLatency)
        }
        return TranslationSession(installedSource: source, target: target)
    }

    static func availability() -> LanguageAvailability {
        if #available(iOS 26.4, *) { return LanguageAvailability(preferredStrategy: .lowLatency) }
        return LanguageAvailability()
    }

    func status(from: String, to: String, completion: @escaping (KotlinInt) -> Void) {
        Task {
            let code = await Self.statusCode(from: from, to: to)
            completion(KotlinInt(int: Int32(code)))
        }
    }

    func translate(
        from: String,
        to: String,
        lines: [String],
        completion: @escaping ([String]?, String?) -> Void
    ) -> AppleTranslationCancellable {
        let task = Task {
            do {
                let session = Self.session(from: from, to: to)
                let requests = lines.enumerated().map { index, line in
                    TranslationSession.Request(sourceText: line, clientIdentifier: String(index))
                }
                let responses = try await session.translations(from: requests)
                var out = lines.map { _ in "" }
                var seen = 0
                for response in responses {
                    guard let id = response.clientIdentifier, let index = Int(id), out.indices.contains(index) else {
                        continue
                    }
                    out[index] = response.targetText
                    seen += 1
                }
                if seen != lines.count {
                    completion(nil, "The system returned \(seen) of \(lines.count) lines")
                } else {
                    completion(out, nil)
                }
            } catch {
                AppleTranslationTrace.record("translate \(from)>\(to) \(lines.count) lines failed: \(error)")
                completion(nil, Self.reason(error))
            }
        }
        return AppleTranslationTask(task)
    }

    func download(from: String, to: String, completion: @escaping (KotlinBoolean) -> Void) {
        Task { @MainActor in
            let started = Date()
            let done = await AppleTranslationDownloads.shared.request(from: from, to: to)
            var installed = false
            if done { installed = await Self.statusCode(from: from, to: to) == 0 }
            AppleTranslationTrace.record("download \(from)>\(to) prepared=\(done) installed=\(installed) seconds=\(Int(Date().timeIntervalSince(started)))")
            completion(KotlinBoolean(bool: installed))
        }
    }

    static func statusCode(from: String, to: String) async -> Int {
        let pair = "\(from)>\(to)"
        if await InstalledPairs.shared.contains(pair) { return 0 }
        let status = await availability().status(
            from: Locale.Language(identifier: from),
            to: Locale.Language(identifier: to)
        )
        let fidelity = await LanguageAvailability().status(
            from: Locale.Language(identifier: from),
            to: Locale.Language(identifier: to)
        )
        AppleTranslationTrace.record("status \(pair) lowLatency=\(status) default=\(fidelity)")
        switch status {
        case .installed:
            await InstalledPairs.shared.insert(pair)
            return 0
        case .supported:
            // The answer is not always right: languages the user downloaded (in Settings, or
            // for the other strategy) can be reported as only downloadable while the session
            // translates with them. Asking the session settles it; only "not installed" means
            // the download prompt is needed.
            if await probe(from: from, to: to) {
                await InstalledPairs.shared.insert(pair)
                return 0
            }
            return 1
        default:
            return 2
        }
    }

    /// Whether a session for the pair translates now, without downloading anything. Only the
    /// session's own "not installed" counts against it: another error is the translation's to
    /// report, not a reason to ask for a download.
    private static func probe(from: String, to: String) async -> Bool {
        do {
            let answer = try await session(from: from, to: to).translate("OK")
            AppleTranslationTrace.record("probe \(from)>\(to) ok: \(answer.targetText)")
            return true
        } catch {
            AppleTranslationTrace.record("probe \(from)>\(to) failed notInstalled=\(isNotInstalled(error)): \(error)")
            return !isNotInstalled(error)
        }
    }

    static func isNotInstalled(_ error: Error) -> Bool {
        TranslationError.notInstalled ~= error
    }

    /// The error text for [error], marked when the languages are not installed so the shared
    /// code can offer the download instead of reporting a failure.
    static func reason(_ error: Error) -> String {
        if isNotInstalled(error) {
            return "notInstalled: \(error)"
        }
        return String(describing: error)
    }
}

/// A small trace at Documents/Diagnostics/apple-translation.log: what the system answered for
/// each pair, so a wrong "download first" can be told apart from a missing download.
enum AppleTranslationTrace {
    private static let queue = DispatchQueue(label: "dev.aluo.shinsoux.apple-translation-trace")
    private static let url: URL = {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let directory = documents.appendingPathComponent("Diagnostics", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("apple-translation.log")
    }()

    static func record(_ line: String) {
        let stamp = ISO8601DateFormatter().string(from: Date())
        queue.async {
            if let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int, size > 256_000 {
                try? FileManager.default.removeItem(at: url)
            }
            let data = Data("\(stamp) \(line)\n".utf8)
            if let handle = try? FileHandle(forWritingTo: url) {
                handle.seekToEndOfFile()
                handle.write(data)
                try? handle.close()
            } else {
                try? data.write(to: url)
            }
        }
    }
}

/// Pairs found installed during this run: asked once, not before every chapter.
private actor InstalledPairs {
    static let shared = InstalledPairs()
    private var pairs = Set<String>()
    func contains(_ pair: String) -> Bool { pairs.contains(pair) }
    func insert(_ pair: String) { pairs.insert(pair) }
}

private final class AppleTranslationTask: NSObject, AppleTranslationCancellable {
    private let task: Task<Void, Never>
    init(_ task: Task<Void, Never>) { self.task = task }
    func cancel() { task.cancel() }
}
#endif

/// Shows the system's language download sheet when the shared code asks for it.
@MainActor
final class AppleTranslationDownloads: ObservableObject {
    static let shared = AppleTranslationDownloads()

    /// A `TranslationSession.Configuration` while a download is asked for; typed loosely because
    /// the app still runs on systems without the framework.
    @Published private(set) var configuration: Any?
    private var waiting: CheckedContinuation<Bool, Never>?

    func request(from: String, to: String) async -> Bool {
        #if canImport(Translation)
        guard #available(iOS 18.0, *) else { return false }
        // One sheet at a time: a second request while one is showing is refused, not queued.
        if waiting != nil { return false }
        return await withCheckedContinuation { continuation in
            waiting = continuation
            let source = Locale.Language(identifier: from), target = Locale.Language(identifier: to)
            if #available(iOS 26.4, *) {
                // The same models the bridge translates with; see AppleTranslationBridgeImpl.lowLatency.
                configuration = TranslationSession.Configuration(source: source, target: target, preferredStrategy: .lowLatency)
            } else {
                configuration = TranslationSession.Configuration(source: source, target: target)
            }
        }
        #else
        return false
        #endif
    }

    func finish(_ success: Bool) {
        configuration = nil
        let continuation = waiting
        waiting = nil
        continuation?.resume(returning: success)
    }
}

struct AppleTranslationDownloadHost: ViewModifier {
    @ObservedObject var downloads: AppleTranslationDownloads

    func body(content: Content) -> some View {
        #if canImport(Translation)
        if #available(iOS 18.0, *) {
            content.translationTask(downloads.configuration as? TranslationSession.Configuration) { session in
                do {
                    try await session.prepareTranslation()
                    downloads.finish(true)
                } catch {
                    AppleTranslationTrace.record("prepareTranslation failed: \(error)")
                    downloads.finish(false)
                }
            }
        } else {
            content
        }
        #else
        content
        #endif
    }
}
