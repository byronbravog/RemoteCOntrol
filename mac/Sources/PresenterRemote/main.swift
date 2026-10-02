import AppKit
import CoreGraphics
import Darwin
import Foundation

private let discoveryPort: UInt16 = 50001
private let tcpPort: UInt16 = 5001
private let pin = String(format: "%04d", Int.random(in: 1000...9999))

private enum PresentationCommand: String {
    case next = "NEXT"
    case previous = "PREVIOUS"
    case start = "START"
    case black = "BLACK"
    case end = "END"

    var keyCode: CGKeyCode {
        switch self {
        case .next: return 124
        case .previous: return 123
        case .start: return 96
        case .black: return 11
        case .end: return 53
        }
    }
}

private final class PresentationServer {
    private var tcpSocket: Int32 = -1
    private var udpSocket: Int32 = -1
    private let queue = DispatchQueue(label: "presenter.remote.server", attributes: .concurrent)
    private let stateQueue = DispatchQueue(label: "presenter.remote.state")
    private var clients = 0

    var statusChanged: (() -> Void)?
    var connectionCount: Int { stateQueue.sync { clients } }
    var localIP: String { localIPAddress() }

    func start() throws {
        tcpSocket = try makeSocket(type: SOCK_STREAM, port: tcpPort)
        udpSocket = try makeSocket(type: SOCK_DGRAM, port: discoveryPort)
        queue.async { [weak self] in self?.acceptClients() }
        queue.async { [weak self] in self?.serveDiscovery() }
    }

    func stop() {
        if tcpSocket >= 0 { close(tcpSocket); tcpSocket = -1 }
        if udpSocket >= 0 { close(udpSocket); udpSocket = -1 }
    }

    private func makeSocket(type: Int32, port: UInt16) throws -> Int32 {
        let socketFD = Darwin.socket(AF_INET, type, 0)
        guard socketFD >= 0 else { throw POSIXError(.init(rawValue: errno)!) }

        var reuse: Int32 = 1
        setsockopt(socketFD, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr = in_addr(s_addr: INADDR_ANY)

        let result = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(socketFD, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard result == 0 else {
            close(socketFD)
            throw POSIXError(.init(rawValue: errno)!)
        }
        if type == SOCK_STREAM { listen(socketFD, 5) }
        return socketFD
    }

    private func acceptClients() {
        while tcpSocket >= 0 {
            let client = Darwin.accept(tcpSocket, nil, nil)
            guard client >= 0 else { continue }
            stateQueue.sync { clients += 1 }
            statusChanged?()
            queue.async { [weak self] in
                self?.handle(client)
                close(client)
                self?.stateQueue.sync { self?.clients -= 1 }
                self?.statusChanged?()
            }
        }
    }

    private func handle(_ client: Int32) {
        guard send("PIN:\(pin)\n", to: client), let pairing = readLine(from: client),
              pairing.hasPrefix("PAIR "), pairing.dropFirst(5) == pin else {
            _ = send("AUTH_FAILED\n", to: client)
            return
        }
        guard send("AUTHORIZED\n", to: client) else { return }

        while let line = readLine(from: client) {
            guard let command = PresentationCommand(rawValue: line) else {
                _ = send("ERR:COMMAND\n", to: client)
                continue
            }
            postKey(command.keyCode)
            _ = send("OK:\(command.rawValue)\n", to: client)
        }
    }

    private func serveDiscovery() {
        var buffer = [UInt8](repeating: 0, count: 1024)
        while udpSocket >= 0 {
            var address = sockaddr_in()
            var addressLength = socklen_t(MemoryLayout<sockaddr_in>.size)
            let count = withUnsafeMutablePointer(to: &address) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(udpSocket, &buffer, buffer.count, 0, $0, &addressLength)
                }
            }
            guard count > 0,
                  String(decoding: buffer.prefix(count), as: UTF8.self)
                    .trimmingCharacters(in: .whitespacesAndNewlines) == "DISCOVER" else { continue }
            let response = Array("HELLO|\(localIPAddress())|\(pin)".utf8)
            response.withUnsafeBytes { bytes in
                withUnsafePointer(to: &address) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        _ = sendto(udpSocket, bytes.baseAddress, response.count, 0, $0, addressLength)
                    }
                }
            }
        }
    }

    private func readLine(from socket: Int32) -> String? {
        var bytes: [UInt8] = []
        var byte: UInt8 = 0
        while recv(socket, &byte, 1, 0) == 1 {
            if byte == 10 { return String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines) }
            bytes.append(byte)
            if bytes.count > 4096 { return nil }
        }
        return bytes.isEmpty ? nil : String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func send(_ text: String, to socket: Int32) -> Bool {
        let bytes = Array(text.utf8)
        return bytes.withUnsafeBytes { Darwin.send(socket, $0.baseAddress, bytes.count, 0) == bytes.count }
    }

    private func postKey(_ keyCode: CGKeyCode) {
        let source = CGEventSource(stateID: .hidSystemState)
        CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: true)?.post(tap: .cghidEventTap)
        CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: false)?.post(tap: .cghidEventTap)
    }

    private func localIPAddress() -> String {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return "127.0.0.1" }
        defer { freeifaddrs(first) }

        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let item = current {
            let interface = item.pointee
            if let address = interface.ifa_addr,
               address.pointee.sa_family == UInt8(AF_INET),
               let name = interface.ifa_name,
               String(cString: name).hasPrefix("en") {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
                return String(cString: host)
            }
            current = interface.ifa_next
        }
        return "127.0.0.1"
    }
}

private final class AppDelegate: NSObject, NSApplicationDelegate {
    private let server = PresentationServer()
    private var statusItem: NSStatusItem!
    private var statusMenuItem: NSMenuItem!
    private var ipMenuItem: NSMenuItem!

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "play.rectangle.fill", accessibilityDescription: "Control Presentación")
        statusItem.button?.image?.isTemplate = true
        statusItem.button?.title = "PR"
        statusItem.menu = makeMenu()
        server.statusChanged = { [weak self] in DispatchQueue.main.async { self?.refreshMenu() } }
        do { try server.start(); refreshMenu() } catch { statusMenuItem.title = "Error: \(error.localizedDescription)" }
    }

    private func makeMenu() -> NSMenu {
        let menu = NSMenu()
        menu.addItem(withTitle: "Control Presentación", action: nil, keyEquivalent: "")
        menu.addItem(.separator())
        statusMenuItem = menu.addItem(withTitle: "Iniciando servidor...", action: nil, keyEquivalent: "")
        ipMenuItem = menu.addItem(withTitle: "IP: buscando...", action: nil, keyEquivalent: "")
        menu.addItem(withTitle: "PIN: \(pin)", action: nil, keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Abrir permisos de Accesibilidad", action: #selector(openAccessibilitySettings), keyEquivalent: "")
        menu.addItem(withTitle: "Salir", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        return menu
    }

    @objc private func openAccessibilitySettings() {
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }

    private func refreshMenu() {
        statusMenuItem?.title = "Clientes conectados: \(server.connectionCount)"
        ipMenuItem?.title = "IP: \(server.localIP)"
    }
    func applicationWillTerminate(_ notification: Notification) { server.stop() }
}

let application = NSApplication.shared
private let delegate = AppDelegate()
application.delegate = delegate
application.run()