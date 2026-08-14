package com.r1.audioprobe

/**
 * The last few seconds of audio, kept in memory.
 *
 * Needed because a question can start before the gesture that asks for it: you
 * press the button while already mid-sentence. Without this the query would
 * begin at the press and lose the opening words.
 *
 * Sized to hold the two minutes of context a question can ask for, plus slack.
 * At 48 kHz stereo PCM16 that is 28.8 MB — six times what it cost at 16 kHz
 * mono, and still small next to the heap, so the window stayed at 150 s rather
 * than being traded away for the format.
 *
 * Samples are stored exactly as AudioRecord delivers them, interleaved, and
 * every offset here is therefore in *samples* rather than frames. [channels]
 * exists only so the time arithmetic can convert between the two; nothing else
 * in this class cares how the samples are arranged.
 */
class RingBuffer(
    private val sampleRate: Int,
    private val channels: Int = 1,
    seconds: Int = 150,
) {

    private val capacity = sampleRate * channels * seconds
    private val data = ShortArray(capacity)
    private var writeIndex = 0
    private var written = 0L

    /** Wall-clock time of the most recent sample, for mapping times to offsets. */
    @Volatile private var lastSampleAtMs = 0L

    @Synchronized
    fun append(samples: ShortArray, count: Int, nowMs: Long) {
        var offset = 0
        var remaining = count
        while (remaining > 0) {
            val room = capacity - writeIndex
            val take = minOf(room, remaining)
            System.arraycopy(samples, offset, data, writeIndex, take)
            writeIndex = (writeIndex + take) % capacity
            offset += take
            remaining -= take
        }
        written += count
        lastSampleAtMs = nowMs
    }

    /**
     * Copies out everything captured between two wall-clock instants.
     *
     * Times are mapped to sample offsets from the newest sample, so this stays
     * correct even though the buffer itself holds no timestamps. Returns null
     * when the window has already been overwritten.
     */
    @Synchronized
    fun slice(fromMs: Long, toMs: Long): ShortArray? {
        if (lastSampleAtMs == 0L || toMs <= fromMs) return null

        // Times convert to frames; the buffer counts samples. Rounding down to
        // a whole frame matters for stereo: an odd offset would swap the
        // channels for the entire slice.
        val frameRate = sampleRate.toLong()
        val backFromEnd = ((lastSampleAtMs - fromMs) * frameRate / 1000).toInt() * channels
        val backToEnd =
            ((lastSampleAtMs - toMs) * frameRate / 1000).coerceAtLeast(0).toInt() * channels
        val length = backFromEnd - backToEnd
        if (length <= 0) return null

        val available = minOf(written, capacity.toLong()).toInt()
        if (backFromEnd > available) return null // asked for audio already gone

        val out = ShortArray(length)
        var index = ((writeIndex - backFromEnd) % capacity + capacity) % capacity
        for (i in 0 until length) {
            out[i] = data[index]
            index += 1
            if (index == capacity) index = 0
        }
        return out
    }
}
