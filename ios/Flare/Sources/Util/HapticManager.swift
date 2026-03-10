import UIKit

enum HapticManager {

    private static let mediumImpact = UIImpactFeedbackGenerator(style: .medium)
    private static let lightImpact = UIImpactFeedbackGenerator(style: .light)
    private static let notification = UINotificationFeedbackGenerator()

    static func messageSent() {
        mediumImpact.impactOccurred()
    }

    static func messageReceived() {
        notification.notificationOccurred(.success)
    }

    static func buttonTap() {
        lightImpact.impactOccurred()
    }
}
