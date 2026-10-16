package earth.dipstick.app.rec

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/**
 * Serialises access to one [MediaMuxer] shared by the video and audio encoders.
 *
 * MediaMuxer is not thread-safe and refuses samples until every track is registered, so the
 * gate holds writes back until all [expectedTracks] have arrived. The video encoder runs on
 * the GL thread and the audio encoder on its own thread; both funnel through here.
 */
internal class MuxerGate(
    private val muxer: MediaMuxer,
    private val expectedTracks: Int,
) {
    private var addedTracks = 0
    private var started = false
    private var stopped = false

    /** Total bytes of media written, for the on-screen size readout. */
    @Volatile
    var bytesWritten: Long = 0L
        private set

    /** Samples produced before the muxer can start; counted so the loss is visible in logs. */
    /**
     * Samples that arrived before every track was registered. They are held rather than
     * discarded: the AAC encoder cannot publish its format until it has been fed audio, and
     * throwing away the video frames captured during that window left the recording's tracks
     * disagreeing about when the video began.
     */
    private val pending = ArrayList<Pending>()
    private var pendingBytes = 0L

    private class Pending(
        val trackIndex: Int,
        val data: ByteArray,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        check(!started) { "cannot add a track after the muxer has started" }
        val index = muxer.addTrack(format)
        addedTracks++
        if (addedTracks == expectedTracks) {
            muxer.start()
            started = true
            Log.i(TAG, "muxer started with $addedTracks track(s)")
            flushPending()
        }
        return index
    }

    @Synchronized
    fun writeSample(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped || trackIndex < 0) return
        if (!started) {
            // Copy it out: the codec reclaims its buffer as soon as we return.
            if (pendingBytes + info.size <= MAX_PENDING_BYTES) {
                val copy = ByteArray(info.size)
                val dup = buffer.duplicate()
                dup.position(info.offset)
                dup.limit(info.offset + info.size)
                dup.get(copy)
                pending += Pending(
                    trackIndex, copy, 0, info.size, info.presentationTimeUs, info.flags
                )
                pendingBytes += info.size
            } else {
                Log.w(TAG, "pending buffer full; dropping sample on track $trackIndex")
            }
            return
        }
        muxer.writeSampleData(trackIndex, buffer, info)
        bytesWritten += info.size
    }

    /** Writes everything held back while the muxer was still collecting tracks. */
    private fun flushPending() {
        if (pending.isEmpty()) return
        val scratch = MediaCodec.BufferInfo()
        // Each track's samples are already in order; preserve arrival order across tracks.
        for (item in pending) {
            scratch.set(item.offset, item.size, item.presentationTimeUs, item.flags)
            muxer.writeSampleData(item.trackIndex, ByteBuffer.wrap(item.data), scratch)
            bytesWritten += item.size
        }
        Log.i(TAG, "flushed ${pending.size} buffered sample(s), ${pendingBytes / 1024}KB")
        pending.clear()
        pendingBytes = 0
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        if (pending.isNotEmpty()) {
            Log.w(TAG, "stopping with ${pending.size} sample(s) never flushed")
            pending.clear()
        }
        if (started) runCatching { muxer.stop() }.onFailure { Log.e(TAG, "muxer.stop", it) }
        runCatching { muxer.release() }
    }

    private companion object {
        const val TAG = "DipstickMux"

        /** Roughly a second of 720p video; enough for any realistic startup stall. */
        const val MAX_PENDING_BYTES = 4L * 1024 * 1024
    }
}
