package com.onda.player

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject

/**
 * Reproductor nativo (Media3 / ExoPlayer) para música y videos.
 * Vive en el proceso: sigue sonando con la pantalla apagada y aguanta formatos que el WebView no abre.
 * La página solo manda órdenes y recibe el estado (posición, duración, errores).
 */
object Engine {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var player: ExoPlayer? = null

    /** Número de la carga actual: la página ignora mensajes de cargas anteriores. */
    @Volatile var lid = 0
    private var enhancer: LoudnessEnhancer? = null
    private var gainMb = 0

    /** pos ms, dur ms, reproduciendo: lo usan los controles de pantalla completa. */
    var onTick: ((Long, Long, Boolean) -> Unit)? = null

    /** Se llama (en el hilo principal) cuando cambia el tamaño del video. */
    var onSize: (() -> Unit)? = null

    fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun init(ctx: Context) {
        if (player != null) return
        val p = ExoPlayer.Builder(ctx.applicationContext).build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true
        )
        p.setHandleAudioBecomingNoisy(true)
        p.setWakeMode(C.WAKE_MODE_LOCAL)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                push("state")
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                push("state")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                onSize?.invoke()
                push("size")
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applyEnhancer(audioSessionId)
            }

            override fun onPlayerError(error: PlaybackException) {
                push("error", error.errorCode, error.errorCodeName, error.message ?: "")
            }
        })
        player = p
        main.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val dur = if (p.duration == C.TIME_UNSET) 0L else p.duration
                onTick?.invoke(p.currentPosition, dur, p.playWhenReady && p.playbackState == Player.STATE_READY)
                if (p.playWhenReady && p.playbackState == Player.STATE_READY) push("tick")
            }
            main.postDelayed(this, 250)
        }
    }

    fun push(e: String, ec: Int = 0, en: String = "", em: String = "") {
        val p = player ?: return
        try {
            val o = JSONObject()
            o.put("e", e)
            o.put("lid", lid)
            o.put("pos", p.currentPosition)
            o.put("dur", if (p.duration == C.TIME_UNSET) 0L else p.duration)
            o.put("pw", p.playWhenReady)
            o.put(
                "st", when (p.playbackState) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "buf"
                    Player.STATE_READY -> "ready"
                    else -> "ended"
                }
            )
            o.put("vw", p.videoSize.width)
            o.put("vh", p.videoSize.height)
            if (ec != 0) {
                o.put("ec", ec)
                o.put("en", en)
                o.put("em", em)
            }
            MainActivity.instance?.runJs("window.onNativePlayer&&window.onNativePlayer($o)")
        } catch (_: Exception) {
        }
    }

    fun load(uri: Uri, startMs: Long) = ui {
        val p = player ?: return@ui
        p.setMediaItem(MediaItem.fromUri(uri))
        p.playWhenReady = false
        p.prepare()
        if (startMs > 0) p.seekTo(startMs)
        push("state")
    }

    fun play() = ui { player?.play() }
    fun pause() = ui { player?.pause() }
    fun seek(ms: Long) = ui { player?.seekTo(ms.coerceAtLeast(0)) }
    fun stop() = ui {
        player?.stop()
        player?.clearMediaItems()
    }

    fun volume(v: Float) = ui { player?.volume = v.coerceIn(0f, 1f) }
    fun rate(r: Float) = ui { player?.playbackParameters = PlaybackParameters(r.coerceIn(0.25f, 4f)) }

    fun gain(mb: Int) {
        gainMb = mb.coerceIn(0, 1500)
        ui { applyEnhancer(player?.audioSessionId ?: 0) }
    }

    private fun applyEnhancer(session: Int) {
        try {
            enhancer?.release()
            enhancer = null
            if (session == 0 || session == C.AUDIO_SESSION_ID_UNSET) return
            val le = LoudnessEnhancer(session)
            le.setTargetGain(gainMb)
            le.enabled = gainMb > 0
            enhancer = le
        } catch (_: Exception) {
        }
    }

    fun release() = ui {
        try { enhancer?.release() } catch (_: Exception) {}
        enhancer = null
        player?.stop()
    }
}
