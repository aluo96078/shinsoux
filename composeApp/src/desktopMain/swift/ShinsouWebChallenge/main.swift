import AppKit
import Foundation
import WebKit
import Network
import Darwin

private let launchLineMaxBytes = 2 * 1_024 * 1_024
private let challengeCommandMaxBytes = 64
private let reviewedImageCommandMaxBytes = 16 * 1_024
// A 4 MiB evaluated string can occupy 24 MiB after worst-case JSON escaping.
private let browserCommandLineMaxBytes = 26 * 1_024 * 1_024
private let helperEventMaxBytes = 26 * 1_024 * 1_024
// Reviewed image results contain a base64 encoding of at most 4 MiB plus bounded JSON framing.
private let evaluatedValueMaxBytes = 5_594_432
private let browserSessionEvaluatedValueMaxBytes = 4 * 1_024 * 1_024
private let captureCookieMaxCount = 256
private let captureCookieAggregateMaxBytes = 1 * 1_024 * 1_024
private let captureStorageMaxCount = 8
private let captureStorageValueMaxBytes = 16 * 1_024
private let captureStorageAggregateMaxBytes = 32 * 1_024
private let reviewedTunnelByteMax = 16 * 1_024 * 1_024

@available(macOS 14.0, *)
private final class ReviewedConnectProxy {
    private let queue = DispatchQueue(label: "dev.aluo.shinsoux.reviewed-connect")
    private let listener: NWListener
    private let approvedAddresses: [NWEndpoint.Host]
    private let authorization: String
    private var active = 0
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var closed = false

    init(addresses: [String], token: String) throws {
        guard !addresses.isEmpty, addresses.count <= 32, token.utf8.count >= 32,
              token.utf8.count <= 128 else { throw BoundedLineError.invalidUTF8 }
        approvedAddresses = try addresses.map {
            guard !Self.isObviouslyPrivateAddress($0) else { throw BoundedLineError.invalidUTF8 }
            guard let ipv4 = IPv4Address($0) else {
                guard let ipv6 = IPv6Address($0) else { throw BoundedLineError.invalidUTF8 }
                return .ipv6(ipv6)
            }
            return .ipv4(ipv4)
        }
        authorization = Data("shinsou:\(token)".utf8).base64EncodedString()
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        listener = try NWListener(using: parameters, on: .any)
    }

    func start() throws -> UInt16 {
        let ready = DispatchSemaphore(value: 0)
        var failure: NWError?
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready: ready.signal()
            case .failed(let error): failure = error; ready.signal()
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.admit(connection) }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success, failure == nil,
              let port = listener.port else { listener.cancel(); throw BoundedLineError.truncated }
        return port.rawValue
    }

    func cancel() {
        queue.async {
            guard !self.closed else { return }
            self.closed = true
            self.listener.cancel()
            self.connections.values.forEach { $0.cancel() }
            self.connections.removeAll()
        }
    }

    private func admit(_ client: NWConnection) {
        guard !closed, active < 4 else { client.cancel(); return }
        active += 1
        connections[ObjectIdentifier(client)] = client
        client.stateUpdateHandler = { [weak self] state in
            switch state {
            case .cancelled, .failed:
                self?.queue.async { self?.active = max(0, (self?.active ?? 1) - 1) }
                self?.connections.removeValue(forKey: ObjectIdentifier(client))
                client.stateUpdateHandler = nil
            default: break
            }
        }
        client.start(queue: queue)
        receiveConnect(client, Data(), authenticationChallenges: 0)
    }

    private func receiveConnect(_ client: NWConnection, _ accumulated: Data, authenticationChallenges: Int) {
        client.receive(minimumIncompleteLength: 1, maximumLength: 8_193) { [weak self] data, _, complete, error in
            guard let self else { client.cancel(); return }
            var request = accumulated
            if let data { request.append(data) }
            guard request.count <= 8_192 else { client.cancel(); return }
            if let end = request.range(of: Data("\r\n\r\n".utf8)) {
                guard end.upperBound == request.endIndex,
                      let text = String(data: request, encoding: .utf8) else { client.cancel(); return }
                self.openTunnel(client, text, authenticationChallenges: authenticationChallenges)
            } else if complete || error != nil {
                client.cancel()
            } else {
                self.receiveConnect(client, request, authenticationChallenges: authenticationChallenges)
            }
        }
    }

    private func openTunnel(_ client: NWConnection, _ request: String, authenticationChallenges: Int) {
        let lines = request.components(separatedBy: "\r\n")
        let printableSegments = lines.allSatisfy { segment in
            segment.unicodeScalars.allSatisfy { $0.value >= 32 && $0.value <= 126 }
        }
        guard printableSegments, !request.contains("\n "), !request.contains("\n\t"),
              request.replacingOccurrences(of: "\r\n", with: "").unicodeScalars.allSatisfy({
                  $0.value != 13 && $0.value != 10
              }) else {
            client.cancel(); return
        }
        guard lines.first == "CONNECT i.motiezw.com:443 HTTP/1.1" else { client.cancel(); return }
        var hosts: [String] = [], auths: [String] = []
        for line in lines.dropFirst() where !line.isEmpty {
            guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { client.cancel(); return }
            let rawName = line[..<colon]
            guard rawName.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "!#$%&'*+-.^_`|~".contains($0)) }) else {
                client.cancel(); return
            }
            let name = rawName.lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            guard name != "content-length" && name != "transfer-encoding" else { client.cancel(); return }
            if name == "host" { hosts.append(value) }
            if name == "proxy-authorization" { auths.append(value) }
        }
        guard hosts.count == 1 else { client.cancel(); return }
        let acceptedHostValues = ["i.motiezw.com:443", "i.motiezw.com"]
        guard acceptedHostValues.contains(hosts[0].lowercased()) else { client.cancel(); return }
        guard auths == ["Basic \(authorization)"] else {
            guard authenticationChallenges == 0 else { client.cancel(); return }
            client.send(content: Data("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Shinsou\"\r\nContent-Length: 0\r\n\r\n".utf8), completion: .contentProcessed { [weak self] error in
                if error == nil { self?.receiveConnect(client, Data(), authenticationChallenges: 1) } else { client.cancel() }
            })
            return
        }
        guard let address = approvedAddresses.first, let port = NWEndpoint.Port(rawValue: 443) else { client.cancel(); return }
        let upstreamParameters = NWParameters.tcp
        upstreamParameters.preferNoProxies = true
        let upstream = NWConnection(host: address, port: port, using: upstreamParameters)
        connections[ObjectIdentifier(upstream)] = upstream
        upstream.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                client.send(content: Data("HTTP/1.1 200 Connection Established\r\n\r\n".utf8), completion: .contentProcessed { error in
                    guard error == nil else { client.cancel(); upstream.cancel(); return }
                    self?.relay(client, upstream, 0)
                    self?.relay(upstream, client, 0)
                })
            case .failed:
                self?.connections.removeValue(forKey: ObjectIdentifier(upstream))
                client.cancel(); upstream.cancel()
            case .cancelled:
                self?.connections.removeValue(forKey: ObjectIdentifier(upstream))
                client.cancel(); upstream.cancel()
            default: break
            }
        }
        upstream.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 30) { client.cancel(); upstream.cancel() }
    }

    private func relay(_ source: NWConnection, _ destination: NWConnection, _ total: Int) {
        source.receive(minimumIncompleteLength: 1, maximumLength: 32 * 1_024) { [weak self] data, _, complete, error in
            guard let self, let data, !data.isEmpty, total + data.count <= reviewedTunnelByteMax,
                  error == nil else { source.cancel(); destination.cancel(); return }
            destination.send(content: data, completion: .contentProcessed { sendError in
                if complete || sendError != nil { source.cancel(); destination.cancel() }
                else { self.relay(source, destination, total + data.count) }
            })
        }
    }

    private static func isObviouslyPrivateAddress(_ value: String) -> Bool {
        if let address = IPv4Address(value) {
            let bytes = [UInt8](address.rawValue)
            return bytes[0] == 0 || bytes[0] == 10 || bytes[0] == 127 ||
                (bytes[0] == 169 && bytes[1] == 254) || (bytes[0] == 172 && (16...31).contains(bytes[1])) ||
                (bytes[0] == 192 && bytes[1] == 168) || bytes[0] >= 224
        }
        let lower = value.lowercased()
        return lower == "::" || lower == "::1" || lower.hasPrefix("fe8") || lower.hasPrefix("fe9") ||
            lower.hasPrefix("fea") || lower.hasPrefix("feb") || lower.hasPrefix("fc") || lower.hasPrefix("fd") ||
            lower.hasPrefix("::ffff:0.") || lower.hasPrefix("::ffff:10.") || lower.hasPrefix("::ffff:127.") ||
            lower.hasPrefix("::ffff:169.254.") || lower.hasPrefix("::ffff:192.168.")
    }
}

private enum BoundedLineError: Error {
    case tooLong
    case invalidUTF8
    case truncated
}

private final class BoundedLineReader {
    private let handle: FileHandle
    private var buffered = Data()
    private var reachedEOF = false

    init(_ handle: FileHandle) {
        self.handle = handle
    }

    func readLine(maxBytes: Int) throws -> String? {
        precondition(maxBytes > 0)
        while true {
            if let newline = buffered.firstIndex(of: 0x0a) {
                let byteCount = buffered.distance(from: buffered.startIndex, to: newline)
                guard byteCount <= maxBytes else { throw BoundedLineError.tooLong }
                var line = Data(buffered[..<newline])
                buffered.removeSubrange(...newline)
                if line.last == 0x0d { line.removeLast() }
                guard let decoded = String(data: line, encoding: .utf8) else {
                    throw BoundedLineError.invalidUTF8
                }
                return decoded
            }
            guard buffered.count <= maxBytes else { throw BoundedLineError.tooLong }
            if reachedEOF {
                if buffered.isEmpty { return nil }
                throw BoundedLineError.truncated
            }
            let remaining = maxBytes - buffered.count
            let capacity = min(4_096, remaining + 1)
            var bytes = [UInt8](repeating: 0, count: capacity)
            let count: Int = try bytes.withUnsafeMutableBytes { buffer in
                while true {
                    let result = Darwin.read(handle.fileDescriptor, buffer.baseAddress, capacity)
                    if result >= 0 { return result }
                    if errno != EINTR { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
                }
            }
            let chunk = Data(bytes.prefix(count))
            if chunk.isEmpty { reachedEOF = true } else { buffered.append(chunk) }
        }
    }
}

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
    let mode: String
    let url: String
    let sourceName: String
    let userAgent: String
    let cookies: [CookiePayload]
    let localStorageKeys: [String]
    let username: String?
    let password: String?
    let embeddedPolicy: String
    let allowedNavigationOrigins: [String]
    let allowedSubresourceOrigins: [String]
    let reviewedAddresses: [String]
    let proxyToken: String?

    private enum CodingKeys: String, CodingKey {
        case mode, url, sourceName, userAgent, cookies, localStorageKeys, username, password
        case embeddedPolicy, allowedNavigationOrigins, allowedSubresourceOrigins
        case reviewedAddresses, proxyToken
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        mode = try values.decodeIfPresent(String.self, forKey: .mode) ?? "challenge"
        url = try values.decode(String.self, forKey: .url)
        sourceName = try values.decode(String.self, forKey: .sourceName)
        userAgent = try values.decode(String.self, forKey: .userAgent)
        cookies = try values.decode([CookiePayload].self, forKey: .cookies)
        localStorageKeys = try values.decodeIfPresent([String].self, forKey: .localStorageKeys) ?? []
        username = try values.decodeIfPresent(String.self, forKey: .username)
        password = try values.decodeIfPresent(String.self, forKey: .password)
        embeddedPolicy = try values.decodeIfPresent(String.self, forKey: .embeddedPolicy) ?? "DENY"
        allowedNavigationOrigins = try values.decodeIfPresent([String].self, forKey: .allowedNavigationOrigins) ?? []
        allowedSubresourceOrigins = try values.decodeIfPresent([String].self, forKey: .allowedSubresourceOrigins) ?? []
        reviewedAddresses = try values.decodeIfPresent([String].self, forKey: .reviewedAddresses) ?? []
        proxyToken = try values.decodeIfPresent(String.self, forKey: .proxyToken)
    }
}

private struct HelperEvent: Codable {
    let type: String
    let message: String?
    let cookies: [CookiePayload]?
    let userAgent: String?
    let localStorage: [String: String]?
    let id: String?
    let value: String?

    static func simple(_ type: String) -> HelperEvent {
        HelperEvent(
            type: type,
            message: nil,
            cookies: nil,
            userAgent: nil,
            localStorage: nil,
            id: nil,
            value: nil
        )
    }

    static func error(_ message: String) -> HelperEvent {
        HelperEvent(
            type: "error",
            message: message,
            cookies: nil,
            userAgent: nil,
            localStorage: nil,
            id: nil,
            value: nil
        )
    }

    static func evaluated(_ id: String, value: String?) -> HelperEvent {
        HelperEvent(
            type: "evaluated",
            message: nil,
            cookies: nil,
            userAgent: nil,
            localStorage: nil,
            id: id,
            value: value
        )
    }

    static func evaluationError(_ id: String) -> HelperEvent {
        HelperEvent(
            type: "error",
            message: "The browser-session script failed.",
            cookies: nil,
            userAgent: nil,
            localStorage: nil,
            id: id,
            value: nil
        )
    }

    static func captured(
        _ cookies: [CookiePayload],
        userAgent: String,
        localStorage: [String: String]
    ) -> HelperEvent {
        HelperEvent(
            type: "cookies",
            message: nil,
            cookies: cookies,
            userAgent: userAgent,
            localStorage: localStorage,
            id: nil,
            value: nil
        )
    }

}

private struct BrowserSessionCommand: Codable {
    let type: String
    let id: String?
    let script: String?
}

private final class EventWriter {
    private let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        // Base64 contains slashes. Escaping them needlessly inflates a bounded binary result.
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return encoder
    }()
    private let queue = DispatchQueue(label: "dev.aluo.shinsoux.web-challenge.events")

    func send(_ event: HelperEvent) {
        queue.async {
            let bounded = self.isBounded(event)
                ? event
                : .error("The browser helper response exceeded its safe limit.")
            guard let data = try? self.encoder.encode(bounded), data.count <= helperEventMaxBytes else { return }
            FileHandle.standardOutput.write(data)
            FileHandle.standardOutput.write(Data([0x0a]))
        }
    }

    private func isBounded(_ event: HelperEvent) -> Bool {
        guard event.type.utf8.count <= 16,
              event.message.map({ $0.utf8.count <= 512 }) ?? true,
              event.id.map({ !$0.isEmpty && $0.utf8.count <= 128 }) ?? true,
              event.value.map({ $0.utf8.count <= evaluatedValueMaxBytes }) ?? true,
              event.userAgent.map({ $0.utf8.count <= 1_024 }) ?? true else { return false }
        if let cookies = event.cookies, !Self.cookiesAreBounded(cookies) { return false }
        if let storage = event.localStorage, !Self.storageIsBounded(storage) { return false }
        return true
    }

    private static func cookiesAreBounded(_ cookies: [CookiePayload]) -> Bool {
        guard cookies.count <= captureCookieMaxCount else { return false }
        var aggregate = 0
        for cookie in cookies {
            let sizes = [cookie.name.utf8.count, cookie.value.utf8.count, cookie.domain.utf8.count, cookie.path.utf8.count]
            guard !cookie.name.isEmpty, !cookie.value.isEmpty, !cookie.domain.isEmpty,
                  sizes[0] <= 256, sizes[1] <= 8_192, sizes[2] <= 255, sizes[3] <= 2_048 else { return false }
            aggregate += sizes.reduce(0, +)
            if aggregate > captureCookieAggregateMaxBytes { return false }
        }
        return true
    }

    private static func storageIsBounded(_ storage: [String: String]) -> Bool {
        guard storage.count <= captureStorageMaxCount else { return false }
        var aggregate = 0
        for (key, value) in storage {
            guard !key.isEmpty, key.utf8.count <= 64,
                  value.utf8.count <= captureStorageValueMaxBytes else { return false }
            aggregate += key.utf8.count + value.utf8.count
            if aggregate > captureStorageAggregateMaxBytes { return false }
        }
        return true
    }
}

private final class ChallengeController: NSObject, NSApplicationDelegate, WKNavigationDelegate, WKUIDelegate, NSWindowDelegate {
    private let launch: LaunchPayload
    private let writer: EventWriter
    private let origin: URL
    private let allowedNavigationOrigins: Set<String>
    private let allowedSubresourceOrigins: Set<String>
    private let commandReader: BoundedLineReader
    private let dataStore: WKWebsiteDataStore
    private var reviewedProxy: Any?
    private var webView: WKWebView?
    private var window: NSWindow?
    private var didReportFirstPage = false
    private var autoLoginInFlight = false
    private var didSubmitAutomaticLogin = false
    private var automaticLoginWatcherInstalled = false
    private var automaticLoginRecoveryAttempts = 0
    private var terminating = false

    init(
        launch: LaunchPayload,
        writer: EventWriter,
        origin: URL,
        allowedNavigationOrigins: Set<String>,
        allowedSubresourceOrigins: Set<String>,
        commandReader: BoundedLineReader
    ) {
        self.launch = launch
        self.writer = writer
        self.origin = origin
        self.allowedNavigationOrigins = allowedNavigationOrigins
        self.allowedSubresourceOrigins = allowedSubresourceOrigins
        self.commandReader = commandReader
        self.dataStore = .nonPersistent()
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        installContentRulesAndStart()
    }

    private func installContentRulesAndStart() {
        let rules = Self.contentRuleList(allowedOrigins: allowedSubresourceOrigins)
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "dev.aluo.shinsoux.web-challenge.\(rules.hashValue)",
            encodedContentRuleList: rules
        ) { [weak self] ruleList, error in
            guard let self else { return }
            guard let ruleList, error == nil else {
                self.writer.send(.error("The isolated browser network policy could not be installed."))
                self.terminate()
                return
            }
            let configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = self.dataStore
            if self.launch.mode == "reviewedImage" {
                guard #available(macOS 14.0, *), let token = self.launch.proxyToken else {
                    self.writer.send(.error("Reviewed image transport is unavailable.")); self.terminate(); return
                }
                do {
                    let proxyServer = try ReviewedConnectProxy(addresses: self.launch.reviewedAddresses, token: token)
                    let port = try proxyServer.start()
                    var proxy = ProxyConfiguration(
                        httpCONNECTProxy: .hostPort(
                            host: .ipv4(.loopback),
                            port: NWEndpoint.Port(rawValue: port)!
                        ),
                        tlsOptions: nil
                    )
                    proxy.allowFailover = false
                    proxy.applyCredential(username: "shinsou", password: token)
                    self.dataStore.proxyConfigurations = [proxy]
                    self.reviewedProxy = proxyServer
                } catch {
                    self.writer.send(.error("Reviewed image proxy could not start.")); self.terminate(); return
                }
            }
            configuration.defaultWebpagePreferences.allowsContentJavaScript = true
            configuration.userContentController.add(ruleList)
            self.createWindow(configuration: configuration)
            self.seedCookiesAndLoad()
            self.readCommands()
        }
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

    private func createWindow(configuration suppliedConfiguration: WKWebViewConfiguration? = nil) {
        let configuration = suppliedConfiguration ?? WKWebViewConfiguration()
        if suppliedConfiguration == nil {
            configuration.websiteDataStore = dataStore
            configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        }

        let browser = WKWebView(frame: .zero, configuration: configuration)
        browser.navigationDelegate = self
        browser.uiDelegate = self
        // Cloudflare detects a custom UA even when its text matches desktop Safari and keeps the
        // challenge in a loop. Use WKWebView's genuine UA; capture it with cookies so the source's
        // subsequent HTTP requests can use the exact browser-bound value.
        browser.customUserAgent = nil
        browser.allowsMagnification = true
        webView = browser

        if launch.mode == "browserSession" || launch.mode == "reviewedImage" {
            // Browser-session transport is deliberately headless. Keeping the WKWebView attached
            // to a tiny hidden window gives WebKit a normal page lifecycle and Safari's native
            // networking identity without exposing an interactive browser surface.
            NSApp.setActivationPolicy(.prohibited)
            let browserWindow = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 1, height: 1),
                styleMask: [.borderless],
                backing: .buffered,
                defer: false
            )
            browserWindow.contentView = browser
            browserWindow.setFrameOrigin(NSPoint(x: -10_000, y: -10_000))
            window = browserWindow
            writer.send(.simple("ready"))
            return
        }

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
            if self.launch.mode == "browserSession" || self.launch.mode == "reviewedImage" {
                let document = self.launch.mode == "reviewedImage"
                    ? "<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; connect-src https://i.motiezw.com\">"
                    : "<!doctype html><meta charset=\"utf-8\"><title>Shinsou browser session</title>"
                self.webView?.loadHTMLString(
                    document,
                    baseURL: self.origin
                )
            } else {
                self.webView?.load(URLRequest(url: self.origin, cachePolicy: .reloadIgnoringLocalCacheData))
            }
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
            guard let self else { return }
            do {
                let limit: Int
                switch self.launch.mode {
                case "browserSession": limit = browserCommandLineMaxBytes
                case "reviewedImage": limit = reviewedImageCommandMaxBytes
                default: limit = challengeCommandMaxBytes
                }
                while let line = try self.commandReader.readLine(maxBytes: limit) {
                    DispatchQueue.main.async { [weak self] in
                        guard let self else { return }
                        if self.launch.mode == "browserSession" || self.launch.mode == "reviewedImage" {
                            self.handleBrowserSessionCommand(line)
                        } else {
                            switch line {
                            case "capture": self.captureCookies()
                            case "close": self.terminate()
                            default: self.writer.send(.error("The browser helper received an unsupported command."))
                            }
                        }
                    }
                }
            } catch {
                self.writer.send(.error("The browser helper command stream was invalid."))
            }
            DispatchQueue.main.async { [weak self] in self?.terminate() }
        }
    }

    private func handleBrowserSessionCommand(_ line: String) {
        guard let data = line.data(using: .utf8),
              let command = try? JSONDecoder().decode(BrowserSessionCommand.self, from: data) else {
            writer.send(.error("The browser-session command was invalid."))
            return
        }
        switch command.type {
        case "close":
            terminate()
        case "evaluate":
            guard let id = command.id, !id.isEmpty, id.count <= 128,
                  let script = command.script, script.utf8.count <= 4_194_304,
                  let browser = webView else {
                writer.send(.error("The browser-session command was invalid."))
                return
            }
            let evaluationLimit = launch.mode == "reviewedImage"
                ? evaluatedValueMaxBytes : browserSessionEvaluatedValueMaxBytes
            let boundedScript = Self.boundedEvaluationScript(script, maximumBytes: evaluationLimit)
            browser.evaluateJavaScript(boundedScript) { [weak self] value, error in
                guard let self else { return }
                if error != nil {
                    self.writer.send(.evaluationError(id))
                } else {
                    guard value == nil || value is String else {
                        self.writer.send(.evaluationError(id))
                        return
                    }
                    let rendered = value as? String
                    let resultLimit = self.launch.mode == "reviewedImage"
                        ? evaluatedValueMaxBytes : browserSessionEvaluatedValueMaxBytes
                    guard rendered.map({ $0.utf8.count <= resultLimit }) ?? true else {
                        self.writer.send(.evaluationError(id))
                        return
                    }
                    self.writer.send(.evaluated(id, value: rendered))
                }
            }
        default:
            writer.send(.error("The browser helper received an unsupported command."))
        }
    }

    private func captureCookies() {
        guard let browser = webView else {
            writer.send(.error("The browser session is unavailable."))
            return
        }
        guard browser.url.map(isSameOrigin) == true else {
            writer.send(.error("Return to the source website before importing its browser session."))
            return
        }
        let storageScript = Self.localStorageCaptureScript(keys: launch.localStorageKeys)
        browser.evaluateJavaScript(storageScript) { [weak self] storageValue, storageError in
            guard let self else { return }
            guard storageError == nil,
                  let storageJson = storageValue as? String,
                  storageJson.utf8.count <= 512 * 1_024,
                  let storageData = storageJson.data(using: .utf8),
                  let storage = try? JSONDecoder().decode([String: String].self, from: storageData),
                  self.validatedStorage(storage) != nil else {
                self.writer.send(.error("The browser session data could not be read."))
                return
            }
            browser.evaluateJavaScript("navigator.userAgent") { value, _ in
                let userAgent = (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                guard !userAgent.isEmpty, userAgent.count <= 512, userAgent.utf8.count <= 1_024,
                      !userAgent.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else {
                    self.writer.send(.error("The browser User-Agent could not be read."))
                    return
                }
                self.dataStore.httpCookieStore.getAllCookies { cookies in
                    guard let payloads = self.validatedCookies(cookies),
                          let boundedStorage = self.validatedStorage(storage) else {
                        self.writer.send(.error("The browser session data exceeded its safe limit."))
                        return
                    }
                    self.writer.send(.captured(payloads, userAgent: userAgent, localStorage: boundedStorage))
                }
            }
        }
    }

    private static func localStorageCaptureScript(keys: [String]) -> String {
        let allowed = Array(Set(keys.filter {
            !$0.isEmpty && $0.count <= 64 && $0.allSatisfy { $0.isLetter || $0.isNumber || ".-_".contains($0) }
        })).prefix(8)
        let encoded = (try? JSONSerialization.data(withJSONObject: Array(allowed)))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        return """
        (() => {
          const values = {};
          let aggregateBytes = 0;
          const boundedUtf8Length = (text, maximum) => {
            let bytes = 0;
            for (let index = 0; index < text.length; index += 1) {
              const code = text.charCodeAt(index);
              let width;
              if (code < 0x80) width = 1;
              else if (code < 0x800) width = 2;
              else if (code >= 0xD800 && code <= 0xDBFF && index + 1 < text.length &&
                  text.charCodeAt(index + 1) >= 0xDC00 && text.charCodeAt(index + 1) <= 0xDFFF) {
                width = 4;
                index += 1;
              } else width = 3;
              if (bytes > maximum - width) return null;
              bytes += width;
            }
            return bytes;
          };
          for (const key of \(encoded)) {
            const value = localStorage.getItem(key);
            if (value !== null) {
              const rendered = String(value);
              const valueBytes = boundedUtf8Length(rendered, 16384);
              if (valueBytes === null || aggregateBytes > 32768 - valueBytes) {
                throw new Error("storage data too large");
              }
              aggregateBytes += valueBytes;
              values[key] = rendered;
            }
          }
          return JSON.stringify(values);
        })()
        """
    }

    private static func boundedEvaluationScript(_ script: String, maximumBytes: Int) -> String {
        """
        (() => {
          const result = (\(script));
          if (result === null || result === undefined) return null;
          const rendered = String(result);
          let bytes = 0;
          for (let index = 0; index < rendered.length; index += 1) {
            const code = rendered.charCodeAt(index);
            let width;
            if (code < 0x80) width = 1;
            else if (code < 0x800) width = 2;
            else if (code >= 0xD800 && code <= 0xDBFF && index + 1 < rendered.length &&
                rendered.charCodeAt(index + 1) >= 0xDC00 && rendered.charCodeAt(index + 1) <= 0xDFFF) {
              width = 4;
              index += 1;
            } else width = 3;
            if (bytes > \(maximumBytes) - width) throw new Error("evaluation result too large");
            bytes += width;
          }
          return rendered;
        })()
        """
    }

    private func validatedStorage(_ storage: [String: String]) -> [String: String]? {
        let allowed = Set(launch.localStorageKeys.filter {
            !$0.isEmpty && $0.utf8.count <= 64 && $0.allSatisfy { $0.isLetter || $0.isNumber || ".-_".contains($0) }
        }.prefix(captureStorageMaxCount))
        guard storage.count <= captureStorageMaxCount else { return nil }
        var aggregate = 0
        for (key, value) in storage {
            guard allowed.contains(key), value.utf8.count <= captureStorageValueMaxBytes else { return nil }
            aggregate += key.utf8.count + value.utf8.count
            if aggregate > captureStorageAggregateMaxBytes { return nil }
        }
        return storage
    }

    private func validatedCookies(_ cookies: [HTTPCookie]) -> [CookiePayload]? {
        guard cookies.count <= captureCookieMaxCount else { return nil }
        var payloads: [CookiePayload] = []
        payloads.reserveCapacity(cookies.count)
        var aggregate = 0
        for cookie in cookies {
            guard let payload = payload(for: cookie) else { return nil }
            let sizes = [payload.name.utf8.count, payload.value.utf8.count, payload.domain.utf8.count, payload.path.utf8.count]
            guard sizes[0] <= 256, sizes[1] <= 8_192, sizes[2] <= 255, sizes[3] <= 2_048 else { return nil }
            aggregate += sizes.reduce(0, +)
            if aggregate > captureCookieAggregateMaxBytes { return nil }
            payloads.append(payload)
        }
        return payloads
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
        if #available(macOS 14.0, *), let proxy = reviewedProxy as? ReviewedConnectProxy {
            proxy.cancel()
        }
        reviewedProxy = nil
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
        if launch.mode != "browserSession" {
            attemptAutomaticLogin(in: webView)
        }
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
            arguments: [
                "username": username,
                "password": password,
                "installWatcher": !automaticLoginWatcherInstalled,
            ],
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
                    } else if value as? String == "watching" {
                        self.automaticLoginWatcherInstalled = true
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
        canonicalOrigin(candidate).map(allowedNavigationOrigins.contains) == true
    }

    private func effectivePort(_ url: URL) -> Int? {
        if let port = url.port { return port }
        switch url.scheme?.lowercased() {
        case "http": return 80
        case "https": return 443
        default: return nil
        }
    }

    private func canonicalOrigin(_ url: URL) -> String? {
        guard url.user == nil, url.password == nil,
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(), !host.isEmpty,
              let port = effectivePort(url) else { return nil }
        return port == 443 ? "https://\(host)" : "https://\(host):\(port)"
    }

    private static func contentRuleList(allowedOrigins: Set<String>) -> String {
        var rules: [[String: Any]] = [[
            "trigger": ["url-filter": "^[A-Za-z][A-Za-z0-9+.-]*://"],
            "action": ["type": "block"],
        ]]
        for origin in allowedOrigins.sorted() {
            guard let url = URL(string: origin), let host = url.host?.lowercased() else { continue }
            let escapedHost = NSRegularExpression.escapedPattern(for: host)
            let port = url.port.map { ":\($0)" } ?? ""
            rules.append([
                "trigger": ["url-filter": "^https://\(escapedHost)\(port)[/?#]"],
                "action": ["type": "ignore-previous-rules"],
            ])
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rules),
              let encoded = String(data: data, encoding: .utf8) else { return "[]" }
        return encoded
    }

    private static let automaticLoginScript = #"""
    const suppliedUsername = String(username ?? "");
    const suppliedPassword = String(password ?? "");
    if (!suppliedUsername.trim() || !suppliedPassword) return "missing-credentials";
    const attemptKey = "__shinsouAutomaticLoginSubmitted";
    if (sessionStorage.getItem(attemptKey) === "1") return "already-submitted";

    const visible = (element) => !!element && element.getClientRects().length > 0;
    const submit = () => {
    const passwordInput = Array.from(document.querySelectorAll('input[type="password"]'))
        .find((input) => visible(input) && !input.disabled && !input.readOnly);
    if (!passwordInput) return false;

    const form = passwordInput.form || passwordInput.closest("form");
    if (!form) return false;

    const action = new URL(form.getAttribute("action") || location.href, location.href);
    if (!/^https?:$/.test(action.protocol) || action.origin !== location.origin) {
        return false;
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
        const candidate = Array.from(form.querySelectorAll(selector)).find((input) =>
            visible(input) && input !== passwordInput && !input.disabled && !input.readOnly &&
            String(input.type).toLowerCase() !== "hidden");
        if (candidate) {
            usernameInput = candidate;
            break;
        }
    }
    if (!usernameInput) return false;

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
    return true;
    };
    if (submit()) return "submitted";
    if (!Boolean(installWatcher)) return "waiting";
    const observer = new MutationObserver(() => {
        if (submit()) observer.disconnect();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
    const labels = new Set(["login", "log in", "sign in", "登入", "登录", "會員登入", "会员登录"]);
    const opener = Array.from(document.querySelectorAll('button, a, [role="button"]')).find((element) => {
        if (!visible(element) || element.disabled) return false;
        const label = String(element.getAttribute("aria-label") || element.getAttribute("title") ||
            element.textContent || "").trim().toLowerCase();
        if (!labels.has(label)) return false;
        if (element.tagName === "A" && element.href) {
            try { if (new URL(element.href, location.href).origin !== location.origin) return false; }
            catch (_) { return false; }
        }
        return true;
    });
    if (opener) opener.click();
    return "watching";
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
            let allowedOrigins = navigationAction.targetFrame?.isMainFrame == false
                ? allowedSubresourceOrigins
                : allowedNavigationOrigins
            if canonicalOrigin(url).map(allowedOrigins.contains) == true {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
                reportBlockedNavigation(scheme: scheme)
            }
        case "about":
            // Cloudflare creates short-lived about:blank/about:srcdoc frames (occasionally with a
            // fragment) while collecting browser proof. All about: destinations remain internal.
            if navigationAction.targetFrame?.isMainFrame == false,
               url.absoluteString == "about:blank" || url.absoluteString.hasPrefix("about:blank#") ||
                    url.absoluteString == "about:srcdoc" {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
                reportBlockedNavigation(scheme: scheme)
            }
        case "blob":
            let raw = url.absoluteString
            let creator = raw.hasPrefix("blob:") ? String(raw.dropFirst(5)) : ""
            if navigationAction.targetFrame?.isMainFrame == false,
               URL(string: creator).flatMap(canonicalOrigin).map(allowedSubresourceOrigins.contains) == true {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
                reportBlockedNavigation(scheme: scheme)
            }
        case "data":
            if navigationAction.targetFrame?.isMainFrame == false {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
                reportBlockedNavigation(scheme: scheme)
            }
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
        if launch.mode == "reviewedImage" { return nil }
        if navigationAction.targetFrame == nil,
           let url = navigationAction.request.url,
           canonicalOrigin(url).map(allowedNavigationOrigins.contains) == true {
            webView.load(URLRequest(url: url))
        } else if navigationAction.targetFrame == nil {
            reportBlockedNavigation(scheme: navigationAction.request.url?.scheme)
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
private let stdinReader = BoundedLineReader(FileHandle.standardInput)
guard let launchLine = try? stdinReader.readLine(maxBytes: launchLineMaxBytes),
      let launchData = launchLine.data(using: .utf8),
      let launch = try? JSONDecoder().decode(LaunchPayload.self, from: launchData) else {
    fail(writer, "The browser helper could not read its launch request.")
}
guard let origin = URL(string: launch.url),
      let scheme = origin.scheme?.lowercased(),
      scheme == "https",
      origin.host != nil else {
    fail(writer, "The source URL is invalid.")
}

private func canonicalReviewedOrigin(_ value: String) -> String? {
    guard let url = URL(string: value), url.user == nil, url.password == nil,
          url.scheme?.lowercased() == "https", let host = url.host?.lowercased(), !host.isEmpty,
          url.path.isEmpty || url.path == "/", url.query == nil, url.fragment == nil else { return nil }
    let port = url.port ?? 443
    return port == 443 ? "https://\(host)" : "https://\(host):\(port)"
}

private let navigationOrigins = Set(launch.allowedNavigationOrigins.compactMap(canonicalReviewedOrigin))
private let subresourceOrigins = Set(launch.allowedSubresourceOrigins.compactMap(canonicalReviewedOrigin))
private let initialOrigin: String? = {
    guard let url = URL(string: launch.url), url.user == nil, url.password == nil,
          url.scheme?.lowercased() == "https", let host = url.host?.lowercased(), !host.isEmpty else { return nil }
    let port = url.port ?? 443
    return port == 443 ? "https://\(host)" : "https://\(host):\(port)"
}()
let browserSessionGrantIsValid = launch.mode == "browserSession" &&
    !navigationOrigins.isEmpty &&
    navigationOrigins.isSubset(of: subresourceOrigins) &&
    initialOrigin.map(navigationOrigins.contains) == true
let reviewedImageGrantIsValid = launch.mode == "reviewedImage" &&
    navigationOrigins == ["https://i.motiezw.com"] &&
    subresourceOrigins == navigationOrigins &&
    initialOrigin == "https://i.motiezw.com" &&
    launch.cookies.isEmpty && launch.userAgent.isEmpty && launch.localStorageKeys.isEmpty &&
    launch.username == nil && launch.password == nil
let interactiveGrantIsValid = launch.mode == "challenge" &&
    launch.embeddedPolicy == "ALLOW_REVIEWED_ORIGINS" &&
    !navigationOrigins.isEmpty &&
    navigationOrigins.isSubset(of: subresourceOrigins) &&
    initialOrigin.map(navigationOrigins.contains) == true
guard browserSessionGrantIsValid || interactiveGrantIsValid || reviewedImageGrantIsValid else {
    fail(writer, "Embedded browser access is not authorized for this source.")
}

private let application = NSApplication.shared
guard let initialOrigin else {
    fail(writer, "The source URL is invalid.")
}
private let controller = ChallengeController(
    launch: launch,
    writer: writer,
    origin: origin,
    allowedNavigationOrigins: navigationOrigins,
    allowedSubresourceOrigins: subresourceOrigins,
    commandReader: stdinReader
)
application.delegate = controller
application.run()
