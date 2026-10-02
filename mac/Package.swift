// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PresenterRemote",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "PresenterRemote", targets: ["PresenterRemote"])],
    targets: [.executableTarget(name: "PresenterRemote")]
)