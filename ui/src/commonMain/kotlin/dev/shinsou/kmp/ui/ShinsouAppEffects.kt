package dev.shinsou.kmp.ui

import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.domain.model.ReaderSettings
import dev.shinsou.kmp.domain.model.SecuritySettings
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.navigation.NavigationState
import dev.shinsou.kmp.navigation.openReaderCovered
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyRouter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.ui.reader.effectiveReaderVolumeKeyMonitoringEnabled
import dev.shinsou.kmp.ui.reader.effectiveReaderVolumeKeysEnabled

/**
 * Progress writers and plugin UI availability. The progress queues run off the UI thread: their
 * writes reach the repository's mutation observers, which keep their own stores.
 */
@Composable
internal fun ShellLifecycleEffects(app: ShinsouAppState, lifecycle: AppLifecycleState, unlocked: Boolean) {
    val appServices = app.appServices
    LaunchedEffect(lifecycle, unlocked) {
        val available = lifecycle == AppLifecycleState.FOREGROUND && unlocked
        withContext(app.environment.workDispatcher) { appServices.browse.setPluginUiAvailable(available) }
        if (!available) appServices.browse.dismissAllPluginLogouts()
    }
    LaunchedEffect(app.v2Progress) { withContext(app.environment.workDispatcher) { app.v2Progress.run() } }
    LaunchedEffect(app.localProgress) { withContext(app.environment.workDispatcher) { app.localProgress.run() } }
    DisposableEffect(appServices, app.volumeKeyRouter) {
        // Android can synchronously hand Activity key events to this router. Other platforms use
        // readerVolumeKeyEvents below; both paths still resolve to one active owner.
        appServices.setReaderVolumeKeyEventSink(app.volumeKeyRouter)
        onDispose { appServices.setReaderVolumeKeyEventSink(null) }
    }
    LaunchedEffect(appServices, app.volumeKeyRouter) {
        appServices.readerVolumeKeyEvents.collect(app.volumeKeyRouter::dispatch)
    }
    LaunchedEffect(app.navigator, app.volumeKeyRouter) {
        // An open reader with another page in front of it (phone or wide window) takes no presses.
        app.navigator.state.collect { app.volumeKeyRouter.covered = openReaderCovered(it) }
    }
    LaunchedEffect(lifecycle, app.v2Progress) {
        if (lifecycle == AppLifecycleState.FOREGROUND) return@LaunchedEffect
        // The extension reader callback is deliberately non-blocking. Before the process can be
        // suspended, place a barrier behind every queued local event and force its snapshot write.
        runCatchingCancellable { app.localProgress.flushLocal() }
            .onFailure { Log.warn("Reader", it) { "Flushing reading progress before suspension failed" } }
        runCatchingCancellable { app.v2Progress.flushLocal() }
            .onFailure { Log.warn("Reader", it) { "Flushing extension reading progress before suspension failed" } }
    }
}

/** Loads the reader's chapter, re-checks its rights on return, and resolves a pending source title. */
@Composable
internal fun ReaderAndTitleEffects(
    app: ShinsouAppState,
    reader: ReaderState,
    nav: NavigationState,
    lifecycle: AppLifecycleState,
) {
    LaunchedEffect(reader.session, reader.reloadKey) {
        val session = reader.session ?: return@LaunchedEffect
        // Reader chrome and loading state reach the screen before chapter or plugin work begins.
        app.reader.load(session, awaitFrame = { withFrameNanos { } })
    }
    val typed = reader.chapter.typedSession
    LaunchedEffect(lifecycle, typed, reader.chapterSession) {
        if (lifecycle != AppLifecycleState.FOREGROUND || typed == null) return@LaunchedEffect
        app.reader.recheckRights(typed)
    }
    val pending = nav.stack.pending
    LaunchedEffect(pending, app.appServices.browse) {
        val item = pending?.item ?: return@LaunchedEffect
        val request = pending.request
        // The pending page has entered composition. Waiting for the next frame draws its cover and
        // progress before plugin start-up begins.
        withFrameNanos { }
        if (!app.navigator.isPendingBrowse(item, request)) return@LaunchedEffect
        app.titles.resolvePending(item, request)
    }
}

/** Screen, window and volume-key behaviour that follows the reader and the settings. */
@Composable
internal fun PresentationEffects(
    app: ShinsouAppState,
    readerOpen: Boolean,
    /** A reader is open and no page covers it ([readerInFront]). */
    readerInFront: Boolean,
    readerSettings: ReaderSettings,
    secureScreen: Boolean,
) {
    val appServices = app.appServices
    LaunchedEffect(secureScreen) { appServices.setSecureScreen(secureScreen) }
    LaunchedEffect(readerOpen, readerSettings.keepScreenOn) {
        appServices.setKeepScreenOn(readerOpen && readerSettings.keepScreenOn)
    }
    LaunchedEffect(readerOpen, readerSettings.fullscreen) {
        appServices.setFullscreen(readerOpen && readerSettings.fullscreen)
    }
    DisposableEffect(appServices, readerInFront) {
        // Publish the reader gate as part of the composition commit. A launched coroutine leaves a
        // short window where iOS can begin its native edge recognizer after the reader appears.
        // A covered reader does not own the edge: Back swipes away the page in front of it.
        appServices.setReaderOpen(readerInFront)
        onDispose { if (readerInFront) appServices.setReaderOpen(false) }
    }
    DisposableEffect(appServices, readerOpen) {
        if (readerOpen) {
            appServices.setFullscreen(readerSettings.fullscreen)
            appServices.setReaderOrientation(readerSettings.orientation)
        }
        onDispose {
            if (readerOpen) {
                appServices.setFullscreen(false)
                appServices.setReaderOrientation(null)
            }
        }
    }
    LaunchedEffect(appServices, readerOpen, readerSettings.orientation) {
        if (readerOpen) appServices.setReaderOrientation(readerSettings.orientation)
    }
    val volumeKeysEnabled = effectiveReaderVolumeKeysEnabled(readerSettings.volumeKeys)
    // A covered reader gives the buttons back to the system, which changes the volume again.
    val interceptVolumeKeys = effectiveReaderVolumeKeyMonitoringEnabled(
        readerInFront,
        readerSettings.volumeKeys,
    )
    DisposableEffect(appServices, volumeKeysEnabled) {
        // As on the original iOS app: AVAudioSession, silent playback and MPVolumeView are prepared
        // as soon as the preference is on, before the reader starts listening.
        appServices.setReaderVolumeKeyInfrastructureEnabled(volumeKeysEnabled)
        onDispose { appServices.setReaderVolumeKeyInfrastructureEnabled(false) }
    }
    DisposableEffect(appServices, interceptVolumeKeys) {
        appServices.setReaderVolumeKeyMonitoringEnabled(interceptVolumeKeys)
        onDispose { appServices.setReaderVolumeKeyMonitoringEnabled(false) }
    }
    LaunchedEffect(interceptVolumeKeys) {
        if (!interceptVolumeKeys) app.volumeKeyPressTracker.clear()
    }
}

/**
 * Whether an open reader is the page in front, so it takes the volume buttons and the edge. The
 * chapter reader ([legacyReaderOpen]) is drawn over everything; an extension reader can have a
 * page in front of it ([ReaderVolumeKeyRouter.covered]), and then the system has the buttons.
 */
internal fun ShinsouAppState.readerInFront(legacyReaderOpen: Boolean, extensionReaderOpen: Boolean): Boolean =
    legacyReaderOpen || extensionReaderOpen && !volumeKeyRouter.covered

/** Locks and unlocks the app as the lifecycle and the lock settings change. */
@Composable
internal fun AppLockEffects(
    app: ShinsouAppState,
    security: SecuritySettings,
    appLockEnabled: Boolean,
    lifecycle: AppLifecycleState,
    unlocked: Boolean,
) {
    val authenticationAvailable = app.appServices.securityCapabilities.appLock.available
    LaunchedEffect(authenticationAvailable, security.appLockEnabled) {
        app.lock.onAuthenticationAvailabilityChanged(security.appLockEnabled, authenticationAvailable)
    }
    LaunchedEffect(lifecycle, appLockEnabled, security.lockAfterSeconds) {
        app.lock.onLifecycleChanged(lifecycle, appLockEnabled, security.lockAfterSeconds)
    }
    LaunchedEffect(appLockEnabled, unlocked, lifecycle) { app.lock.enforce(appLockEnabled, lifecycle) }
}

/**
 * Deep links, followed once the app is unlocked and on screen; and the platform's Back events and
 * gesture. On screen: Android can run a second activity on the same process-wide link queue, and
 * only the one the user sees may take a link.
 */
@Composable
internal fun NavigationEventEffects(app: ShinsouAppState, unlocked: Boolean, foreground: Boolean, backSwipe: BackSwipeProgress) {
    val appServices = app.appServices
    LaunchedEffect(appServices, unlocked, foreground) {
        // Back on screen: a link this shell was asking about may have been answered meanwhile.
        if (foreground) app.reviews.dropPromptsHandledElsewhere(appServices::isDeepLinkPending)
        // Every platform keeps the current deep link until deepLinkHandled is called.
        // The captured "unlocked" may predate the foreground lock check: the app may have just
        // locked on its return after the lock timeout.
        if (!unlocked || !foreground || !app.lock.unlockedOnceChecked(AppLifecycleState.FOREGROUND)) return@LaunchedEffect
        try {
            appServices.deepLinks.collect { link ->
                // Locked meanwhile: the link waits (unacknowledged) until this effect restarts unlocked.
                if (!app.lock.state.value.unlocked) awaitCancellation()
                app.handleDeepLink(link)
            }
        } finally {
            // No longer taking links: a prompt for one goes (this shell asks again once it takes
            // links again, if the link still waits).
            app.reviews.dropPromptsHandledElsewhere { false }
        }
    }
    LaunchedEffect(appServices) {
        appServices.systemBackEvents.collect { app.systemBack() }
    }
    DisposableEffect(appServices) {
        appServices.setSystemBackHandler(app::systemBack)
        onDispose { appServices.setSystemBackHandler(null) }
    }
    LaunchedEffect(appServices) {
        appServices.systemBackGestureEvents.collect { event ->
            when (event) {
                is SystemBackGestureEvent.Progress -> {
                    if (app.backGestureDragsPage()) backSwipe.snapTo(event.fraction)
                }
                is SystemBackGestureEvent.Settled -> settleBackGesture(app, event.committed, backSwipe)
            }
        }
    }
}

private suspend fun settleBackGesture(app: ShinsouAppState, committed: Boolean, backSwipe: BackSwipeProgress) {
    val readerClosed = app.reader.state.value.session == null
    val movesPage = app.appServices.browse.loginRequests.value.isEmpty() && app.navigator.gestureMovesVisiblePage()
    if (committed && readerClosed && movesPage) {
        backSwipe.animateTo(1f, animationSpec = tween(160))
        app.systemBack()
    } else {
        if (committed && readerClosed) app.systemBack()
        backSwipe.animateTo(0f, animationSpec = tween(180))
    }
}
