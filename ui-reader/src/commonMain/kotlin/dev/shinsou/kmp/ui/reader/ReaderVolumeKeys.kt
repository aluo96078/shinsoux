package dev.shinsou.kmp.ui.reader

import dev.shinsou.kmp.reader.ReaderTapAction

public enum class ReaderVolumeKeyEvent {
    VOLUME_UP,
    VOLUME_DOWN,
}

public fun interface ReaderVolumeKeyEventSink {
    /** Returns true when the currently mounted reader accepted the physical press. */
    public fun dispatch(event: ReaderVolumeKeyEvent): Boolean
}

/**
 * Routes every platform volume-button event to exactly one mounted reader.
 *
 * Compose can temporarily retain an outgoing destination while the next reader is already
 * mounted. A broadcast flow lets both destinations react to the same key press. Registrations are
 * therefore ordered: the newest reader owns input, disposing an older registration cannot clear
 * the newer owner, and disposing the newer owner cannot reactivate an outgoing reader.
 */
public class ReaderVolumeKeyRouter : ReaderVolumeKeyEventSink {
    private class Owner(
        val handler: (ReaderVolumeKeyEvent) -> Boolean,
    )

    internal class Registration internal constructor(
        private val unregisterAction: () -> Unit,
    ) {
        private var registered = true

        fun unregister() {
            if (!registered) return
            registered = false
            unregisterAction()
        }
    }

    private var activeOwner: Owner? = null
    internal fun register(handler: (ReaderVolumeKeyEvent) -> Boolean): Registration {
        val owner = Owner(handler)
        activeOwner = owner
        return Registration {
            if (activeOwner === owner) activeOwner = null
        }
    }

    override fun dispatch(event: ReaderVolumeKeyEvent): Boolean {
        val owner = activeOwner ?: return false
        return owner.handler(event)
    }
}

/** Stable router target that follows the latest state of one reader chapter or extension unit. */
internal class ReaderVolumeKeyHandlerSlot {
    private var handler: (ReaderVolumeKeyEvent) -> Boolean = { false }

    fun update(handler: (ReaderVolumeKeyEvent) -> Boolean) {
        this.handler = handler
    }

    fun dispatch(event: ReaderVolumeKeyEvent): Boolean = handler(event)
}

/** De-duplicates a native DOWN/repeat/UP sequence into one logical reader action. */
public class ReaderVolumeKeyPressTracker {
    private val heldKeys = mutableSetOf<ReaderVolumeKeyEvent>()

    public fun shouldDispatchDown(event: ReaderVolumeKeyEvent, repeatCount: Int): Boolean {
        if (repeatCount != 0) return false
        return heldKeys.add(event)
    }

    public fun release(event: ReaderVolumeKeyEvent) {
        heldKeys.remove(event)
    }

    public fun clear() {
        heldKeys.clear()
    }
}

/** Rejects callbacks emitted by a reader surface after its immutable session was replaced. */
public fun readerProgressSessionIsActive(
    activeSession: Any?,
    callbackSession: Any,
    transitionInFlight: Boolean,
): Boolean = activeSession === callbackSession && !transitionInFlight

/**
 * Maps hardware buttons to logical reader movement only while the complete common gate is open.
 * Keeping this platform-neutral prevents an iOS callback from bypassing reader state or settings.
 */
internal fun readerVolumeKeyAction(
    event: ReaderVolumeKeyEvent,
    readerOpen: Boolean,
    volumeKeysEnabled: Boolean,
): ReaderTapAction? {
    if (!readerOpen || !volumeKeysEnabled) return null
    return when (event) {
        ReaderVolumeKeyEvent.VOLUME_UP -> ReaderTapAction.PREVIOUS_PAGE
        ReaderVolumeKeyEvent.VOLUME_DOWN -> ReaderTapAction.NEXT_PAGE
    }
}

/**
 * iOS applies a real system volume change unless this returns true. Consume the press whenever
 * a reader is open and volume-key paging is live, even if the current page cannot move.
 */
public fun readerVolumeKeyShouldBeConsumed(
    readerOpen: Boolean,
    volumeKeysEnabled: Boolean,
    monitoringEnabled: Boolean,
): Boolean = readerOpen && volumeKeysEnabled && monitoringEnabled

internal fun readerMayCrossChapterBoundary(
    proseReader: Boolean,
    pageCountMeasured: Boolean,
    interactionBlocked: Boolean,
): Boolean = !interactionBlocked && (!proseReader || pageCountMeasured)
