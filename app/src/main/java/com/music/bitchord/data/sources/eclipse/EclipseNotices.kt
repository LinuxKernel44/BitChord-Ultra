package com.music.bitchord.data.sources.eclipse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The notice an Eclipse addon most recently asked to have shown, for the in-app
 * banner to draw.
 *
 * Rules from the protocol, kept here so every caller gets them for free:
 *  - A later notice with the same [EclipseNotice.id] *replaces* the earlier one
 *    (so a "preparing" toast updates in place instead of stacking).
 *  - The same text from the same addon is not shown twice within a minute.
 *  - A notice that rode on the manifest is shown once per id.
 *  - The listener can switch an addon's notices off, which only applies when
 *    the addon declares the `notifications` resource.
 */
object EclipseNotices {

    /** What the banner shows. [serial] makes two identical notices distinct emissions. */
    data class Shown(
        val addon: String,
        val notice: EclipseNotice,
        val serial: Long,
    )

    private val state = MutableStateFlow<Shown?>(null)
    val current: StateFlow<Shown?> get() = state

    private var serial = 0L
    private val recent = HashMap<String, Long>()
    private val manifestSeen = HashSet<String>()

    /**
     * Offers [notice] from [addon]. [enabled] is the listener's switch for this
     * addon; [fromManifest] marks a notice that came on `/manifest.json`.
     */
    @Synchronized
    fun post(addon: String, notice: EclipseNotice, enabled: Boolean, fromManifest: Boolean = false) {
        if (!enabled || notice.text.isEmpty()) return
        val now = System.currentTimeMillis()
        if (fromManifest) {
            val key = "$addon|${notice.id ?: notice.text}"
            if (!manifestSeen.add(key)) return
        } else if (notice.id == null) {
            val key = "$addon|${notice.text}"
            val last = recent[key]
            if (last != null && now - last < DEDUPE_MS) return
            recent[key] = now
            if (recent.size > 64) recent.entries.removeAll { now - it.value > DEDUPE_MS }
        }
        // An id'd notice always goes through: it is an update to the one on show.
        state.value = Shown(addon, notice, ++serial)
    }

    /** Dismisses the banner if it is still showing [shown]. */
    @Synchronized
    fun dismiss(shown: Shown) {
        if (state.value?.serial == shown.serial) state.value = null
    }

    private const val DEDUPE_MS = 60_000L
}
