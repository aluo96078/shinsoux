// R5-1 (docs/architecture/0006 §4.2): backup (with the ShuYue import), download, tracking,
// statistics and update. Depends on the data, domain and core modules.
plugins {
    id("shinsou.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":core-network"))
            implementation(project(":domain"))
            implementation(project(":data-content"))
            implementation(project(":data-repository"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.json.okio)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        val desktopTest by getting {
            dependencies {
                // The v2 restore tests wire the C3 hot-state store (SqlHotStateStore) into the repository.
                implementation(project(":data-persistence-api"))
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}
