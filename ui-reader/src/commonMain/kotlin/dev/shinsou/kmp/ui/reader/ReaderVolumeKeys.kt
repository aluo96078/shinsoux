package dev.shinsou.kmp.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 *
 * One reader does come back: the extension reader stays mounted beneath a chapter reader opened
 * over it, and its registration effect does not run again when that reader closes. The two cases
 * are told apart by how the reader registered. A chapter reader is never kept beneath another
 * reader, so one that a newer reader replaced is an outgoing destination and is dropped for good.
 * A [Owner.coverable] reader (the extension reader) is kept beneath the newer one and owns input
 * again once every reader registered after it is gone. An extension reader that is itself leaving
 * unregisters when it is disposed, so it can only get presses back for that last moment.
 */
public class ReaderVolumeKeyRouter : ReaderVolumeKeyEventSink {
    private class Owner(
        val handler: (ReaderVolumeKeyEvent) -> Boolean,
        /** An extension reader, which a page can cover ([covered]) and which stays mounted beneath it. */
        val coverable: Boolean,
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

    /**
     * Live registrations: coverable readers, oldest first, then at most one chapter reader. The
     * last one owns input; the others are coverable readers beneath it.
     */
    private val owners = mutableListOf<Owner>()

    /**
     * A page covers the mounted extension reader (it stays mounted beneath a recovery, for
     * instance): presses go to nobody while that reader owns them, so they cannot turn pages or change chapters behind that page, and the
     * platform handles them as volume presses. It is snapshot state: the platform stops
     * intercepting volume buttons, and the reader stops paging from the keyboard, while it is set.
     */
    public var covered: Boolean by mutableStateOf(false)

    internal fun register(
        coverable: Boolean = false,
        handler: (ReaderVolumeKeyEvent) -> Boolean,
    ): Registration {
        val owner = Owner(handler, coverable)
        if (coverable) {
            // An extension reader can finish loading after a chapter reader opened over it: it
            // goes beneath that reader, never in front of it, and evicts nobody.
            val chapterReader = owners.indexOfFirst { !it.coverable }
            owners.add(if (chapterReader >= 0) chapterReader else owners.size, owner)
        } else {
            // A replaced chapter reader is outgoing; only a reader that stays mounted waits beneath.
            owners.removeAll { !it.coverable }
            owners += owner
        }
        return Registration { owners.remove(owner) }
    }

    override fun dispatch(event: ReaderVolumeKeyEvent): Boolean {
        // A chapter reader opened over a covered extension reader is in front and keeps paging.
        val owner = owners.lastOrNull()?.takeUnless { covered && it.coverable } ?: return false
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
