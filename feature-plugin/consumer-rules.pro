# R5-3 (docs/architecture/0006): R8 rules for the plugin sandbox in the app's release build.

# JNI entry points of libshinsou_sandbox.so (sandbox-core/src/ssc_android_jni.c):
# Java_dev_shinsou_kmp_plugin_sandbox_SandboxNative_native{Lockdown,Run,Probe}.
-keep class dev.shinsou.kmp.plugin.sandbox.SandboxNative {
    native <methods>;
}

# The isolated-process service and the app zygote's preload class are named in the manifest
# (AndroidManifest.xml: android:name, android:zygotePreloadName). The system instantiates the
# preload class by that name in the app zygote, which aapt2's manifest rules do not cover.
-keep class dev.shinsou.kmp.plugin.sandbox.SandboxProcessService { <init>(); }
-keep class dev.shinsou.kmp.plugin.sandbox.SandboxZygotePreload { <init>(); }

# ISandboxProcess (AIDL): the host and the sandbox process are the same APK, so renaming would be
# consistent; kept anyway so the binder interface reads the same in traces and device tests.
-keep class dev.shinsou.kmp.plugin.sandbox.ISandboxProcess { *; }
-keep class dev.shinsou.kmp.plugin.sandbox.ISandboxProcess$* { *; }
