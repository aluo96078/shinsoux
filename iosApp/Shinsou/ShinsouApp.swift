import SwiftUI
import UIKit
import WidgetKit
import AVFoundation
import MediaPlayer
import ShinsouKit

@main
struct ShinsouIOSApp: App {
    init() {
        // Keep a device-readable trace for volume-button diagnosis. Unified logging has proven
        // unreliable when the app is relaunched by CoreDevice or hosted by LiveContainer.
        _ = ReaderVolumeKeyTrace.shared

        // BGTaskScheduler registration must happen during launch, before Compose suspend startup.
        _ = MainViewControllerKt.registerAutomaticBackupBackgroundTask()

        // Match the original Shinsou singleton lifetime and prewarm from the durable KMP setting.
        // This runs independently of SwiftUI/Compose notification ordering on a cold launch.
        if MainViewControllerKt.isReaderVolumeKeyConfigured() {
            DispatchQueue.main.async {
                ReaderVolumeKeyMonitor.shared.setApplicationActive(
                    UIApplication.shared.applicationState == .active
                )
                ReaderVolumeKeyMonitor.shared.setInfrastructureEnabled(true)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            ShinsouRootView()
        }
    }
}

/// Small, bounded trace stored at Documents/Diagnostics/reader-volume-keys.log.
///
/// The file intentionally contains only volume values and reader-routing state. It can be copied
/// from an installed app's data container without attaching a debugger or collecting user data.
private final class ReaderVolumeKeyTrace {
    static let shared = ReaderVolumeKeyTrace()

    static let relativePath = "Documents/Diagnostics/reader-volume-keys.log"
    private static let notificationName = Notification.Name(
        "dev.aluo.shinsoux.reader-volume-keys.trace"
    )
    private static let maximumPreviousBytes: UInt64 = 512 * 1024

    private let queue = DispatchQueue(label: "dev.aluo.shinsoux.reader-volume-keys.trace")
    private let fileURL: URL
    private var commonTraceObserver: NSObjectProtocol?

    private init() {
        let documents = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let directory = documents.appendingPathComponent("Diagnostics", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        fileURL = directory.appendingPathComponent("reader-volume-keys.log")

        if let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
           let size = attributes[.size] as? NSNumber,
           size.uint64Value > Self.maximumPreviousBytes {
            try? FileManager.default.removeItem(at: fileURL)
        }
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        }

        commonTraceObserver = NotificationCenter.default.addObserver(
            forName: Self.notificationName,
            object: nil,
            queue: nil
        ) { [weak self] notification in
            guard let payload = notification.object as? String else { return }
            self?.record("source=common \(payload)")
        }
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
            ?? "unknown"
        record("source=swift event=launch build=\(build)")
    }

    func record(_ payload: String) {
        let cleanPayload = payload
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
        let wall = Date().timeIntervalSince1970
        let uptime = ProcessInfo.processInfo.systemUptime
        let line = "wall=\(wall) uptime=\(uptime) \(cleanPayload)\n"
        queue.async { [fileURL] in
            guard let data = line.data(using: .utf8) else { return }
            do {
                let handle = try FileHandle(forWritingTo: fileURL)
                defer { try? handle.close() }
                try handle.seekToEnd()
                try handle.write(contentsOf: data)
                handle.synchronizeFile()
            } catch {
                NSLog("[ShinsouX.VolumeKeys] trace write failed: %@", error.localizedDescription)
            }
        }
    }
}

private struct ShinsouRootView: View {
    private static let secureScreenStateDidChange = Notification.Name(
        "dev.aluo.shinsoux.secure-screen.changed"
    )
    private static let widgetLibraryDidChange = Notification.Name(
        "dev.aluo.shinsoux.widget-library.changed"
    )
    private static let readerPresentationDidChange = Notification.Name(
        "dev.aluo.shinsoux.reader-presentation.changed"
    )
    private static let readerVolumeKeysDidChange = Notification.Name(
        "dev.aluo.shinsoux.reader-volume-keys.changed"
    )

    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var volumeKeyMonitor = ReaderVolumeKeyMonitor.shared
    @State private var isScreenCaptured = UIScreen.main.isCaptured
    @State private var securityStateRevision = 0
    @State private var readerFullscreen = false

    private var needsPrivacyCover: Bool {
        MainViewControllerKt.isSecureScreenEnabled()
            && (scenePhase != .active || isScreenCaptured)
    }

    var body: some View {
        ZStack {
            ComposeRootView()
                .ignoresSafeArea()

            if needsPrivacyCover {
                PrivacyCover()
                    .transition(.opacity)
                    .zIndex(10)
            }
        }
        .statusBarHidden(readerFullscreen)
        .persistentSystemOverlays(readerFullscreen ? .hidden : .automatic)
        .onOpenURL { url in
            _ = MainViewControllerKt.handleDeepLink(url: url.absoluteString)
        }
        .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
            guard let url = activity.webpageURL else { return }
            _ = MainViewControllerKt.handleDeepLink(url: url.absoluteString)
        }
        .onAppear {
            MainViewControllerKt.setApplicationForeground(foreground: scenePhase == .active)
            volumeKeyMonitor.setApplicationActive(UIApplication.shared.applicationState != .background)
            synchronizeVolumeKeyMonitor()
            // Compose can publish its persisted setting during the same presentation pass.
            // Re-read it on the next run loop so startup never depends on notification order.
            DispatchQueue.main.async {
                synchronizeVolumeKeyMonitor()
            }
            readerFullscreen = MainViewControllerKt.isReaderFullscreenEnabled()
            applyReaderOrientation()
        }
        .onChange(of: scenePhase) { phase in
            MainViewControllerKt.setApplicationForeground(foreground: phase == .active)
            volumeKeyMonitor.setApplicationActive(UIApplication.shared.applicationState != .background)
            synchronizeVolumeKeyMonitor()
            if phase == .background {
                _ = MainViewControllerKt.scheduleAutomaticBackupBackgroundTask()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            isScreenCaptured = UIScreen.main.isCaptured
        }
        .onReceive(NotificationCenter.default.publisher(for: Self.secureScreenStateDidChange)) { _ in
            securityStateRevision &+= 1
        }
        .onReceive(NotificationCenter.default.publisher(for: Self.widgetLibraryDidChange)) { _ in
            DispatchQueue.main.async {
                WidgetCenter.shared.reloadTimelines(ofKind: "dev.aluo.shinsoux.recent-updates")
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: Self.readerPresentationDidChange)) { _ in
            readerFullscreen = MainViewControllerKt.isReaderFullscreenEnabled()
            applyReaderOrientation()
            // Reader presentation and volume-monitoring state are committed together by Compose.
            // Querying both here makes reader entry self-healing even if a startup volume-state
            // notification was delivered before SwiftUI installed its publisher subscription.
            synchronizeVolumeKeyMonitor()
        }
        .onReceive(NotificationCenter.default.publisher(for: Self.readerVolumeKeysDidChange)) { _ in
            synchronizeVolumeKeyMonitor()
        }
        .animation(.easeOut(duration: 0.15), value: needsPrivacyCover)
    }

    private func applyReaderOrientation() {
        guard let windowScene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }) else {
            return
        }

        let mask: UIInterfaceOrientationMask
        switch MainViewControllerKt.currentReaderOrientation() {
        case "PORTRAIT", "SENSOR_PORTRAIT":
            mask = .portrait
        case "LANDSCAPE", "SENSOR_LANDSCAPE":
            mask = .landscape
        default:
            mask = UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
        }

        windowScene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
        windowScene.windows.forEach {
            $0.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        }
    }

    private func synchronizeVolumeKeyMonitor() {
        let configured = MainViewControllerKt.isReaderVolumeKeyConfigured()
        let readerOpen = MainViewControllerKt.isReaderOpen()
        let mirroredInfrastructure = MainViewControllerKt.isReaderVolumeKeyInfrastructureEnabled()
        let mirroredListening = MainViewControllerKt.isReaderVolumeKeyMonitoringEnabled()
        NSLog(
            "[ShinsouX.VolumeKeys] synchronize configured=%d readerOpen=%d mirrorInfra=%d mirrorListen=%d active=%d",
            configured ? 1 : 0,
            readerOpen ? 1 : 0,
            mirroredInfrastructure ? 1 : 0,
            mirroredListening ? 1 : 0,
            UIApplication.shared.applicationState != .background ? 1 : 0
        )

        // Keep the audio infrastructure warm from the durable preference, but only intercept
        // hardware buttons while common code has an active consumer for the presented reader.
        volumeKeyMonitor.setInfrastructureEnabled(configured)
        volumeKeyMonitor.setListeningEnabled(configured && readerOpen && mirroredListening)
    }
}

/// Captures hardware volume HID events when LiveContainer does not mutate AVAudioSession.
private final class VolumeKeyCaptureViewController: UIViewController {
    let content: UIViewController

    init(content: UIViewController) {
        self.content = content
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(content)
        content.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content.view)
        NSLayoutConstraint.activate([
            content.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            content.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            content.view.topAnchor.constraint(equalTo: view.topAnchor),
            content.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        content.didMove(toParent: self)
    }

    override var canBecomeFirstResponder: Bool { true }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        becomeFirstResponder()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if view.window != nil && !isFirstResponder {
            becomeFirstResponder()
        }
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if handleVolumePresses(presses) { return }
        super.pressesBegan(presses, with: event)
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if handleVolumePresses(presses, began: false) { return }
        super.pressesEnded(presses, with: event)
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if handleVolumePresses(presses, began: false) { return }
        super.pressesCancelled(presses, with: event)
    }

    @discardableResult
    private func handleVolumePresses(_ presses: Set<UIPress>, began: Bool = true) -> Bool {
        var handled = false
        for press in presses {
            let volumeUp: Bool?
            switch press.key?.keyCode {
            case .keyboardVolumeUp:
                volumeUp = true
            case .keyboardVolumeDown:
                volumeUp = false
            default:
                volumeUp = nil
            }
            guard let volumeUp else { continue }
            if began {
                handled = ReaderVolumeKeyMonitor.shared.emitHardwareVolumeEvent(volumeUp: volumeUp) || handled
            } else {
                handled = true
            }
        }
        return handled
    }
}

private struct ComposeRootView: UIViewControllerRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController()
        let edgeBack = UIScreenEdgePanGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleEdgeBack(_:)),
        )
        edgeBack.edges = .left
        edgeBack.delegate = context.coordinator
        // Let Compose continue receiving the touch stream; the recognizer only emits after a
        // completed edge swipe and should not interfere with pager/content gestures.
        edgeBack.cancelsTouchesInView = false
        controller.view.addGestureRecognizer(edgeBack)

        if #available(iOS 13.4, *) {
            // Compose for iOS currently exposes only primary/secondary button masks. Register the
            // standard mouse "back" side button natively so iPad keyboard-and-mouse navigation
            // reaches the same common back stack as the edge gesture and Escape key.
            let mouseBack = UITapGestureRecognizer(
                target: context.coordinator,
                action: #selector(Coordinator.handleMouseBack(_:))
            )
            // buttonMaskRequired is only evaluated for indirect input. Without an explicit
            // touch-type gate, UIKit also recognizes an ordinary finger tap on iPhone and emits
            // a back event immediately after Compose opens a detail/settings/reader route.
            mouseBack.allowedTouchTypes = [
                NSNumber(value: UITouch.TouchType.indirectPointer.rawValue)
            ]
            mouseBack.buttonMaskRequired = .button(4)
            mouseBack.cancelsTouchesInView = false
            controller.view.addGestureRecognizer(mouseBack)
        }
        return VolumeKeyCaptureViewController(content: controller)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            // A reader owns the left edge for its pager/image gestures.  Do not even begin the
            // app-level back recognizer in that state; merely ignoring the ended callback still
            // steals the edge touch on some iOS releases.
            !MainViewControllerKt.isReaderOpen()
        }

        @objc func handleEdgeBack(_ gesture: UIScreenEdgePanGestureRecognizer) {
            guard let view = gesture.view else { return }
            let width = max(view.bounds.width, 1)
            let translation = max(0, gesture.translation(in: view).x)
            let fraction = min(max(translation / width, 0), 1)

            switch gesture.state {
            case .began, .changed:
                // Compose receives this stream continuously and moves only the active destination
                // surface. This keeps the source catalogue visible underneath the detail page
                // and makes the gesture respond to the finger instead of waiting for .ended.
                _ = MainViewControllerKt.handleSystemBackGestureProgress(fraction: Float(fraction))
            case .ended:
                let committed = fraction >= 0.32 || translation >= 96
                _ = MainViewControllerKt.handleSystemBackGestureSettled(committed: committed)
            case .cancelled, .failed:
                _ = MainViewControllerKt.handleSystemBackGestureSettled(committed: false)
            default:
                break
            }
        }

        @available(iOS 13.4, *)
        @objc func handleMouseBack(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended else { return }
            _ = MainViewControllerKt.handleSystemBackGesture()
        }
    }
}

/// Original Shinsou-style two-layer volume-button handler.
///
/// AVAudioSession, silent playback, and MPVolumeView stay warm whenever the setting is enabled.
/// Only output-volume KVO is added and removed as the reader opens and closes.
private final class ReaderVolumeKeyMonitor: NSObject, ObservableObject {
    static let shared = ReaderVolumeKeyMonitor()

    private let audioSession = AVAudioSession.sharedInstance()
    private var volumeView: MPVolumeView?
    private var volumeSlider: UISlider?
    private var silentPlayer: AVAudioPlayer?
    private var interruptionObserver: NSObjectProtocol?
    private var mediaResetObserver: NSObjectProtocol?
    private var didBecomeActiveObserver: NSObjectProtocol?
    private var willResignActiveObserver: NSObjectProtocol?
    private var installRetryWorkItem: DispatchWorkItem?
    private var installRetryCount = 0

    private var infrastructureEnabled = false
    private var listeningEnabled = false
    private var applicationActive = false
    private var interrupted = false
    private var monitoring = false
    private var previousVolume: Float = 0.5
    private var nativeEventSequence: UInt64 = 0
    private var resetGeneration: UInt64 = 0
    private var resetInFlight = false
    private var resetTarget: Float?
    private var resetSourceSequence: UInt64?
    private var resetAttempt = 0
    private var resetSettleWorkItem: DispatchWorkItem?

    private override init() {
        super.init()
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: audioSession,
            queue: .main
        ) { [weak self] notification in
            self?.handleInterruption(notification)
        }
        mediaResetObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification,
            object: audioSession,
            queue: .main
        ) { [weak self] _ in
            self?.handleMediaServicesReset()
        }
        didBecomeActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.setApplicationActive(true)
        }
        willResignActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.setApplicationActive(false)
        }
    }

    deinit {
        removeInfrastructure()
        if let interruptionObserver {
            NotificationCenter.default.removeObserver(interruptionObserver)
        }
        if let mediaResetObserver {
            NotificationCenter.default.removeObserver(mediaResetObserver)
        }
        if let didBecomeActiveObserver {
            NotificationCenter.default.removeObserver(didBecomeActiveObserver)
        }
        if let willResignActiveObserver {
            NotificationCenter.default.removeObserver(willResignActiveObserver)
        }
    }

    func setInfrastructureEnabled(_ enabled: Bool) {
        if infrastructureEnabled != enabled {
            NSLog("[ShinsouX.VolumeKeys] infrastructure=%d", enabled ? 1 : 0)
        }
        infrastructureEnabled = enabled
        if enabled {
            ensureInfrastructureHealthy()
        } else {
            removeInfrastructure()
        }
        reconcileMonitoring()
    }

    func setListeningEnabled(_ enabled: Bool) {
        if listeningEnabled != enabled {
            NSLog("[ShinsouX.VolumeKeys] listening=%d", enabled ? 1 : 0)
        }
        listeningEnabled = enabled
        if enabled, volumeSlider == nil {
            // Prewarming can exhaust its retries before SwiftUI has attached a key window.
            // Entering the reader must start a fresh recovery attempt, just like original
            // Shinsou's startListening() calls ensureInfrastructureHealthy() every time.
            installRetryWorkItem?.cancel()
            installRetryWorkItem = nil
            installRetryCount = 0
        }
        reconcileMonitoring()
    }

    func setApplicationActive(_ active: Bool) {
        if applicationActive != active {
            NSLog("[ShinsouX.VolumeKeys] applicationActive=%d", active ? 1 : 0)
        }
        applicationActive = active
        if active, infrastructureEnabled {
            ensureInfrastructureHealthy()
        }
        reconcileMonitoring()
    }

    func invalidate() {
        infrastructureEnabled = false
        listeningEnabled = false
        applicationActive = false
        removeInfrastructure()
    }

    @discardableResult
    func emitHardwareVolumeEvent(volumeUp: Bool) -> Bool {
        // LiveContainer can deliver HID volume events even when AVAudioSession
        // never mutates outputVolume. Consume the press when a reader is open
        // without starting KVO as a side effect of the same physical event.
        if !listeningEnabled {
            let configured = MainViewControllerKt.isReaderVolumeKeyConfigured()
            let readerOpen = MainViewControllerKt.isReaderOpen()
            guard configured && readerOpen else { return false }
        }
        let accepted = MainViewControllerKt.handleReaderVolumeKey(volumeUp: volumeUp)
        nativeEventSequence &+= 1
        let direction = volumeUp ? "up" : "down"
        ReaderVolumeKeyTrace.shared.record(
            "source=hid seq=\(nativeEventSequence) direction=\(direction) accepted=\(accepted ? 1 : 0)"
        )
        NSLog(
            "[ShinsouX.VolumeKeys] hid direction=%@ accepted=%d",
            volumeUp ? "up" : "down",
            accepted ? 1 : 0
        )
        return accepted
    }

    private func reconcileMonitoring() {
        let shouldMonitor = infrastructureEnabled
            && listeningEnabled
            && applicationActive
            && !interrupted
        if shouldMonitor {
            // Do not gate recovery on volumeSlider: that made a failed early MPVolumeView install
            // permanent until the user toggled the setting off and on. The original handler
            // repairs its infrastructure first and only then attempts to attach KVO.
            ensureInfrastructureHealthy()
            startMonitoring()
        } else {
            stopMonitoring()
        }
    }

    private func installVolumeViewIfNeeded() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard infrastructureEnabled else { return }

        guard let window = activeWindow() else {
            NSLog("[ShinsouX.VolumeKeys] MPVolumeView waiting for active window")
            scheduleVolumeViewInstallRetry()
            return
        }

        if volumeView?.superview !== window {
            volumeView?.removeFromSuperview()
            let view = MPVolumeView(frame: CGRect(x: -2_000, y: -2_000, width: 1, height: 1))
            view.showsVolumeSlider = true
            view.alpha = 0.01
            // MPVolumeSlider is lazily created only after MPVolumeView participates in layout
            // on some physical devices. Keep this off-screen view non-hidden while enabled so
            // slider discovery cannot deadlock waiting for a hardware volume event.
            view.isHidden = false
            window.addSubview(view)
            volumeView = view
        }

        guard let view = volumeView else { return }
        view.showsVolumeSlider = true
        view.isHidden = false
        view.setNeedsLayout()
        view.layoutIfNeeded()
        volumeSlider = findVolumeSlider(in: view)
        if volumeSlider == nil {
            NSLog("[ShinsouX.VolumeKeys] MPVolumeView slider unavailable retry=%d", installRetryCount)
            // Keep retrying layout while the view is attached. A one-shot next-run-loop lookup
            // can still run before MediaPlayer has constructed the private slider.
            scheduleVolumeViewInstallRetry()
            return
        }

        installRetryWorkItem?.cancel()
        installRetryWorkItem = nil
        installRetryCount = 0
        NSLog("[ShinsouX.VolumeKeys] MPVolumeView slider ready outputVolume=%.3f", audioSession.outputVolume)
    }

    private func scheduleVolumeViewInstallRetry() {
        guard infrastructureEnabled, installRetryWorkItem == nil, installRetryCount < 20 else {
            return
        }
        installRetryCount += 1
        let retry = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.installRetryWorkItem = nil
            self.installVolumeViewIfNeeded()
            self.reconcileMonitoring()
        }
        installRetryWorkItem = retry
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1, execute: retry)
    }

    private func activeWindow() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
        let rankedScenes = scenes.sorted { lhs, rhs in
            rank(lhs.activationState) > rank(rhs.activationState)
        }
        let windows = rankedScenes.flatMap(\.windows)
        return windows.first(where: { $0.isKeyWindow && !$0.isHidden })
            ?? windows.first(where: { !$0.isHidden && $0.windowLevel == .normal && $0.alpha > 0 })
            ?? windows.first(where: { !$0.isHidden })
            ?? UIApplication.shared.windows.first(where: { $0.isKeyWindow && !$0.isHidden })
            ?? UIApplication.shared.windows.first(where: { !$0.isHidden })
    }

    private func rank(_ state: UIScene.ActivationState) -> Int {
        switch state {
        case .foregroundActive: return 3
        case .foregroundInactive: return 2
        case .unattached: return 1
        default: return 0
        }
    }

    private func startMonitoring() {
        guard !monitoring, infrastructureEnabled, listeningEnabled else { return }
        ensureInfrastructureHealthy()

        // This is deliberately the same sequence as the original Shinsou handler: the audio
        // session and silent player are already warm; entering the reader refreshes the baseline
        // and adds KVO unconditionally. MPVolumeSlider is created lazily on some physical devices,
        // so it must never gate observation (the original implementation does not gate on it).
        previousVolume = audioSession.outputVolume
        clampVolumeToSafeRange()
        audioSession.addObserver(self, forKeyPath: "outputVolume", options: [.new], context: nil)
        monitoring = true
        let sliderValue = volumeSlider?.value.description ?? "none"
        ReaderVolumeKeyTrace.shared.record(
            "source=kvo event=started baseline=\(previousVolume) actual=\(audioSession.outputVolume) slider=\(sliderValue)"
        )
        NSLog(
            "[ShinsouX.VolumeKeys] KVO started baseline=%.3f slider=%d",
            previousVolume,
            volumeSlider == nil ? 0 : 1
        )
    }

    private func stopMonitoring() {
        guard monitoring else { return }
        audioSession.removeObserver(self, forKeyPath: "outputVolume")
        monitoring = false
        cancelVolumeReset(reason: "monitoring_stopped")
        ReaderVolumeKeyTrace.shared.record("source=kvo event=stopped")
        NSLog("[ShinsouX.VolumeKeys] KVO stopped")
    }

    private func ensureInfrastructureHealthy() {
        guard infrastructureEnabled, applicationActive else { return }

        do {
            try audioSession.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try audioSession.setActive(true)
        } catch {}

        if silentPlayer == nil || silentPlayer?.isPlaying == false {
            silentPlayer?.stop()
            if let player = try? AVAudioPlayer(data: Self.silentWavData) {
                player.numberOfLoops = -1
                player.volume = 0
                player.prepareToPlay()
                if player.play() {
                    silentPlayer = player
                }
            }
        }

        installVolumeViewIfNeeded()
        // KVO captures the new value before dispatching its handler to the main queue. Updating
        // the baseline while monitoring can therefore turn a queued physical press into a zero
        // delta when SwiftUI synchronizes the same enabled state between those two steps.
        // startMonitoring() refreshes the baseline immediately before adding the observer.
        if !monitoring {
            previousVolume = audioSession.outputVolume
            clampVolumeToSafeRange()
        }
    }

    private func removeInfrastructure() {
        stopMonitoring()
        installRetryWorkItem?.cancel()
        installRetryWorkItem = nil
        installRetryCount = 0
        silentPlayer?.stop()
        silentPlayer = nil
        volumeView?.removeFromSuperview()
        volumeView = nil
        volumeSlider = nil
        try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
    }

    override func observeValue(
        forKeyPath keyPath: String?,
        of object: Any?,
        change: [NSKeyValueChangeKey: Any]?,
        context: UnsafeMutableRawPointer?
    ) {
        guard keyPath == "outputVolume" else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
            return
        }

        let newValue = (change?[.newKey] as? NSNumber)?.floatValue
            ?? (change?[.newKey] as? Float)
            ?? audioSession.outputVolume
        DispatchQueue.main.async { [weak self] in
            self?.handleOutputVolumeChange(newValue)
        }
    }

    private func handleOutputVolumeChange(_ volume: Float) {
        guard monitoring else { return }
        nativeEventSequence &+= 1
        let eventSequence = nativeEventSequence
        let delta = volume - previousVolume
        let sliderValue = volumeSlider?.value.description ?? "none"
        ReaderVolumeKeyTrace.shared.record(
            "source=kvo seq=\(eventSequence) event=change volume=\(volume) baseline=\(previousVolume) delta=\(delta) actual=\(audioSession.outputVolume) slider=\(sliderValue)"
        )
        if resetInFlight {
            let targetDescription = resetTarget.map { String($0) } ?? "none"
            ReaderVolumeKeyTrace.shared.record(
                "source=kvo seq=\(eventSequence) event=ignored reason=reset_in_flight target=\(targetDescription)"
            )
            scheduleResetSettlementCheck()
            return
        }
        NSLog(
            "[ShinsouX.VolumeKeys] outputVolume=%.3f baseline=%.3f delta=%.3f",
            volume,
            previousVolume,
            delta
        )
        guard abs(delta) > Self.volumeThreshold else {
            ReaderVolumeKeyTrace.shared.record(
                "source=kvo seq=\(eventSequence) event=ignored reason=threshold"
            )
            return
        }

        let accepted = MainViewControllerKt.handleReaderVolumeKey(volumeUp: delta > 0)
        let direction = delta > 0 ? "up" : "down"
        ReaderVolumeKeyTrace.shared.record(
            "source=kvo seq=\(eventSequence) event=dispatch direction=\(direction) accepted=\(accepted ? 1 : 0)"
        )
        NSLog(
            "[ShinsouX.VolumeKeys] event direction=%@ accepted=%d",
            delta > 0 ? "up" : "down",
            accepted ? 1 : 0
        )

        // A reader transition can turn monitoring off one native run-loop after this KVO event
        // was queued. If common code has no consumer, preserve the user's actual volume change.
        guard accepted else {
            previousVolume = volume
            ReaderVolumeKeyTrace.shared.record(
                "source=kvo seq=\(eventSequence) event=baseline_advanced baseline=\(previousVolume)"
            )
            return
        }

        // Reset to the unchanged baseline. The resulting KVO callback has delta ~= 0 and is
        // ignored naturally, matching the original implementation without an extra debounce
        // state that could suppress the next physical press.
        // If the real baseline was at 0/1 and the first press caused MPVolumeSlider to appear,
        // promote it to the safe midpoint now so both directions work on the following press.
        clampVolumeToSafeRange()
        if !beginVolumeReset(eventSequence: eventSequence) {
            previousVolume = volume
            clampVolumeToSafeRange()
            ReaderVolumeKeyTrace.shared.record(
                "source=kvo seq=\(eventSequence) event=reset_unavailable baseline=\(previousVolume)"
            )
        }
    }

    private func handleInterruption(_ notification: Notification) {
        guard
            let number = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber,
            let type = AVAudioSession.InterruptionType(rawValue: number.uintValue)
        else {
            return
        }

        switch type {
        case .began:
            interrupted = true
            stopMonitoring()
        case .ended:
            interrupted = false
            ensureInfrastructureHealthy()
            reconcileMonitoring()
        @unknown default:
            interrupted = true
            stopMonitoring()
        }
    }

    private func handleMediaServicesReset() {
        stopMonitoring()
        silentPlayer?.stop()
        silentPlayer = nil
        volumeView?.removeFromSuperview()
        volumeView = nil
        volumeSlider = nil
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.ensureInfrastructureHealthy()
            self.reconcileMonitoring()
        }
    }

    private func clampVolumeToSafeRange() {
        if previousVolume <= 0.01 || previousVolume >= 0.99 {
            // Do not retain a fake 0.5 baseline when MPVolumeSlider has not materialized yet.
            // Otherwise the first volume-up event from a real volume of zero looks like a
            // negative delta and is misclassified as volume-down.
            if setSystemVolume(Self.defaultVolume) {
                previousVolume = Self.defaultVolume
            }
        }
    }

    @discardableResult
    private func beginVolumeReset(eventSequence: UInt64) -> Bool {
        resetGeneration &+= 1
        resetInFlight = true
        resetTarget = previousVolume
        resetSourceSequence = eventSequence
        resetAttempt = 1
        resetSettleWorkItem?.cancel()
        resetSettleWorkItem = nil

        guard setSystemVolume(
            previousVolume,
            eventSequence: eventSequence,
            resetGeneration: resetGeneration
        ) else {
            cancelVolumeReset(reason: "slider_unavailable")
            return false
        }
        scheduleResetSettlementCheck()
        return true
    }

    private func scheduleResetSettlementCheck() {
        guard resetInFlight, let target = resetTarget else { return }
        let generation = resetGeneration
        let sourceSequence = resetSourceSequence
        resetSettleWorkItem?.cancel()
        let workItem = DispatchWorkItem { [weak self] in
            guard
                let self,
                self.monitoring,
                self.resetInFlight,
                self.resetGeneration == generation,
                self.resetTarget == target
            else { return }

            let actual = self.audioSession.outputVolume
            let sequenceDescription = sourceSequence.map { String($0) } ?? "none"
            if abs(actual - target) <= Self.volumeThreshold {
                ReaderVolumeKeyTrace.shared.record(
                    "source=kvo seq=\(sequenceDescription) event=reset_settled target=\(target) actual=\(actual) attempts=\(self.resetAttempt)"
                )
                self.finishVolumeReset()
                return
            }

            guard self.resetAttempt < Self.maximumResetAttempts else {
                self.previousVolume = actual
                ReaderVolumeKeyTrace.shared.record(
                    "source=kvo seq=\(sequenceDescription) event=reset_abandoned target=\(target) actual=\(actual) attempts=\(self.resetAttempt) baseline=\(self.previousVolume)"
                )
                self.finishVolumeReset()
                return
            }

            self.resetAttempt += 1
            ReaderVolumeKeyTrace.shared.record(
                "source=kvo seq=\(sequenceDescription) event=reset_retry target=\(target) actual=\(actual) attempt=\(self.resetAttempt)"
            )
            if self.setSystemVolume(
                target,
                eventSequence: sourceSequence,
                resetGeneration: generation
            ) {
                self.scheduleResetSettlementCheck()
            } else {
                self.previousVolume = actual
                self.cancelVolumeReset(reason: "retry_slider_unavailable")
            }
        }
        resetSettleWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.resetQuietPeriod, execute: workItem)
    }

    private func finishVolumeReset() {
        resetSettleWorkItem?.cancel()
        resetSettleWorkItem = nil
        resetInFlight = false
        resetTarget = nil
        resetSourceSequence = nil
        resetAttempt = 0
    }

    private func cancelVolumeReset(reason: String) {
        if resetInFlight {
            let targetDescription = resetTarget.map { String($0) } ?? "none"
            let sequenceDescription = resetSourceSequence.map { String($0) } ?? "none"
            ReaderVolumeKeyTrace.shared.record(
                "source=kvo seq=\(sequenceDescription) event=reset_cancelled reason=\(reason) target=\(targetDescription) actual=\(audioSession.outputVolume)"
            )
        }
        resetGeneration &+= 1
        finishVolumeReset()
    }

    @discardableResult
    private func setSystemVolume(
        _ target: Float,
        eventSequence: UInt64? = nil,
        resetGeneration expectedResetGeneration: UInt64? = nil
    ) -> Bool {
        let sequenceDescription = eventSequence.map { String($0) } ?? "none"
        // MPVolumeSlider may materialize only after the first hardware event. Resolve it again on
        // every reset instead of permanently caching a cold-launch miss.
        if volumeSlider == nil, let volumeView {
            volumeSlider = findVolumeSlider(in: volumeView)
        }
        guard let volumeSlider else {
            ReaderVolumeKeyTrace.shared.record(
                "source=kvo seq=\(sequenceDescription) event=reset_missing_slider target=\(target) actual=\(audioSession.outputVolume)"
            )
            NSLog("[ShinsouX.VolumeKeys] volume reset deferred: slider unavailable")
            return false
        }
        // Device traces 25600114/115 proved that the private slider can remain at the baseline
        // after the hardware button has changed AVAudioSession.outputVolume. Sending that same
        // slider value is a no-op on this device, even with valueChanged. First synchronize the
        // control to the actual volume without emitting an action, then submit the distinct target
        // on the next main-queue turn so MediaPlayer observes a real slider transition.
        let actualBeforeReset = audioSession.outputVolume
        let sliderBeforeReset = volumeSlider.value
        volumeSlider.setValue(actualBeforeReset, animated: false)
        ReaderVolumeKeyTrace.shared.record(
            "source=kvo seq=\(sequenceDescription) event=reset_prepared target=\(target) actual=\(actualBeforeReset) slider_before=\(sliderBeforeReset) slider_prepared=\(volumeSlider.value)"
        )
        DispatchQueue.main.async { [weak self, weak volumeSlider] in
            guard
                let self,
                let volumeSlider,
                self.monitoring,
                self.volumeSlider === volumeSlider,
                expectedResetGeneration == nil || (
                    self.resetInFlight && self.resetGeneration == expectedResetGeneration
                )
            else { return }
            volumeSlider.setValue(target, animated: false)
            volumeSlider.sendActions(for: .valueChanged)
            volumeSlider.sendActions(for: .touchUpInside)
            ReaderVolumeKeyTrace.shared.record(
                "source=kvo seq=\(sequenceDescription) event=reset_submitted target=\(target) actual=\(self.audioSession.outputVolume) slider=\(volumeSlider.value)"
            )
            NSLog("[ShinsouX.VolumeKeys] volume reset submitted=%.3f", target)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.075) { [weak self, weak volumeSlider] in
            guard
                let self,
                let volumeSlider,
                self.monitoring,
                self.volumeSlider === volumeSlider,
                expectedResetGeneration == nil || (
                    self.resetInFlight && self.resetGeneration == expectedResetGeneration
                )
            else { return }
            ReaderVolumeKeyTrace.shared.record(
                "source=kvo seq=\(sequenceDescription) event=reset_observed target=\(target) actual=\(self.audioSession.outputVolume) slider=\(volumeSlider.value)"
            )
            NSLog(
                "[ShinsouX.VolumeKeys] volume reset observed target=%.3f actual=%.3f slider=%.3f",
                target,
                self.audioSession.outputVolume,
                volumeSlider.value
            )
        }
        return true
    }

    private static let defaultVolume: Float = 0.5
    private static let volumeThreshold: Float = 0.001
    private static let resetQuietPeriod: TimeInterval = 0.12
    private static let maximumResetAttempts = 3

    private func findVolumeSlider(in view: UIView) -> UISlider? {
        if let slider = view as? UISlider {
            return slider
        }
        for subview in view.subviews {
            if let slider = findVolumeSlider(in: subview) {
                return slider
            }
        }
        return nil
    }

    /// Exact silent PCM format used by the original app: 100 ms, 8 kHz, unsigned 8-bit mono.
    private static let silentWavData: Data = {
        let sampleRate: UInt32 = 8_000
        let dataSize: UInt32 = sampleRate / 10
        let channelCount: UInt16 = 1
        let bitsPerSample: UInt16 = 8
        let byteRate = sampleRate
        let blockAlign: UInt16 = 1
        var data = Data()

        func appendAscii(_ value: String) {
            data.append(value.data(using: .ascii)!)
        }

        func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
            var littleEndian = value.littleEndian
            Swift.withUnsafeBytes(of: &littleEndian) { bytes in
                data.append(contentsOf: bytes)
            }
        }

        appendAscii("RIFF")
        appendLittleEndian(UInt32(36) + dataSize)
        appendAscii("WAVE")
        appendAscii("fmt ")
        appendLittleEndian(UInt32(16))
        appendLittleEndian(UInt16(1))
        appendLittleEndian(channelCount)
        appendLittleEndian(sampleRate)
        appendLittleEndian(byteRate)
        appendLittleEndian(blockAlign)
        appendLittleEndian(bitsPerSample)
        appendAscii("data")
        appendLittleEndian(dataSize)
        data.append(Data(repeating: 0x80, count: Int(dataSize)))
        return data
    }()
}

private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color(uiColor: .systemBackground)
                .ignoresSafeArea()

            VStack(spacing: 12) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 38, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text("Shinsou X")
                    .font(.headline)
                    .foregroundStyle(.primary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Shinsou X content hidden")
        }
    }
}
