// R5-2 (docs/architecture/0006): the app's build logic lives here as convention plugins, compiled
// once; composeApp/build.gradle.kts keeps the plugin list and the dependencies.
plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the app's own plugin requests (root build.gradle.kts) put these on the classpath
    // at their catalog versions; the convention plugins only compile against them.
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    compileOnly("org.jetbrains.compose:compose-gradle-plugin:${libs.versions.compose.get()}")
    compileOnly("dev.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}")
    compileOnly("org.jetbrains.kotlinx:kover-gradle-plugin:${libs.versions.kover.get()}")
}

gradlePlugin {
    plugins {
        register("kmp") {
            id = "shinsou.kmp"
            implementationClass = "dev.shinsou.build.KmpPlugin"
        }
        register("kmpLibrary") {
            id = "shinsou.kmp-library"
            implementationClass = "dev.shinsou.build.KmpLibraryPlugin"
        }
        register("iosFramework") {
            id = "shinsou.ios-framework"
            implementationClass = "dev.shinsou.build.IosFrameworkPlugin"
        }
        register("codegen") {
            id = "shinsou.codegen"
            implementationClass = "dev.shinsou.build.CodegenPlugin"
        }
        register("nativeLibraries") {
            id = "shinsou.native-libraries"
            implementationClass = "dev.shinsou.build.NativeLibrariesPlugin"
        }
        register("nativeInterop") {
            id = "shinsou.native-interop"
            implementationClass = "dev.shinsou.build.NativeInteropPlugin"
        }
        register("androidApp") {
            id = "shinsou.android-app"
            implementationClass = "dev.shinsou.build.AndroidAppPlugin"
        }
        register("desktopApp") {
            id = "shinsou.desktop-app"
            implementationClass = "dev.shinsou.build.DesktopAppPlugin"
        }
        register("quality") {
            id = "shinsou.quality"
            implementationClass = "dev.shinsou.build.QualityPlugin"
        }
        register("testing") {
            id = "shinsou.testing"
            implementationClass = "dev.shinsou.build.TestingPlugin"
        }
    }
}
