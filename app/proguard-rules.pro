# --- AirPlay protocol (vendored java-airplay) ---
-keep class com.github.serezhka.airplay.protocol.** { *; }
# FairPlay lookup tables are loaded as Java resources next to SapTables.
-keepclassmembers class com.github.serezhka.airplay.protocol.fairplay.SapTables { *; }

# --- dd-plist ---
-keep class com.dd.plist.** { *; }
-dontwarn com.dd.plist.**

# --- EdDSA (i2p) references sun.security classes that do not exist on Android ---
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn sun.security.**


# Keep stack traces readable in the in-app diagnostics log.
-keepattributes SourceFile,LineNumberTable
