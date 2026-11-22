// R5-1 (docs/architecture/0006 §4.2): content (the SQL content store, blobs, acquisition),
// annotation and search. Depends on :domain and :core.
plugins {
    id("shinsou.kmp-library")
    id("shinsou.sqldelight")
    alias(libs.plugins.kotlinSerialization)
}

// C4 (docs/architecture/0007 §6.2): one database per table group, query code only.
sqldelight {
    databases {
        create("ContentAnnotationDatabase") {
            packageName.set("dev.shinsou.kmp.annotation.sql")
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":domain"))
            api(libs.sqldelight.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}
