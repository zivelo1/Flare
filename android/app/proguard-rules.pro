# Flare ProGuard Rules

# ── JNA (required by UniFFI Rust bindings) ──────────────────────────
# JNA uses reflection to invoke native methods. Obfuscation breaks FFI calls.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ── UniFFI generated bindings ───────────────────────────────────────
# The generated Kotlin bindings use JNA interfaces and callback classes
# that must not be renamed or removed.
-keep class uniffi.flare_core.** { *; }
-keep interface uniffi.flare_core.** { *; }

# ── Bluetooth GATT callbacks ───────────────────────────────────────
# Android BLE callbacks are invoked by the system via reflection.
-keep class com.flare.mesh.ble.** { *; }

# ── Data models ─────────────────────────────────────────────────────
-keep class com.flare.mesh.data.model.** { *; }

# ── Timber logging — strip debug/verbose in release ─────────────────
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
}
