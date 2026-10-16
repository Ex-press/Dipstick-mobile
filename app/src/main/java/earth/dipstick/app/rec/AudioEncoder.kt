package earth.dipstick.app.rec

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Captures the phone microphone and encodes it to AAC for the recording's audio track,
 * so the operator can narrate an inspection while it happens.
 *
 * Timestamps are derived from the sample count rather than the wall clock, which keeps the
 * track drift-free, and are offset by how long after [startNanos] capture actually began so
 * the voiceover lines up with the video.
 */
internal class AudioEncoder(
    private val gate: MuxerGate,
    private val startNanos: Long,
) {
    private var codec: MediaCodec? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile private var running = false
    private var trackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()

    private var totalSamples = 0L
    private var offsetUs = 0L

    /** @return true if the mic opened and encoding began. */
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioRecord reports no usable buffer size")
            return false
        }
        // The AudioRecord ring stays generous so we never overrun...
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE / 5 * BYTES_PER_SAMPLE)
        // ...but we read in small chunks. AudioRecord.read blocks until the requested bytes
        // exist, so a large read would delay the encoder's first output - and with it the
        // muxer start - by the whole chunk duration.
        val readChunk = minOf(bufferSize, READ_CHUNK_BYTES)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO not granted", e)
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            audioRecord.release()
            return false
        }

        val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNELS).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }

        val encoder = try {
            MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not start AAC encoder", e)
            audioRecord.release()
            return false
        }

        recorder = audioRecord
        codec = encoder
        running = true
        audioRecord.startRecording()

        worker = thread(name = "dipstick-audio", isDaemon = true) { captureLoop(readChunk) }
        Log.i(TAG, "audio capture started")
        return true
    }

    private fun captureLoop(bufferSize: Int) {
        val encoder = codec ?: return
        val audioRecord = recorder ?: return
        val pcm = ByteArray(bufferSize)
        var first = true

        while (running) {
            val read = audioRecord.read(pcm, 0, pcm.size)
            if (read <= 0) continue

            if (first) {
                first = false
                offsetUs = (System.nanoTime() - startNanos) / 1_000
                // The buffer we just received was captured *before* now, so wind back.
                offsetUs -= read.toLong() / BYTES_PER_SAMPLE * 1_000_000L / SAMPLE_RATE
                if (offsetUs < 0) offsetUs = 0
            }

            val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val input = encoder.getInputBuffer(inIndex)
                input?.clear()
                input?.put(pcm, 0, read)
                val ptsUs = offsetUs + totalSamples * 1_000_000L / SAMPLE_RATE
                encoder.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                totalSamples += read / BYTES_PER_SAMPLE
            }
            drain(endOfStream = false)
        }

        // Flush: tell the encoder we are done, then collect what it still owes us.
        runCatching {
            val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US * 10)
            if (inIndex >= 0) {
                val ptsUs = offsetUs + totalSamples * 1_000_000L / SAMPLE_RATE
                encoder.queueInputBuffer(
                    inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain(endOfStream = true)
        }.onFailure { Log.e(TAG, "audio flush failed", it) }
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        val deadline = System.nanoTime() + FLUSH_TIMEOUT_NANOS
        while (true) {
            if (endOfStream && System.nanoTime() > deadline) {
                Log.w(TAG, "gave up waiting for audio EOS")
                return
            }
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIndex < 0) trackIndex = gate.addTrack(encoder.outputFormat)
                }

                outIndex < 0 -> Unit

                else -> {
                    val encoded = encoder.getOutputBuffer(outIndex)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && encoded != null) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        gate.writeSample(trackIndex, encoded, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false

        // The worker owns the codec while it drains. Releasing underneath it would be a
        // native use-after-free, so wait it out before touching anything it holds.
        runCatching { worker?.join(WORKER_JOIN_MS) }
        if (worker?.isAlive == true) {
            Log.w(TAG, "audio worker still running after ${WORKER_JOIN_MS}ms; leaking codec")
            worker = null
            return
        }
        worker = null

        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null

        codec?.runCatching { stop() }
        codec?.runCatching { release() }
        codec = null
        Log.i(TAG, "audio capture stopped")
    }

    private companion object {
        const val TAG = "DipstickAudio"
        const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 1
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
        const val BIT_RATE = 96_000
        const val TIMEOUT_US = 10_000L
        /** ~23ms at 44.1kHz mono, one AAC frame's worth. */
        const val READ_CHUNK_BYTES = 2048
        const val FLUSH_TIMEOUT_NANOS = 1_500_000_000L
        const val WORKER_JOIN_MS = 4_000L
    }
}
