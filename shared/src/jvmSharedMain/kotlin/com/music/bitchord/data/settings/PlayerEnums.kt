package com.music.bitchord.data.settings

/**
 * Stream bitrate ceiling on the YouTube fallback path — MEDIUM, HIGH and
 * LOSSLESS all mean "whatever the best available Opus format is" there; what
 * actually tells them apart is which other sources are allowed to answer
 * *before* YouTube gets asked. That part is [permits], and the rungs read:
 *
 * - [LOSSLESS] — the user's own addons and JioSaavn both asked.
 * - [HIGH] — the addons skipped, JioSaavn asked.
 * - [MEDIUM] and [LOW] — both skipped; YouTube's own Opus ladder is all there
 *   is, capped at [maxKbps].
 *
 * [hourly] is what the ceiling costs in data over an hour of listening, which
 * is the only part of this a user actually cares about on a metered plan.
 */
enum class AudioQuality(
    val maxKbps: Int,
    val label: String,
    val detail: String,
    val hourly: String,
) {
    // Ultra: upstream BitChord's data-saver floor was 64 kbps; Ultra raises it
    // to 128 (matching ModuleSource.LOW_CEILING_KBPS, the module-source tier
    // split already drawn at that line) so the cheapest rung is still usable.
    LOW(128, "Low", "~128 kbps · smallest download", "58 MB/hr"),
    MEDIUM(Int.MAX_VALUE, "Medium", "Best available · ~171 kbps Opus", "77 MB/hr"),
    HIGH(Int.MAX_VALUE, "High", "JioSaavn up to 320kbps, YouTube fallback", "144 MB/hr"),
    LOSSLESS(Int.MAX_VALUE, "Lossless", "Your addons + JioSaavn, bit-exact where available", "300+ MB/hr"),
    ;
}

/** What the phone's main player stands on, below the artwork. */
enum class PlayerTheme {
    /** The artwork's own colours hung off its bottom edge, as a mesh. */
    DEFAULT,

    /** v1.5's four quantised blobs, drifting behind the whole screen. */
    LEGACY,

    /** The cover blurred across the whole screen, as the lyrics and queue are. */
    SIMPLE,
}

/** The surface that was last open inside the expanded player. */
enum class LastPlayerScreen {
    MAIN,
    LYRICS,
    QUEUE,
}
