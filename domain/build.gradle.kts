// R5-1 (docs/architecture/0006 §4.2): domain (models), rights and contract (the types shared
// across features). Depends on no other module of the app (0007 §7, follow-up 3; checked by
// DependencyDirectionTest): its one lock is its own (rights/RightsLock).
plugins {
    id("shinsou.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            // StateFlow is in the contracts' API (it used to come with :core's api).
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
