package com.salmansaadiq.dheu.ui.messages

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.salmansaadiq.dheu.data.VOICE_MAX_SECONDS
import com.salmansaadiq.dheu.data.VoiceClip
import java.io.File

/**
 * Records a voice message to a temporary AAC .m4a ("audio/mp4"), which the
 * website's browsers and other phones all play. The chat screen stops it at
 * [VOICE_MAX_SECONDS]; the recorder's own limit is a little later, as a backstop.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val recording get() = recorder != null
    val elapsedMs get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Throws if the microphone can't be opened (in use by a call, say). */
    fun start() {
        cancel()
        val out = File.createTempFile("voice-", ".m4a", context.cacheDir)
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setMaxDuration((VOICE_MAX_SECONDS + 2) * 1000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            out.delete()
            throw e
        }
        recorder = r
        file = out
        startedAt = SystemClock.elapsedRealtime()
    }

    /** The finished clip, or null if it was too short to keep (under half a second). */
    fun stop(): VoiceClip? {
        val r = recorder ?: return null
        val out = file
        val duration = elapsedMs.coerceAtMost(VOICE_MAX_SECONDS * 1000L)
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess // throws when nothing was captured
        r.release()
        if (out == null) return null
        if (!ok || duration < 500 || out.length() == 0L) {
            out.delete()
            return null
        }
        return VoiceClip(out, duration)
    }

    fun cancel() {
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        recorder = null
        file?.delete()
        file = null
    }
}
