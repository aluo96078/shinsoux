import AppKit
import Foundation
import WebKit

private struct CookiePayload: Codable {
    let name: String
    let value: String
    let domain: String
    let path: String
    let expiresAtEpochMillis: Int64?
    let secure: Bool
    let httpOnly: Bool
    let hostOnly: Bool
}

private struct LaunchPayload: Codable {
    let url: String
    let sourceName: String
    let userAgent: String
    let cookies: [CookiePayload]
    let username: String?
    let password: String?
}

private struct HelperEvent: Codable {
    let type: String
    let message: String?
    let cookies: [CookiePayload]?
    let userAgent: String?

    static func simple(_ type: String) -> HelperEvent {
        HelperEvent(type: type, message: nil, cookies: nil, userAgent: nil)
    }

    static func error(_ message: String) -> HelperEvent {
        HelperEvent(type: "error", message: message, cookies: nil, userAgent: nil)
    }

    static func captured(_ cookies: [CookiePayload], userAgent: String) -> HelperEvent {
        HelperEvent(type: "cookies", message: nil, cookies: cookies, userAgent: userAgent)
    }

}

private final class EventWriter {
    private let encoder = JSONEncoder()
    private let queue = DispatchQueue(label: "dev.aluo.shinsoux.web-challenge.events")

    func send(_ event: HelperEvent) {
        queue.async {
            guard let data = try? self.encoder.encode(event) else { return }
            FileHandle.standardOutput.write(data)
            FileHandle.standardOutput.write(Data([0x0a]))
        }
    }
}

private final class ChallengeController: NSObject, NSApplicationDelegate, WKNavigationDelegate, WKUIDelegate, NSWindowDelegate {
    private let launch: LaunchPayload
    private let writer: EventWriter
    private let origin: URL
    private let dataStore: WKWebsiteDataStore
    private var webView: WKWebView?
    private var window: NSWindow?
    private var didReportFirstPage = false
    private var autoLoginInFlight = false
    private var didSubmitAutomaticLogin = false
    private var automaticLoginRecoveryAttempts = 0
    private var terminating = false

    init(launch: LaunchPayload, writer: EventWriter, origin: URL) {
        self.launch = launch
        self.writer = writer
        self.origin = origin
        self.dataStore = .nonPersistent()
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        createWindow()
        seedCookiesAndLoad()
        readCommands()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    func applicationWillTerminate(_ notification: Notification) {
        if !terminating {
            writer.send(.simple("closed"))
        }
    }

    func windowWillClose(_ notification: Notification) {
        if !terminating {
            writer.send(.simple("closed"))
            terminate()
        }
    }

    private func createWindow() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = dataStore
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true

        let browser = WKWebView(frame: .zero, configuration: configuration)
        browser.navigationDelegate = self
        browser.uiDelegate = self
        // Cloudflare detects a custom UA even when its text matches desktop Safari and keeps the
        // challenge in a loop. Use WKWebView's genuine UA; capture it with cookies so the source's
        // subsequent HTTP requests can use the exact browser-bound value.
        browser.customUserAgent = nil
        browser.allowsMagnification = true
        webView = browser

        let visible = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1100, height: 800)
        let size = NSSize(
            width: min(max(visible.width * 0.78, 760), 1180),
            height: min(max(visible.height * 0.82, 560), 860)
        )
        let browserWindow = NSWindow(
            contentRect: NSRect(origin: .zero, size: size),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        browserWindow.title = "Web challenge / Cloudflare — \(launch.sourceName)"
        browserWindow.minSize = NSSize(width: 680, height: 480)
        browserWindow.contentView = browser
        browserWindow.delegate = self
        browserWindow.center()
        browserWindow.makeKeyAndOrderFront(nil)
        window = browserWindow

        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        writer.send(.simple("ready"))
    }

    private func seedCookiesAndLoad() {
        let store = dataStore.httpCookieStore
        let group = DispatchGroup()
        for payload in launch.cookies {
            guard let cookie = makeCookie(payload) else { continue }
            group.enter()
            store.setCookie(cookie) { group.leave() }
        }
        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            self.webView?.load(URLRequest(url: self.origin, cachePolicy: .reloadIgnoringLocalCacheData))
        }
    }

    private func makeCookie(_ payload: CookiePayload) -> HTTPCookie? {
        var properties: [HTTPCookiePropertyKey: Any] = [
            .name: payload.name,
            .value: payload.value,
            .domain: payload.domain,
            .path: payload.path.isEmpty ? "/" : payload.path,
        ]
        if payload.secure { properties[.secure] = "TRUE" }
        if let expires = payload.expiresAtEpochMillis {
            properties[.expires] = Date(timeIntervalSince1970: TimeInterval(expires) / 1000.0)
        }
        return HTTPCookie(properties: properties)
    }

    private func readCommands() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            while let line = readLine(strippingNewline: true) {
                DispatchQueue.main.async {
                    guard let self else { return }
                    switch line {
                    case "capture": self.captureCookies()
                    case "close": self.terminate()
                    default: self.writer.send(.error("The browser helper received an unsupported command."))
                    }
                }
            }
            DispatchQueue.main.async { [weak self] in self?.terminate() }
        }
    }

    private func captureCookies() {
        guard let browser = webView else {
            writer.send(.error("The browser session is unavailable."))
            return
        }
        browser.evaluateJavaScript("navigator.userAgent") { [weak self] value, _ in
            guard let self else { return }
            let userAgent = (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !userAgent.isEmpty, userAgent.count <= 512,
                  !userAgent.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else {
                self.writer.send(.error("The browser User-Agent could not be read."))
                return
            }
            self.dataStore.httpCookieStore.getAllCookies { cookies in
                let payloads = cookies.compactMap { self.payload(for: $0) }
                self.writer.send(.captured(payloads, userAgent: userAgent))
            }
        }
    }

    private func payload(for cookie: HTTPCookie) -> CookiePayload? {
        guard !cookie.name.isEmpty, !cookie.value.isEmpty else { return nil }
        let rawDomain = cookie.domain.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rawDomain.isEmpty else { return nil }
        return CookiePayload(
            name: cookie.name,
            value: cookie.value,
            domain: rawDomain,
            path: cookie.path.isEmpty ? "/" : cookie.path,
            expiresAtEpochMillis: cookie.expiresDate.map { Int64($0.timeIntervalSince1970 * 1000.0) },
            secure: cookie.isSecure,
            httpOnly: cookie.isHTTPOnly,
            hostOnly: !rawDomain.hasPrefix(".")
        )
    }

    private func terminate() {
        guard !terminating else { return }
        terminating = true
        webView?.stopLoading()
        window?.delegate = nil
        window?.orderOut(nil)
        window?.close()
        NSApp.terminate(nil)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if !didReportFirstPage {
            didReportFirstPage = true
            writer.send(.simple("loaded"))
        }
        attemptAutomaticLogin(in: webView)
    }

    private func attemptAutomaticLogin(in browser: WKWebView) {
        guard !didSubmitAutomaticLogin, !autoLoginInFlight,
              let pageURL = browser.url, isSameOrigin(pageURL),
              let username = launch.username, !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let password = launch.password, !password.isEmpty else {
            return
        }

        autoLoginInFlight = true
        browser.callAsyncJavaScript(
            Self.automaticLoginScript,
            arguments: ["username": username, "password": password],
            in: nil,
            in: .page
        ) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.autoLoginInFlight = false
                switch result {
                case .success(let value):
                    if let status = value as? String,
                       status == "submitted" || status == "already-submitted" {
                        self.didSubmitAutomaticLogin = true
                    }
                case .failure:
                    // A Cloudflare navigation can cancel JavaScript before it reports that no
                    // login form existed. Retry a bounded number of times against the settled
                    // same-origin document; ordinary "no form" results wait for didFinish.
                    guard self.automaticLoginRecoveryAttempts < 2 else { return }
                    self.automaticLoginRecoveryAttempts += 1
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self, weak browser] in
                        guard let self, let browser,
                              browser.url.map(self.isSameOrigin) == true,
                              !browser.isLoading else { return }
                        self.attemptAutomaticLogin(in: browser)
                    }
                }
            }
        }
    }

    private func isSameOrigin(_ candidate: URL) -> Bool {
        guard let candidateScheme = candidate.scheme?.lowercased(),
              let originScheme = origin.scheme?.lowercased(),
              let candidateHost = candidate.host?.lowercased(),
              let originHost = origin.host?.lowercased() else {
            return false
        }
        return candidateScheme == originScheme &&
            candidateHost == originHost &&
            effectivePort(candidate) == effectivePort(origin)
    }

    private func effectivePort(_ url: URL) -> Int? {
        if let port = url.port { return port }
        switch url.scheme?.lowercased() {
        case "http": return 80
        case "https": return 443
        default: return nil
        }
    }

    private static let automaticLoginScript = #"""
    const suppliedUsername = String(username ?? "");
    const suppliedPassword = String(password ?? "");
    if (!suppliedUsername.trim() || !suppliedPassword) return "missing-credentials";
    const attemptKey = "__shinsouAutomaticLoginSubmitted";
    if (sessionStorage.getItem(attemptKey) === "1") return "already-submitted";

    const passwordInput = Array.from(document.querySelectorAll('input[type="password"]'))
        .find((input) => !input.disabled && !input.readOnly);
    if (!passwordInput) return "no-password-field";

    const form = passwordInput.form || passwordInput.closest("form");
    if (!form) return "no-form";

    const action = new URL(form.getAttribute("action") || location.href, location.href);
    if (!/^https?:$/.test(action.protocol) || action.origin !== location.origin) {
        return "blocked-form-action";
    }

    const selectors = [
        'input[autocomplete="username" i]',
        'input[name="username" i]',
        'input[name="email" i]',
        'input[type="email"]',
        'input[name*="user" i]',
        'input[name*="account" i]',
        'input[id*="user" i]',
        'input[id*="email" i]',
        'input[type="text"]'
    ];
    let usernameInput = null;
    for (const selector of selectors) {
        const candidate = form.querySelector(selector);
        if (candidate && candidate !== passwordInput && !candidate.disabled && !candidate.readOnly &&
            String(candidate.type).toLowerCase() !== "hidden") {
            usernameInput = candidate;
            break;
        }
    }
    if (!usernameInput) return "no-username-field";

    const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
    const fill = (input, value) => {
        if (valueSetter) valueSetter.call(input, value);
        else input.value = value;
        input.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
        input.dispatchEvent(new Event("change", { bubbles: true }));
    };
    fill(usernameInput, suppliedUsername);
    fill(passwordInput, suppliedPassword);
    sessionStorage.setItem(attemptKey, "1");

    const submitter = Array.from(form.querySelectorAll('button, input[type="submit"], input[type="image"]'))
        .find((element) => !element.disabled && String(element.type).toLowerCase() === "submit");
    if (typeof form.requestSubmit === "function") {
        if (submitter) form.requestSubmit(submitter);
        else form.requestSubmit();
    } else if (submitter) {
        submitter.click();
    } else {
        HTMLFormElement.prototype.submit.call(form);
    }
    return "submitted";
    """#

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        reportNavigationError(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        reportNavigationError(error)
    }

    private func reportNavigationError(_ error: Error) {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled { return }
        writer.send(.error("The verification page could not be loaded."))
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            // WebKit can synthesize an empty request while replacing a challenge iframe. With no
            // destination URL it cannot hand navigation to another application.
            decisionHandler(.allow)
            return
        }
        guard let scheme = url.scheme?.lowercased() else {
            decisionHandler(.cancel)
            reportBlockedNavigation(scheme: nil)
            return
        }
        switch scheme {
        case "http", "https":
            decisionHandler(.allow)
        case "about":
            // Cloudflare creates short-lived about:blank/about:srcdoc frames (occasionally with a
            // fragment) while collecting browser proof. All about: destinations remain internal.
            decisionHandler(.allow)
        case "blob", "data", "javascript":
            // These schemes stay inside this isolated WKWebView and are used by challenge scripts.
            // They cannot dispatch to another macOS application.
            decisionHandler(.allow)
        default:
            decisionHandler(.cancel)
            reportBlockedNavigation(scheme: scheme)
        }
    }

    private func reportBlockedNavigation(scheme: String?) {
        let safeScheme = scheme?.filter { $0.isLetter || $0.isNumber || $0 == "+" || $0 == "-" || $0 == "." }
        let label = safeScheme?.isEmpty == false ? safeScheme! : "missing"
        let message = "Blocked unsupported navigation scheme: \(label)."
        FileHandle.standardError.write(Data((message + "\n").utf8))
        writer.send(.error(message))
    }

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if navigationAction.targetFrame == nil,
           let url = navigationAction.request.url,
           ["http", "https"].contains(url.scheme?.lowercased() ?? "") {
            webView.load(URLRequest(url: url))
        }
        return nil
    }
}

private func fail(_ writer: EventWriter, _ message: String) -> Never {
    writer.send(.error(message))
    // EventWriter is asynchronous; give its tiny final write a bounded chance to complete.
    usleep(50_000)
    exit(EXIT_FAILURE)
}

private let writer = EventWriter()
guard let launchLine = readLine(strippingNewline: true),
      let launchData = launchLine.data(using: .utf8),
      let launch = try? JSONDecoder().decode(LaunchPayload.self, from: launchData) else {
    fail(writer, "The browser helper could not read its launch request.")
}
guard let origin = URL(string: launch.url),
      let scheme = origin.scheme?.lowercased(),
      scheme == "http" || scheme == "https",
      origin.host != nil else {
    fail(writer, "The source URL is invalid.")
}

private let application = NSApplication.shared
private let controller = ChallengeController(launch: launch, writer: writer, origin: origin)
application.delegate = controller
application.run()
