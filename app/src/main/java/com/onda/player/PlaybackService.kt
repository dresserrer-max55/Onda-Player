package com.onda.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servicio en primer plano: mantiene viva la música con la pantalla apagada y
 * muestra la notificación multimedia (barra de arriba) con anterior / pausa / siguiente.
 */
class PlaybackService : Service() {

    companion object {
        const val CH = "pulso_playback"
        const val NID = 7
        const val A_START = "com.onda.player.START"
        const val A_PLAY = "com.onda.player.PLAY"
        const val A_PAUSE = "com.onda.player.PAUSE"
        const val A_NEXT = "com.onda.player.NEXT"
        const val A_PREV = "com.onda.player.PREV"
        const val A_DISMISS = "com.onda.player.DISMISS"
        const val A_CLOSE = "com.onda.player.CLOSE"

        @Volatile var instance: PlaybackService? = null

        /** Llamado cada vez que la página informa un cambio de estado. */
        fun sync(ctx: Context) {
            val s = instance
            if (NowPlaying.has) {
                if (s == null) {
                    try {
                        ctx.startForegroundService(Intent(ctx, PlaybackService::class.java).setAction(A_START))
                    } catch (_: Exception) {
                        // App en segundo plano sin permiso para iniciar el servicio: se ignora.
                    }
                } else {
                    s.refresh(false)
                }
            } else {
                s?.shutdown()
            }
        }
    }

    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastKey = ""
    private var fg = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Reproducción", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        session = MediaSession(this, "Onda").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { Bridge.dispatch("play") }
                override fun onPause() { Bridge.dispatch("pause") }
                override fun onStop() { Bridge.dispatch("stop") }
                override fun onSkipToNext() { Bridge.dispatch("next") }
                override fun onSkipToPrevious() { Bridge.dispatch("prev") }
                override fun onSeekTo(pos: Long) { Bridge.dispatch("seek:$pos") }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == "close") {
                        Bridge.dispatch("close")
                        NowPlaying.has = false
                        NowPlaying.playing = false
                        shutdown()
                    }
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_PLAY -> Bridge.dispatch("play")
            A_PAUSE -> Bridge.dispatch("pause")
            A_NEXT -> Bridge.dispatch("next")
            A_PREV -> Bridge.dispatch("prev")
            A_CLOSE -> {
                Bridge.dispatch("close")
                NowPlaying.has = false
                NowPlaying.playing = false
                shutdown()
                return START_NOT_STICKY
            }
            A_DISMISS -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                ensureForeground()
                refresh(true)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun ensureForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NID, n)
        }
        fg = true
    }

    fun refresh(force: Boolean) {
        val s = session ?: return
        val np = NowPlaying
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
            )
            .addCustomAction(
                PlaybackState.CustomAction.Builder("close", "Cerrar", R.drawable.ic_close).build()
            )
            .setState(
                if (np.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                np.posMs,
                if (np.playing) 1f else 0f
            )
            .build()
        s.setPlaybackState(state)

        val key = "${np.title}|${np.artist}|${np.playing}|${np.art?.hashCode() ?: 0}|${np.durMs}"
        if (force || key != lastKey) {
            lastKey = key
            val md = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, np.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, np.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, np.durMs)
            np.art?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            s.setMetadata(md.build())
            postNotification()
        }
        updateWakeLock(np.playing)
    }

    private fun postNotification() {
        val n = buildNotification()
        val nm = getSystemService(NotificationManager::class.java)
        if (NowPlaying.playing) {
            if (!fg) {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NID, n)
                }
                fg = true
            } else {
                nm.notify(NID, n)
            }
        } else {
            if (fg) {
                stopForeground(STOP_FOREGROUND_DETACH)
                fg = false
            }
            nm.notify(NID, n)
        }
    }

    private fun buildNotification(): Notification {
        val np = NowPlaying
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(if (np.title.isEmpty()) "Onda" else np.title)
            .setContentText(if (np.artist.isEmpty()) "Onda" else np.artist)
            .setContentIntent(contentPi())
            .setDeleteIntent(servicePi(A_DISMISS, 9))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(np.playing)
        np.art?.let { b.setLargeIcon(it) }
        b.addAction(action(R.drawable.ic_prev, "Anterior", A_PREV, 1))
        if (np.playing) {
            b.addAction(action(R.drawable.ic_pause, "Pausa", A_PAUSE, 2))
        } else {
            b.addAction(action(R.drawable.ic_play, "Reproducir", A_PLAY, 2))
        }
        b.addAction(action(R.drawable.ic_next, "Siguiente", A_NEXT, 3))
        b.addAction(action(R.drawable.ic_close, "Cerrar", A_CLOSE, 4))
        val style = Notification.MediaStyle().setShowActionsInCompactView(1, 2, 3)
        session?.let { style.setMediaSession(it.sessionToken) }
        b.setStyle(style)
        return b.build()
    }

    private fun action(icon: Int, title: String, act: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, servicePi(act, code)).build()

    private fun servicePi(act: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun contentPi(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun updateWakeLock(playing: Boolean) {
        try {
            if (playing) {
                if (wake == null) {
                    wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulso:playback")
                }
                if (wake?.isHeld == false) wake?.acquire(6 * 60 * 60 * 1000L)
            } else if (wake?.isHeld == true) {
                wake?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun cleanup() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
        try { session?.isActive = false; session?.release() } catch (_: Exception) {}
        session = null
        instance = null
    }

    fun shutdown() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        fg = false
        cleanup()
        stopSelf()
    }
}
