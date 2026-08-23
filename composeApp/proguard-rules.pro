# JNA binds native symbols and resolves methods/fields reflectively.  The
# default Compose desktop release rules otherwise remove Native.dispose and
# related callback metadata, causing an UnsatisfiedLinkError at startup.
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# JNA derives the native symbol name from the Java method name.  ProGuard's
# default Kotlin optimisation renamed these methods with `$<hash>` suffixes,
# so Security.framework could no longer resolve SecKeychain* when an
# extension first accessed the desktop secret store.
-keep class dev.shinsou.kmp.desktop.JnaMacOsKeychainApi { *; }
-keep interface dev.shinsou.kmp.desktop.JnaMacOsKeychainApi$SecurityFramework { *; }
-keep interface dev.shinsou.kmp.desktop.JnaMacOsKeychainApi$CoreFoundationFramework { *; }

# DPAPI uses the same reflective JNA binding model on Windows.  Keep native
# function names and DATA_BLOB field names stable in release installers.
-keep class dev.shinsou.kmp.desktop.JnaWindowsDpapiApi { *; }
-keep interface dev.shinsou.kmp.desktop.JnaWindowsDpapiApi$Crypt32Library { *; }
-keep interface dev.shinsou.kmp.desktop.JnaWindowsDpapiApi$Kernel32Library { *; }
-keep class dev.shinsou.kmp.desktop.JnaWindowsDpapiApi$DataBlob { *; }

# Rhino selects its JVM bridge by class name at runtime.  ProGuard cannot see
# the reflective lookup from VMBridge.makeInstance(), so a release image that
# only keeps the entry point loses jdk18/VMBridge_jdk18 and every plugin fails
# on its first Context.enter() with "Failed to create VMBridge instance".
-keep class org.mozilla.javascript.** { *; }

# Rhino exposes this object to every extension through Context.javaToJS().
# Its public methods are therefore called by JavaScript only; without this
# rule ProGuard removes/renames them and scripts report e.g. "Cannot find
# function httpGetWithHeaders" even though the JVM class still loads.
-keep class dev.shinsou.kmp.plugin.RhinoPluginBridge { *; }

# Coil's desktop network fetcher is registered through
# META-INF/services/coil3.util.FetcherServiceLoaderTarget.  Its implementation
# is never referenced directly from Kotlin, so release shrinking otherwise
# leaves only the service file and every remote cover/page becomes a blank
# placeholder.  Keep the service target and its Ktor fetcher implementation.
-keep class coil3.network.** { *; }

# Okio 3.17 contains JVM bridge methods generated from multiplatform sources.
# The desktop release optimiser can incorrectly narrow one of those methods'
# return types while leaving a BufferedSource cast in its bytecode, which makes
# the whole network fetch fail at runtime with VerifyError before any HTTP
# request is sent.  Keep Okio's public ABI intact in the packaged application.
-keep class okio.** { *; }

# Coil's default Ktor network factory creates an HttpClient lazily.  Ktor finds
# the desktop CIO engine through this service provider, so retain the provider
# class as well as the implementation that is already referenced directly by
# the application's own HTTP client.
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }

# JavaFX WebKit resolves the private EPUB URL scheme exclusively through the
# META-INF/services/java.net.spi.URLStreamHandlerProvider entry.  There is no
# direct Kotlin reference for the shrinker to follow, so keep the provider's
# binary name aligned with that service descriptor in packaged DMG/MSI/EXE
# builds.
-keep class dev.shinsou.kmp.reader.protocol.EpubUrlStreamHandlerProvider { *; }

# The Compose desktop distribution includes the JavaFX Web module so EPUB
# pages can be rendered, but WebKit's optional media/editor/control bridges
# refer to JavaFX modules that are intentionally not shipped in the runtime
# image.  QRose likewise exposes a couple of optional Compose graphics
# overloads that vary across the Compose version used by the application.
# These references are not reached by the reader and ProGuard 7.7 treats them
# as fatal unless the optional edges are explicitly ignored.
-dontoptimize
-dontwarn com.sun.javafx.**
-dontwarn com.sun.webkit.**
-dontwarn javafx.scene.web.**
-dontwarn io.github.alexzhirkevich.qrose.**
