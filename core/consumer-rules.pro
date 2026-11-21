# R5-3 (docs/architecture/0006): R8 rules for :core's Android dependencies in the app's release build.

# libsodium (crypto/PlatformLibsodium.android.kt) is bound through JNA. JNA maps an interface's
# method names to native symbols, reads Structure fields by name, and its libjnidispatch.so calls back
# into com.sun.jna classes by name. Neither AAR ships R8 rules.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class com.ionspin.kotlin.crypto.JnaLibsodiumInterface { *; }
# JNA's desktop helpers (AWT window handles) are not on Android and never reached there.
-dontwarn java.awt.**
