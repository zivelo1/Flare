import SwiftUI
import CryptoKit

/// Generates deterministic visual identicons from device IDs.
///
/// Uses SHA-256 hash of the device ID to derive:
/// - A background color from a curated palette
/// - A foreground pattern (5x5 symmetric grid)
/// - Initials for text-based fallback
///
/// The same device ID always produces the same visual, making it easy
/// for users to recognize contacts at a glance.
enum IdenticonGenerator {

    /// Curated palette — perceptually distinct, accessible on both light and dark backgrounds.
    private static let palette: [(background: Color, foreground: Color)] = [
        (Color(red: 0.898, green: 0.451, blue: 0.451), .white), // Red
        (Color(red: 0.506, green: 0.780, blue: 0.518), .white), // Green
        (Color(red: 0.392, green: 0.710, blue: 0.965), .white), // Blue
        (Color(red: 1.000, green: 0.718, blue: 0.302), .white), // Orange
        (Color(red: 0.729, green: 0.408, blue: 0.784), .white), // Purple
        (Color(red: 0.302, green: 0.714, blue: 0.675), .white), // Teal
        (Color(red: 0.941, green: 0.384, blue: 0.573), .white), // Pink
        (Color(red: 0.682, green: 0.835, blue: 0.506), Color(red: 0.2, green: 0.2, blue: 0.2)), // Light Green
        (Color(red: 0.475, green: 0.525, blue: 0.796), .white), // Indigo
        (Color(red: 1.000, green: 0.835, blue: 0.310), Color(red: 0.2, green: 0.2, blue: 0.2)), // Amber
        (Color(red: 0.310, green: 0.765, blue: 0.969), .white), // Light Blue
        (Color(red: 0.878, green: 0.878, blue: 0.878), Color(red: 0.38, green: 0.38, blue: 0.38)), // Grey
    ]

    /// Derives a deterministic hash from a device ID.
    private static func hashDeviceId(_ deviceId: String) -> [UInt8] {
        let data = Data(deviceId.utf8)
        let digest = SHA256.hash(data: data)
        return Array(digest)
    }

    /// Returns the background and foreground colors for a device ID.
    static func getColors(deviceId: String) -> (background: Color, foreground: Color) {
        let hash = hashDeviceId(deviceId)
        let index = Int(hash[0]) % palette.count
        return palette[index]
    }

    /// Returns a 5x5 boolean grid representing the identicon pattern.
    /// The grid is horizontally symmetric (mirrored around the center column).
    static func getPattern(deviceId: String) -> [[Bool]] {
        let gridSize = 5
        let halfSize = 3 // ceil(5/2)
        let hash = hashDeviceId(deviceId)
        var grid = Array(repeating: Array(repeating: false, count: gridSize), count: gridSize)

        for row in 0..<gridSize {
            for col in 0..<halfSize {
                let byteIndex = (row * halfSize + col + 1) % hash.count
                let filled = hash[byteIndex] > 127
                grid[row][col] = filled
                grid[row][gridSize - 1 - col] = filled
            }
        }

        return grid
    }

    /// Returns 1-2 character initials from a display name or device ID.
    static func getInitials(displayName: String?, deviceId: String) -> String {
        if let name = displayName, !name.trimmingCharacters(in: .whitespaces).isEmpty {
            let parts = name.trimmingCharacters(in: .whitespaces)
                .components(separatedBy: .whitespaces)
                .filter { !$0.isEmpty }
            if parts.count >= 2 {
                return "\(parts[0].prefix(1).uppercased())\(parts[1].prefix(1).uppercased())"
            } else if let first = parts.first {
                return first.prefix(1).uppercased()
            }
        }
        return deviceId.prefix(2).uppercased()
    }
}
