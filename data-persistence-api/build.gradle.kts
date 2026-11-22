// 0008 (docs/architecture/0007 §7, follow-up 3): the C3 persistence ports of §6.3, the app
// snapshot's storage and its hot rows (SnapshotStorage, HotStateStore, HotDelta, the pairing). Between
// :domain, whose models they carry, and their implementers (:data-content, :data-repository, the
// app's hosts), which sit side by side and so cannot share a port among themselves (D5).
plugins {
    id("shinsou.kmp-library")
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
        }
    }
}
