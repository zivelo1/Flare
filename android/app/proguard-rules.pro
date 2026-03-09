# Flare ProGuard Rules

# Keep Bluetooth GATT callback classes
-keep class com.flare.mesh.ble.** { *; }

# Keep data models for serialization
-keep class com.flare.mesh.data.model.** { *; }

# Timber logging - remove in release
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
}
