package de.lanewise.nav

import kotlin.math.abs

/**
 * Joins the clips of one announcement into a single PCM buffer: each clip's leading / trailing silence is trimmed
 * and the clips are put together with a short, natural gap. Playing the files one after another (MediaPlayer
 * `setNextMediaPlayer`) left an audible pause between "Turn right onto" and "Celler Straße" on the owner's phone.
 * Pure Kotlin (16-bit interleaved samples), unit-tested.
 */
object PcmJoin {

    /** Samples quieter than this (of 32767) count as silence. */
    const val THRESHOLD = 500

    /** Cuts silence at both ends, keeping [keepMs] before the first and after the last sound. */
    fun trim(s: ShortArray, channels: Int, rate: Int, threshold: Int = THRESHOLD, keepMs: Int = 20): ShortArray {
        val frames = s.size / channels
        fun loud(f: Int) = (0 until channels).any { c -> abs(s[f * channels + c].toInt()) > threshold }
        var first = 0
        while (first < frames && !loud(first)) first++
        if (first == frames) return ShortArray(0)
        var last = frames - 1
        while (last > first && !loud(last)) last--
        val keep = rate * keepMs / 1000
        val a = (first - keep).coerceAtLeast(0)
        val b = (last + keep).coerceAtMost(frames - 1)
        return s.copyOfRange(a * channels, (b + 1) * channels)
    }

    /** The clips one after another with [gapMs] of silence between them. */
    fun join(parts: List<ShortArray>, channels: Int, rate: Int, gapMs: Int = 60): ShortArray {
        val gap = rate * gapMs / 1000 * channels
        val total = parts.sumOf { it.size } + gap * (parts.size - 1).coerceAtLeast(0)
        val out = ShortArray(total)
        var pos = 0
        parts.forEachIndexed { i, p ->
            if (i > 0) pos += gap
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }
}
