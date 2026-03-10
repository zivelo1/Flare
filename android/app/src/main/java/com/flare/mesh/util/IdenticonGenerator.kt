package com.flare.mesh.util

import androidx.compose.ui.graphics.Color
import java.security.MessageDigest

/**
 * Generates deterministic visual identicons from device IDs.
 *
 * Uses SHA-256 hash of the device ID to derive:
 * - A background color from a curated palette
 * - A foreground pattern (5x5 symmetric grid)
 * - Initials for text-based fallback
 *
 * The same device ID always produces the same visual, making it easy
 * for users to recognize contacts at a glance.
 */
object IdenticonGenerator {

    /**
     * Curated palette — perceptually distinct, accessible on both light and dark backgrounds.
     * Each pair is (background, foreground) designed for sufficient contrast.
     */
    private val PALETTE = listOf(
        Pair(Color(0xFFE57373), Color(0xFFFFFFFF)), // Red
        Pair(Color(0xFF81C784), Color(0xFFFFFFFF)), // Green
        Pair(Color(0xFF64B5F6), Color(0xFFFFFFFF)), // Blue
        Pair(Color(0xFFFFB74D), Color(0xFFFFFFFF)), // Orange
        Pair(Color(0xFFBA68C8), Color(0xFFFFFFFF)), // Purple
        Pair(Color(0xFF4DB6AC), Color(0xFFFFFFFF)), // Teal
        Pair(Color(0xFFF06292), Color(0xFFFFFFFF)), // Pink
        Pair(Color(0xFFAED581), Color(0xFF333333)), // Light Green
        Pair(Color(0xFF7986CB), Color(0xFFFFFFFF)), // Indigo
        Pair(Color(0xFFFFD54F), Color(0xFF333333)), // Amber
        Pair(Color(0xFF4FC3F7), Color(0xFFFFFFFF)), // Light Blue
        Pair(Color(0xFFE0E0E0), Color(0xFF616161)), // Grey
    )

    /** Grid dimensions — 5x5 with horizontal symmetry. */
    private const val GRID_SIZE = 5
    private const val HALF_SIZE = 3 // ceil(5/2) — columns we actually generate

    /**
     * Derives a deterministic hash from a device ID.
     */
    private fun hashDeviceId(deviceId: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(deviceId.toByteArray(Charsets.UTF_8))
    }

    /**
     * Returns the background and foreground colors for a device ID.
     */
    fun getColors(deviceId: String): Pair<Color, Color> {
        val hash = hashDeviceId(deviceId)
        val index = (hash[0].toInt() and 0xFF) % PALETTE.size
        return PALETTE[index]
    }

    /**
     * Returns a 5x5 boolean grid representing the identicon pattern.
     * The grid is horizontally symmetric (mirrored around the center column).
     */
    fun getPattern(deviceId: String): Array<BooleanArray> {
        val hash = hashDeviceId(deviceId)
        val grid = Array(GRID_SIZE) { BooleanArray(GRID_SIZE) }

        for (row in 0 until GRID_SIZE) {
            for (col in 0 until HALF_SIZE) {
                // Use different hash bytes for each cell
                val byteIndex = (row * HALF_SIZE + col + 1) % hash.size
                val filled = (hash[byteIndex].toInt() and 0xFF) > 127
                grid[row][col] = filled
                grid[row][GRID_SIZE - 1 - col] = filled // Mirror
            }
        }

        return grid
    }

    /**
     * Returns 1-2 character initials from a display name or device ID.
     */
    fun getInitials(displayName: String?, deviceId: String): String {
        if (!displayName.isNullOrBlank()) {
            val parts = displayName.trim().split("\\s+".toRegex())
            return when {
                parts.size >= 2 -> "${parts[0].first().uppercase()}${parts[1].first().uppercase()}"
                parts.isNotEmpty() -> parts[0].first().uppercase()
                else -> "?"
            }
        }
        // Fallback: first two hex chars of device ID
        return deviceId.take(2).uppercase()
    }
}
