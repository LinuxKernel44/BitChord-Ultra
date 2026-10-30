package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import dev.chrisbanes.haze.HazeState
import java.util.Locale

/**
 * Ultra: the PowerAmp-style "Audio Info" popup — a compact, icon-led list of
 * Track / Decoder / Bit Perfect / Volume Control / Output / Output Device,
 * closed out by a Fidelity/DSP score pair — opened by long-pressing the
 * "stats for nerds" line under the sleeve (see
 * [com.music.bitchord.ui.player.SleeveNerdStats]).
 *
 * Deliberately a second surface rather than a restyle of
 * [AudioPipelineDialog]: that one stays reachable from "Audio Pipeline" under
 * Audio Output for the deeper per-stage inspection (resampler cutoff, DSP
 * buffers, Bluetooth codec telemetry); this one is the quick glance a
 * long-press should answer in one screen, without a scroll past six stages of
 * animated plumbing to find the device's bit depth. Both read the same live
 * state — [NerdStats], [AudioOutputStatus], [BitPerfectOutput] — so neither
 * can say something the other disagrees with.
 */
@Composable
fun AudioInfoDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val bitPerfect by BitPerfectOutput.status.collectAsStateWithLifecycle()
    val eqEnabled by AppSettings.equalizerEnabled.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val crossfadeSeconds by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()

    AudioPopupCard(
        hazeState = hazeState,
        title = "Audio Info",
        subtitle = nerdStats?.sourceName ?: "Live playback pipeline",
        doneLabel = "Done",
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 1. Track
            val codec = NerdStats.codecLabel(nerdStats?.mimeType) ?: nerdStats?.mimeType ?: "—"
            val channelsLabel = when (nerdStats?.channels) {
                1 -> "Mono"
                2 -> "2 Channels"
                null -> null
                else -> "${nerdStats?.channels} Channels"
            }
            val bitrateLabel = nerdStats?.bitrateKbps?.let { "$it kbps" }
            val trackLine1 = listOfNotNull(codec.takeIf { it != "—" }, channelsLabel, bitrateLabel)
                .joinToString(" ")
                .ifEmpty { "—" }
            val sampleRate = nerdStats?.sampleRateHz?.let(::rateText)
                ?: nerdStats?.claimed?.sampleRateHz?.let(::rateText)
            val bitDepth = if (nerdStats?.isLossless == true) {
                (nerdStats?.bitDepth ?: nerdStats?.claimed?.bitDepth)?.let { "$it bit" }
            } else {
                null
            }
            val gaplessOrCrossfade = if (crossfadeSeconds == 0) "Gapless" else "Crossfade ${crossfadeSeconds}s"

            PipelineRule()
            AudioInfoSection(icon = Icons.AutoMirrored.Rounded.InsertDriveFile, title = "Track") {
                AudioInfoLine(trackLine1)
                sampleRate?.let { AudioInfoLine(it) }
                bitDepth?.let { AudioInfoLine(it) }
                AudioInfoLine(gaplessOrCrossfade, underline = true)
            }

            // 2. Decoder
            val dsdActive = nerdStats?.mimeType?.startsWith("audio/dsd") == true
            val decoderName = if (dsdActive) {
                "DSD to PCM (native FIR decimator)"
            } else {
                outputStatus.decoderName ?: "BitChord Media3 Decoder"
            }
            PipelineRule()
            AudioInfoSection(icon = Icons.Rounded.Memory, title = "Decoder") {
                AudioInfoLine(decoderName)
            }

            // 3. Bit Perfect
            val inRate = nerdStats?.sampleRateHz
            val outRate = if (bitPerfect.active) bitPerfect.sampleRateHz else outputStatus.actualSampleRateHz
            val isPassthrough = inRate != null && outRate != null && inRate == outRate
            val verdict = bitExactVerdict(
                dsd = dsdActive,
                bitPerfect = bitPerfect,
                outputExact = outputStatus.outputExact,
                outputExactDetail = outputStatus.outputExactDetail,
                loudnessActive = loudnessNormalization && outputStatus.loudnessGainDb != null,
                eqActive = eqEnabled,
                spatialActive = spatialAudio,
            )
            val isBitExact = verdict.startsWith("Yes")
            PipelineRule()
            AudioInfoSection(
                icon = Icons.Rounded.VerifiedUser,
                title = if (isBitExact) "Perfect Bit Perfect" else "Not Bit Perfect",
                titleUnderline = true,
            ) {
                AudioInfoLine(if (isPassthrough) "No Resampling" else "Resampled")
                AudioInfoLine(
                    if (!eqEnabled && !loudnessNormalization && !spatialAudio) "No DSP" else "DSP Active",
                )
            }

            // 4. Volume Control
            PipelineRule()
            AudioInfoSection(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = if (bitPerfect.active) "Hardware Volume" else "App Volume",
                titleUnderline = true,
            ) {
                AudioInfoLine(
                    if (bitPerfect.active) "Set on the DAC, bypasses the mixer" else "System media volume",
                )
            }

            // 5. Output
            val exclusive = bitPerfect.active
            val outputTitle = when {
                bitPerfect.active -> "${bitPerfect.driver ?: "USB"} Exclusive Output"
                outputStatus.directPlaybackActual -> "Direct Output"
                else -> "Mixed Output"
            }
            val outputDeviceLabel = bitPerfect.deviceName ?: outputStatus.deviceName
            val outputRate = outRate?.let(::rateText)
            val outputBits = if (bitPerfect.active) bitPerfect.outputBits else null
            val latencyMs = outputStatus.bufferSize?.let { size ->
                val rate = outputStatus.actualSampleRateHz
                if (rate != null && rate > 0) ((size.toLong() * 1000L) / (rate * 4L)) else null
            }
            val flags = buildList {
                if (isBitExact) add("PERFECT_BIT_PERFECT")
                if (outputStatus.directPlaybackActual || bitPerfect.active) add("DIRECT")
                if (exclusive) add("USB_EXCLUSIVE")
                if (bitPerfect.active) add("HARDWARE_VOLUME")
                outputStatus.sink.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.ROOT)) }
            }
            PipelineRule()
            AudioInfoSection(
                icon = if (exclusive) Icons.Rounded.Usb else Icons.AutoMirrored.Rounded.VolumeUp,
                title = outputTitle,
                titleUnderline = true,
            ) {
                AudioInfoLine("Output Profile: $outputDeviceLabel")
                outputRate?.let { AudioInfoLine(it) }
                outputBits?.let { AudioInfoLine("$it bit") }
                if (flags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    AudioInfoLine("Flags", dim = true)
                    AudioInfoLine(flags.joinToString(" "))
                }
                latencyMs?.let {
                    Spacer(Modifier.height(6.dp))
                    AudioInfoLine("Latency", dim = true)
                    AudioInfoLine("$it ms")
                }
            }

            // 6. Output Device
            PipelineRule()
            AudioInfoSection(icon = Icons.Rounded.Usb, title = "Output Device", titleUnderline = true) {
                AudioInfoLine(outputDeviceLabel)
                outputRate?.let { AudioInfoLine(it) }
                outputBits?.let { AudioInfoLine("$it bit") }
            }

            // 7. Fidelity / DSP scorecards
            val fidelity = fidelityScore(
                isLossless = nerdStats?.isLossless == true,
                dsd = dsdActive,
                bitPerfect = bitPerfect,
                outputExact = outputStatus.outputExact,
                isPassthroughResample = isPassthrough,
                loudnessActive = loudnessNormalization && outputStatus.loudnessGainDb != null,
                eqActive = eqEnabled,
                spatialActive = spatialAudio,
            )
            val dspLabel = buildList {
                if (eqEnabled) add("EQ")
                if (loudnessNormalization && outputStatus.loudnessGainDb != null) add("Loudness")
                if (spatialAudio) add("Spatial")
            }.joinToString("+").ifEmpty { "–" }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                ScoreCard(label = "Fidelity", value = "$fidelity", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                ScoreCard(label = "DSP", value = dspLabel, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * BitChord's own 0-100 fidelity estimate — not a reverse-engineered copy of
 * any other player's score, just a readable summary of the same verdict
 * [bitExactVerdict] already states in words: a lossy source or an active DSP
 * stage costs the most (they are what a listener can actually hear), a
 * DAC-limited or merely-resampled bit-perfect path costs less (still exact,
 * just not to the source's own rate/depth), and nothing is deducted twice for
 * the same cause.
 */
internal fun fidelityScore(
    isLossless: Boolean,
    dsd: Boolean,
    bitPerfect: BitPerfectOutput.Status,
    outputExact: Boolean,
    isPassthroughResample: Boolean,
    loudnessActive: Boolean,
    eqActive: Boolean,
    spatialActive: Boolean,
): Int {
    var score = 100
    if (!isLossless) score -= 40
    if (dsd) score -= 10
    if (bitPerfect.active) {
        if (bitPerfect.dacLimited) score -= 35 else if (!bitPerfect.exact) score -= 20
    } else {
        if (!outputExact) score -= 30
        if (!isPassthroughResample) score -= 15
    }
    if (eqActive) score -= 25
    if (loudnessActive) score -= 15
    if (spatialActive) score -= 30
    return score.coerceIn(0, 100)
}

@Composable
private fun AudioInfoSection(
    icon: ImageVector,
    title: String,
    titleUnderline: Boolean = false,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W700,
                    textDecoration = if (titleUnderline) TextDecoration.Underline else TextDecoration.None,
                ),
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun AudioInfoLine(text: String, underline: Boolean = false, dim: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            fontWeight = if (underline) FontWeight.W600 else FontWeight.Normal,
            textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
        ),
        color = Color.White.copy(alpha = if (dim) 0.55f else 0.88f),
    )
}

@Composable
private fun ScoreCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(vertical = 14.dp, horizontal = 12.dp),
    ) {
        Row {
            Icon(
                imageVector = if (label == "DSP") Icons.Rounded.Equalizer else Icons.Rounded.VerifiedUser,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.W600),
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, fontWeight = FontWeight.W700),
            color = Color.White,
        )
    }
}
