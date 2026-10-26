plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The compatibility bootstrap is produced by the repo-level S0 build (build/native/bootstrap.js).
val bootstrapAsset = tasks.register<Copy>("copyBootstrap") {
    from(rootProject.file("../build/native/bootstrap.js"))
    into(layout.buildDirectory.dir("generated/assets"))
}

android {
    namespace = "dev.shinsou.sandbox.harness"
    compileSdk = 36
    defaultConfig { applicationId = "dev.shinsou.sandbox.harness"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "s0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/assets"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(bootstrapAsset) }
kotlin { jvmToolchain(21) }
dependencies { implementation(project(":")) }
