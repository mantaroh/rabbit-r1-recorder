package com.r1.audioprobe

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes one segment of captured audio.
 *
 * Two implementations because the tradeoff is not obvious. WAV costs nothing in
 * CPU and is trivially verifiable — you can open it and listen — but it is
 * 1.9 MB per minute, which is what makes a Wi-Fi-only upload policy awkward.
 * Opus is roughly an eighth of that on the wire.
 */
interface SegmentWriter {
    val file: File
    fun write(samples: ShortArray, count: Int)
    /** Finalises the container. Returns the bytes on disk, or 0 on failure. */
    fun close(): Long
}

/**
 * The header is written up front and kept roughly current as samples arrive,
 * rather than being written once at close.
 *
 * The obvious implementation — 44 zero bytes now, real header on close — makes
 * every segment undecodable until the moment it is finished, so a crash, a
 * kill, or a flat battery turns the recording in progress into a file no
 * decoder will open. The samples are all there; nothing will read them. For a
 * device whose output is an archive that is the wrong failure, and it is not
 * hypothetical: `seg_20260814_073239.wav` reached R2 with 44 zero bytes on the
 * front after a reinstall interrupted it.
 *
 * Refreshing costs a seek roughly once a second, and bounds the loss from an
 * interrupted segment to the samples written since the last refresh.
 */
class WavSegmentWriter(override val file: File, private val sampleRate: Int) : SegmentWriter {

    private val raf = RandomAccessFile(file, "rw").apply { write(header(0, sampleRate)) }
    private var dataBytes = 0
    private var declaredBytes = 0

    override fun write(samples: ShortArray, count: Int) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putShort(samples[i])
        // Explicit position: the header refresh below moves the file pointer.
        raf.seek((HEADER_BYTES + dataBytes).toLong())
        raf.write(bb.array())
        dataBytes += count * 2

        if (dataBytes - declaredBytes >= sampleRate * 2) refreshHeader()
    }

    private fun refreshHeader() {
        raf.seek(0)
        raf.write(header(dataBytes, sampleRate))
        declaredBytes = dataBytes
    }

    override fun close(): Long {
        return runCatching {
            refreshHeader()
            raf.close()
            file.length()
        }.getOrDefault(0L)
    }

    private companion object {
        const val HEADER_BYTES = 44

        fun header(dataBytes: Int, sampleRate: Int): ByteArray {
            val h = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + dataBytes)
            h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
            h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(sampleRate); h.putInt(sampleRate * 2)
            h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataBytes)
            return h.array()
        }
    }
}

/**
 * Opus in an Ogg container, via MediaCodec into MediaMuxer.
 *
 * `MUXER_OUTPUT_OGG` handles the Ogg page framing and the Opus codec-specific
 * data, which is the fiddly part; doing it by hand would be a lot of code to
 * get subtly wrong.
 */
class OpusSegmentWriter(
    override val file: File,
    private val sampleRate: Int,
    bitRate: Int,
) : SegmentWriter {

    companion object {
        private const val TAG = "R1AudioProbe"

        /** Returns null when this device cannot encode Opus, so the caller can fall back. */
        fun createOrNull(file: File, sampleRate: Int, bitRate: Int): OpusSegmentWriter? =
            runCatching { OpusSegmentWriter(file, sampleRate, bitRate) }
                .onFailure { Log.w(TAG, "opus encoder unavailable", it) }
                .getOrNull()
    }

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var samplesWritten = 0L
    private val info = MediaCodec.BufferInfo()

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE, // ignored by Opus, harmless
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
    }

    override fun write(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                // Encoder is busy; drain and drop this chunk rather than block
                // the capture loop, which must never wait on the codec.
                drain(endOfStream = false)
                return
            }
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            val room = buffer.capacity() / 2
            val take = minOf(room, count - offset)
            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            shorts.put(samples, offset, take)
            val presentationUs = samplesWritten * 1_000_000L / sampleRate
            codec.queueInputBuffer(index, 0, take * 2, presentationUs, 0)
            samplesWritten += take
            offset += take
        }
        drain(endOfStream = false)
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && muxerStarted &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    override fun close(): Long {
        return runCatching {
            val index = codec.dequeueInputBuffer(50_000)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index, 0, 0,
                    samplesWritten * 1_000_000L / sampleRate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            drain(endOfStream = true)
            codec.stop(); codec.release()
            if (muxerStarted) { muxer.stop() }
            muxer.release()
            file.length()
        }.onFailure {
            Log.e(TAG, "closing opus segment failed", it)
            runCatching { codec.release() }
            runCatching { muxer.release() }
        }.getOrDefault(0L)
    }
}
