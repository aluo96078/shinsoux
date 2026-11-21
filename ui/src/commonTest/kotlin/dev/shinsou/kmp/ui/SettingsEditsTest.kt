package dev.shinsou.kmp.ui

import dev.shinsou.kmp.data.ShinsouRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsEditsTest {
    private fun TestScope.actions(): UserActions = ShellUserActions(
        HolderEnvironment(this, AppMessages({ dev.shinsou.kmp.ui.i18n.shinsouStringsFor("en") }) {}, StandardTestDispatcher(testScheduler)),
    )

    @Test
    fun twoEditsQueuedFromTheSameDrawingBothStay() = runTest {
        val repository = ShinsouRepository()
        val actions = actions()

        // Secure Screen, then Incognito, before either is stored or the screen is drawn again.
        actions.editSettings(repository) { it.copy(security = it.security.copy(secureScreen = true)) }
        actions.editSettings(repository) { it.copy(security = it.security.copy(incognitoMode = true)) }
        advanceUntilIdle()

        assertEquals(true, repository.currentSnapshot.settings.security.secureScreen)
        assertEquals(true, repository.currentSnapshot.settings.security.incognitoMode)
    }

    @Test
    fun aSliderMovedAwayAndBackEndsWhereItWasLeft() = runTest {
        val repository = ShinsouRepository()
        val actions = actions()
        val drawn = repository.currentSnapshot.settings.library.portraitColumns

        actions.editSettings(repository) { it.copy(library = it.library.copy(portraitColumns = drawn + 1)) }
        actions.editSettings(repository) { it.copy(library = it.library.copy(portraitColumns = drawn)) }
        advanceUntilIdle()

        assertEquals(drawn, repository.currentSnapshot.settings.library.portraitColumns)
    }

    @Test
    fun anEditMadeAfterAnotherWasStoredKeepsIt() = runTest {
        val repository = ShinsouRepository()
        val actions = actions()

        actions.editSettings(repository) { it.copy(security = it.security.copy(incognitoMode = true)) }
        advanceUntilIdle()
        actions.editSettings(repository) { it.copy(security = it.security.copy(incognitoMode = false)) }
        actions.editSettings(repository) { it.copy(updates = it.updates.copy(checkForUpdates = true)) }
        advanceUntilIdle()

        assertEquals(false, repository.currentSnapshot.settings.security.incognitoMode)
        assertEquals(true, repository.currentSnapshot.settings.updates.checkForUpdates)
    }
}
